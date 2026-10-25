// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import io.github.muntashirakon.adb.AndChamberAdbStreamReaderException
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Minimal view of the LibADB stream/connection state used by the command reader. The production
 * adapter wraps `AdbConnection`/`AdbStream`; tests provide a fake that returns bytes and then closes
 * normally or drops the link.
 */
internal interface AdbIo {
    /** True only while the underlying connection is still established and its socket is connected. */
    val alive: Boolean

    fun open(destination: String): AdbIoStream
}

internal interface AdbIoStream {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/**
 * The single read/completion classifier used by [LibAdbDevice].
 *
 * LibADB throws the same `IOException("Stream closed.")` for a normal remote CLSE and for a stream
 * torn down by a dead connection. The connection state is the discriminator:
 * - normal CLSE: `isConnectionEstablished` and `isConnected` stay true;
 * - connection-thread failure: it runs `cleanupStreams()` then sets `mConnectionEstablished=false`;
 * - watchdog/disconnect: the socket is closed.
 * [AdbIo.alive] must read those two LibADB getters together under the connection monitor (the
 * production adapter does), so a reader that wakes during the cleanup race waits until the
 * connection thread has published its final state.
 *
 * A read that ends with the link still alive is normal completion. A read that ends after the link
 * died is [AdbDeviceFailure.OUTCOME_UNKNOWN]; for an injected command that is mapped to Unknown, not
 * Sent. Deadline/interruption/overflow have their own fixed failures.
 *
 * This function is deliberately the only read loop in the device; a regression that swallows every
 * IOException and returns success fails the connection-loss tests in [AdbStreamSessionTest].
 */
internal object AdbStreamSession {
    const val INITIAL_BUFFER_BYTES: Int = 64 * 1024
    const val READ_CHUNK_BYTES: Int = 16 * 1024

    fun readAll(
        link: AdbIo,
        stream: AdbIoStream,
        maxBytes: Int,
        expired: () -> Boolean,
        afterWrite: Boolean,
    ): ByteArray {
        if (maxBytes <= 0) throw AdbDeviceException(AdbDeviceFailure.LIMIT, afterWrite)
        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = try {
                stream.read(buffer, 0, buffer.size)
            } catch (failure: IOException) {
                if (expired()) {
                    throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite, failure)
                }
                if (!link.alive) {
                    throw AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN, afterWrite, failure)
                }
                if (failure.cause is InterruptedException) {
                    throw AdbDeviceException(AdbDeviceFailure.CANCELLED, afterWrite, failure)
                }
                if (failure is AndChamberAdbStreamReaderException) {
                    // Pinned SDK layout/read adapter failure: not a normal CLSE, never Sent.
                    throw AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN, afterWrite, failure)
                }
                // Normal remote CLSE while the connection is still established.
                break
            }
            if (read < 0) break
            if (read == 0) {
                if (expired()) {
                    throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite)
                }
                continue
            }
            if (output.size() + read > maxBytes) {
                throw AdbDeviceException(AdbDeviceFailure.LIMIT, afterWrite)
            }
            output.write(buffer, 0, read)
        }
        if (expired()) throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite)
        if (!link.alive) {
            // The stream ended, but the connection is no longer established: completion is not
            // confirmable. Never hand a partial image/snapshot back as if it were complete.
            throw AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN, afterWrite)
        }
        return output.toByteArray()
    }

    /** Waits for the fixed command's stream to close; output is bounded and never logged. */
    fun drain(
        link: AdbIo,
        stream: AdbIoStream,
        maxOutputBytes: Int,
        expired: () -> Boolean,
        afterWrite: Boolean,
    ) {
        if (maxOutputBytes <= 0) throw AdbDeviceException(AdbDeviceFailure.LIMIT, afterWrite)
        val buffer = ByteArray(minOf(512, maxOutputBytes))
        var drained = 0
        while (true) {
            val read = try {
                stream.read(buffer, 0, buffer.size)
            } catch (failure: IOException) {
                if (expired()) {
                    throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite, failure)
                }
                if (!link.alive) {
                    throw AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN, afterWrite, failure)
                }
                if (failure.cause is InterruptedException) {
                    throw AdbDeviceException(AdbDeviceFailure.CANCELLED, afterWrite, failure)
                }
                if (failure is AndChamberAdbStreamReaderException) {
                    throw AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN, afterWrite, failure)
                }
                // Normal CLSE: the fixed shell command has finished.
                return
            }
            if (read < 0) {
                if (!link.alive) {
                    throw AdbDeviceException(AdbDeviceFailure.OUTCOME_UNKNOWN, afterWrite)
                }
                return
            }
            if (read == 0) {
                if (expired()) {
                    throw AdbDeviceException(AdbDeviceFailure.DEADLINE, afterWrite)
                }
                continue
            }
            drained += read
            if (drained > maxOutputBytes) {
                throw AdbDeviceException(AdbDeviceFailure.LIMIT, afterWrite)
            }
        }
    }
}
