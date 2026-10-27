// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import android.os.Build
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.AdbTlsGuardException
import io.github.muntashirakon.adb.AdbTlsGuardFailure
import io.github.muntashirakon.adb.AndChamberAdbStreamReader
import io.github.muntashirakon.adb.AndChamberAdbTls
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LibADB-backed device transport with three gates that the upstream library does not provide:
 * a numeric local-only host policy, a pairing receipt, and a mandatory TLS upgrade. A connection
 * that completed over plaintext is closed and reported as [AdbDeviceFailure.TLS_REQUIRED].
 *
 * All reads are byte-capped and every blocking call is bounded by a watchdog that closes the
 * stream/connection, so a coroutine timeout is not used as a pretend cancellation.
 */
class LibAdbDevice(
    private val identity: AdbIdentity,
    private val host: String,
    private val port: Int,
    private val pairingReceipts: AdbPairingReceiptStore,
    private val hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    private val api: Int = Build.VERSION.SDK_INT,
) : AdbDevice {
    private val lock = Any()
    private var connection: AdbConnection? = null
    private var link: AdbIo? = null

    @Volatile
    override var transportSecurity: AdbTransportSecurity = AdbTransportSecurity.PLAINTEXT
        private set

    override fun connect(deadlineMs: Long) {
        if (deadlineMs <= 0L) throw AdbDeviceException(AdbDeviceFailure.DEADLINE)
        if (!identity.isConsistent()) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        hostPolicy.refusal(host)?.let { throw AdbDeviceException(AdbDeviceFailure.POLICY) }
        if (port !in 1..65535) throw AdbDeviceException(AdbDeviceFailure.POLICY)
        if (api < Build.VERSION_CODES.Q) {
            throw AdbDeviceException(AdbDeviceFailure.TLS_UNAVAILABLE)
        }
        if (!pairingReceipts.has(identity, host)) {
            // A connection is not a pairing. On ro.adb.secure=0 a fresh key can connect without
            // ever pairing, so the app requires its own successful pairing receipt first.
            throw AdbDeviceException(AdbDeviceFailure.PAIRING_REQUIRED)
        }
        synchronized(lock) {
            if (connection != null) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        }

        try {
            AndChamberAdbTls.prepareContext(identity.privateKey, identity.certificate)
        } catch (failure: Throwable) {
            throw AdbDeviceException(AdbDeviceFailure.TLS_UNAVAILABLE, cause = failure)
        }

        val guardBudget = (deadlineMs / 3L).coerceIn(200L, 5_000L).toInt()
        try {
            AndChamberAdbTls.probeStls(host, port, api, guardBudget)
        } catch (failure: AdbTlsGuardException) {
            throw AdbDeviceException(mapGuard(failure.failure), cause = failure)
        }

        var pending: AdbConnection? = null
        val watchdog = DeadlineWatchdog(deadlineMs) {
            runCatching { pending?.close() }
        }
        try {
            pending = try {
                AdbConnection.Builder(host, port)
                    .setApi(api)
                    .setPrivateKey(identity.privateKey)
                    .setCertificate(identity.certificate)
                    .setDeviceName(identity.deviceName)
                    .build()
            } catch (failure: IOException) {
                throw AdbDeviceException(AdbDeviceFailure.UNREACHABLE, cause = failure)
            } catch (failure: Throwable) {
                throw AdbDeviceException(AdbDeviceFailure.TLS_UNAVAILABLE, cause = failure)
            }
            val opened = try {
                pending.connect(watchdog.remainingMs(), TimeUnit.MILLISECONDS, true)
            } catch (failure: AdbAuthenticationFailedException) {
                throw AdbDeviceException(AdbDeviceFailure.AUTH_FAILED, cause = failure)
            } catch (failure: AdbPairingRequiredException) {
                throw AdbDeviceException(AdbDeviceFailure.PAIRING_REQUIRED, cause = failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AdbDeviceException(AdbDeviceFailure.CANCELLED, cause = failure)
            } catch (failure: IllegalStateException) {
                throw AdbDeviceException(AdbDeviceFailure.DRIVER, cause = failure)
            } catch (failure: IOException) {
                throw AdbDeviceException(
                    if (watchdog.expired) {
                        AdbDeviceFailure.DEADLINE
                    } else {
                        AdbDeviceFailure.UNREACHABLE
                    },
                    cause = failure,
                )
            }
            if (!opened) throw AdbDeviceException(AdbDeviceFailure.DEADLINE)
            if (watchdog.expired) throw AdbDeviceException(AdbDeviceFailure.DEADLINE)
            if (!pending.isConnectionEstablished) {
                throw AdbDeviceException(AdbDeviceFailure.DRIVER)
            }
            if (!AndChamberAdbTls.hasTls(pending)) {
                throw AdbDeviceException(AdbDeviceFailure.TLS_REQUIRED)
            }
            // The connection is about to become the installed one: claim normal completion first so
            // an expiring old watchdog cannot close this newly installed connection afterwards.
            if (!watchdog.complete()) throw AdbDeviceException(AdbDeviceFailure.DEADLINE)
            val openedLink = LibAdbLink(pending)
            synchronized(lock) {
                connection = pending
                link = openedLink
            }
            transportSecurity = AdbTransportSecurity.TLS
        } catch (failure: AdbDeviceException) {
            runCatching { pending?.close() }
            throw failure
        } finally {
            watchdog.cancel()
        }
    }

    override fun disconnect() {
        val current = synchronized(lock) {
            val value = connection
            connection = null
            link = null
            value
        }
        transportSecurity = AdbTransportSecurity.PLAINTEXT
        runCatching { current?.close() }
    }

    override fun isConnected(): Boolean {
        val current = synchronized(lock) { connection } ?: return false
        return current.isConnected && current.isConnectionEstablished
    }

    override fun hierarchyXml(deadlineMs: Long, maxBytes: Int): ByteArray {
        val bytes = readService(AdbCommandSchema.HIERARCHY_DESTINATION, deadlineMs, maxBytes)
        if (bytes.isEmpty()) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        // Only the whole fixed AOSP sentence becomes HIERARCHY_NOT_IDLE. Any XML that merely
        // contains those words still goes to the bounded parser unchanged.
        AdbHierarchyOutput.failure(bytes)?.let { throw AdbDeviceException(it) }
        return bytes
    }

    override fun windowStateText(deadlineMs: Long, maxBytes: Int): String {
        val bytes = readService(AdbCommandSchema.WINDOW_STATE_DESTINATION, deadlineMs, maxBytes)
        if (bytes.isEmpty()) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        return String(bytes, Charsets.UTF_8)
    }

    override fun windowDetailText(windowToken: String, deadlineMs: Long, maxBytes: Int): String {
        val bytes = readService(
            AdbCommandSchema.windowDetailDestination(windowToken),
            deadlineMs,
            maxBytes,
        )
        if (bytes.isEmpty()) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        return String(bytes, Charsets.UTF_8)
    }

    override fun displaySizeText(deadlineMs: Long, maxBytes: Int): String {
        val bytes = readService(AdbCommandSchema.DISPLAY_SIZE_DESTINATION, deadlineMs, maxBytes)
        if (bytes.isEmpty()) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        return String(bytes, Charsets.UTF_8)
    }

    override fun capturePng(deadlineMs: Long, maxBytes: Int): ByteArray {
        val bytes = readService(AdbCommandSchema.CAPTURE_DESTINATION, deadlineMs, maxBytes)
        if (!AdbPng.isComplete(bytes)) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        return bytes
    }

    override fun inject(command: AdbInputCommand, deadlineMs: Long): AdbInjection {
        val current: AdbConnection
        val io: AdbIo
        synchronized(lock) {
            current = connection ?: throw AdbDeviceException(AdbDeviceFailure.NOT_CONNECTED)
            io = link ?: throw AdbDeviceException(AdbDeviceFailure.NOT_CONNECTED)
        }
        if (!current.isConnected || !current.isConnectionEstablished) {
            throw AdbDeviceException(AdbDeviceFailure.NOT_CONNECTED)
        }
        // OPEN carries the fixed destination, so the command is dispatched by open() itself. The
        // stream is then read to normal CLSE; only that, with the connection still alive, is Sent.
        val destination = AdbCommandSchema.destination(command)
        var stream: AdbIoStream? = null
        val watchdog = DeadlineWatchdog(deadlineMs) {
            runCatching { stream?.close() }
            runCatching { current.close() }
        }
        try {
            stream = try {
                io.open(destination)
            } catch (failure: AdbPairingRequiredException) {
                throw AdbDeviceException(AdbDeviceFailure.PAIRING_REQUIRED, cause = failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AdbDeviceException(AdbDeviceFailure.CANCELLED, cause = failure)
            } catch (failure: IOException) {
                throw AdbDeviceException(
                    if (watchdog.expired) {
                        AdbDeviceFailure.DEADLINE
                    } else {
                        AdbDeviceFailure.OUTCOME_UNKNOWN
                    },
                    afterWrite = true,
                    cause = failure,
                )
            }
            if (watchdog.expired) {
                throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite = true)
            }
            AdbStreamSession.drain(
                link = io,
                stream = stream,
                maxOutputBytes = INJECT_MAX_OUTPUT_BYTES,
                expired = { watchdog.expired },
                afterWrite = true,
            )
            if (watchdog.expired) {
                throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite = true)
            }
            // Same completion/deadline gate as a read: a successful injection is not followed by a
            // late watchdog closing the connection it just used.
            if (!watchdog.complete()) {
                throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite = true)
            }
            return AdbInjection(written = true)
        } finally {
            watchdog.cancel()
            runCatching { stream?.close() }
        }
    }

    private fun readService(destination: String, deadlineMs: Long, maxBytes: Int): ByteArray {
        if (deadlineMs <= 0L) throw AdbDeviceException(AdbDeviceFailure.DEADLINE)
        if (maxBytes <= 0) throw AdbDeviceException(AdbDeviceFailure.LIMIT)
        val current: AdbConnection
        val io: AdbIo
        synchronized(lock) {
            current = connection ?: throw AdbDeviceException(AdbDeviceFailure.NOT_CONNECTED)
            io = link ?: throw AdbDeviceException(AdbDeviceFailure.NOT_CONNECTED)
        }
        if (!current.isConnected || !current.isConnectionEstablished) {
            throw AdbDeviceException(AdbDeviceFailure.NOT_CONNECTED)
        }
        var stream: AdbIoStream? = null
        // The hard watchdog keeps its old job: close the stream/connection to unblock the read. It
        // is not replaced by coroutine cancellation or a stream-only close.
        val watchdog = DeadlineWatchdog(deadlineMs) {
            runCatching { stream?.close() }
            runCatching { current.close() }
        }
        try {
            stream = try {
                io.open(destination)
            } catch (failure: AdbPairingRequiredException) {
                throw AdbDeviceException(AdbDeviceFailure.PAIRING_REQUIRED, cause = failure)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AdbDeviceException(AdbDeviceFailure.CANCELLED, cause = failure)
            } catch (failure: IOException) {
                // The read service transaction has already started. If its watchdog expired (even
                // before the first byte), this call has abandoned the exact connection: it is
                // READ_TIMEOUT, not an ordinary DEADLINE. Only the pre-call `deadlineMs <= 0`
                // refusal above stays DEADLINE.
                throw AdbDeviceException(
                    if (watchdog.deadlinePassed) {
                        AdbDeviceFailure.READ_TIMEOUT
                    } else {
                        AdbDeviceFailure.DRIVER
                    },
                    cause = failure,
                )
            }
            val bytes = AdbStreamSession.readAll(
                link = io,
                stream = stream,
                maxBytes = maxBytes,
                // The real monotonic deadline decides, not a late-scheduled watchdog thread.
                expired = { watchdog.deadlinePassed },
                afterWrite = false,
                expiredFailure = AdbDeviceFailure.READ_TIMEOUT,
            )
            // Normal completion racing the deadline: the gate lets exactly one side win, so a late
            // watchdog can never invalidate this exact link after a successful read.
            if (!watchdog.complete()) throw AdbDeviceException(AdbDeviceFailure.READ_TIMEOUT)
            return bytes
        } catch (failure: AdbDeviceException) {
            if (failure.failure == AdbDeviceFailure.READ_TIMEOUT) {
                // Before READ_TIMEOUT leaves this class the exact connection is already abandoned
                // and unusable for control. The existing hard-close path keeps running on the
                // watchdog thread; no caller waits for it and a newer connection is never cleared.
                abandonReadConnection(current)
            }
            throw failure
        } finally {
            watchdog.cancel()
            runCatching { stream?.close() }
        }
    }

    /**
     * Drops the references only while they still point at [current]. A reconnect that installed a
     * newer connection is left alone, including its TLS state.
     */
    private fun abandonReadConnection(current: AdbConnection) {
        synchronized(lock) {
            if (connection !== current) return
            connection = null
            link = null
            transportSecurity = AdbTransportSecurity.PLAINTEXT
        }
    }

    private fun mapGuard(failure: AdbTlsGuardFailure): AdbDeviceFailure = when (failure) {
        AdbTlsGuardFailure.UNREACHABLE -> AdbDeviceFailure.UNREACHABLE
        AdbTlsGuardFailure.DEADLINE -> AdbDeviceFailure.DEADLINE
        AdbTlsGuardFailure.TLS_REQUIRED -> AdbDeviceFailure.TLS_REQUIRED
        AdbTlsGuardFailure.PROTOCOL -> AdbDeviceFailure.TLS_REQUIRED
        AdbTlsGuardFailure.CANCELLED -> AdbDeviceFailure.CANCELLED
    }

    private companion object {
        const val INJECT_MAX_OUTPUT_BYTES = 4 * 1024
    }
}

/**
 * Production adapter for the read classifier. `alive` reads LibADB's established state and the
 * socket state together under the connection object's monitor. On a connection-thread failure the
 * thread holds that monitor while it cleans streams and sets `mConnectionEstablished=false`, so a
 * reader that just saw its stream closed waits here for the final state instead of misclassifying
 * the abnormal close as a normal CLSE.
 */
private class LibAdbLink(private val connection: AdbConnection) : AdbIo {
    override val alive: Boolean
        get() = synchronized(connection) {
            connection.isConnected && connection.isConnectionEstablished
        }

    override fun open(destination: String): AdbIoStream =
        LibAdbStream(connection.open(destination))
}

private class LibAdbStream(private val stream: AdbStream) : AdbIoStream {
    private val reader = AndChamberAdbStreamReader(stream)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader.read(buffer, offset, length)

    override fun close() {
        stream.close()
    }
}

/**
 * Closes the active stream/connection when a blocking ADB call runs past its deadline. The close is
 * what unblocks the socket read; a coroutine `withTimeout` alone cannot interrupt it.
 */
private class DeadlineWatchdog(
    deadlineMs: Long,
    private val onExpire: () -> Unit,
) {
    private val deadlineAtMs = System.nanoTime() / 1_000_000L + deadlineMs
    private val gate = DeadlineGate(deadlineAtMs)

    val expired: Boolean get() = gate.expired

    /** True once expiry was claimed or the real monotonic deadline has passed. */
    val deadlinePassed: Boolean get() = gate.deadlinePassed

    private val thread = Thread {
        try {
            while (!gate.expired) {
                val remaining = deadlineAtMs - System.nanoTime() / 1_000_000L
                if (remaining <= 0L) {
                    if (gate.expire()) runCatching { onExpire() }
                    return@Thread
                }
                Thread.sleep(minOf(remaining, 50L))
            }
        } catch (_: InterruptedException) {
            // Cancelled normally.
        }
    }.apply {
        isDaemon = true
        name = "andchamber-adb-deadline"
        start()
    }

    fun remainingMs(): Long =
        (deadlineAtMs - System.nanoTime() / 1_000_000L).coerceAtLeast(1L)

    /**
     * Claims a normal completion against the real monotonic deadline. False means the deadline
     * already passed or the watchdog owns the outcome; the watchdog is then left able to hard-close.
     */
    fun complete(): Boolean = gate.finish()

    /**
     * Stops the watchdog only when a normal (pre-deadline) end was claimed. After the deadline this
     * is a no-op so it cannot steal expiry from a late-scheduled watchdog thread.
     */
    fun cancel() {
        if (gate.finish()) thread.interrupt()
    }
}
