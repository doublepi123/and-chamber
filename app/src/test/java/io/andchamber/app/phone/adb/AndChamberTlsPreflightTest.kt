package io.andchamber.app.phone.adb

import io.github.muntashirakon.adb.AdbTlsGuardException
import io.github.muntashirakon.adb.AdbTlsGuardFailure
import io.github.muntashirakon.adb.AndChamberAdbTls
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Protocol-level check of the plaintext-downgrade guard, without a device. A local fake daemon
 * answers CNXN with the command a real daemon would use.
 */
class AndChamberTlsPreflightTest {
    @Test
    fun plaintextCnxnAndAuthAreRefused() {
        assertGuardFailure(AdbTlsGuardFailure.TLS_REQUIRED, A_CNXN)
        assertGuardFailure(AdbTlsGuardFailure.TLS_REQUIRED, A_AUTH)
    }

    @Test
    fun stlsOfferIsAccepted() {
        withFakeDaemon(A_STLS, timeoutMs = 2_000) { port ->
            AndChamberAdbTls.probeStls("127.0.0.1", port, API, 2_000)
        }
    }

    @Test
    fun nonAdbResponseAndSilenceAreRefused() {
        assertGuardFailure(AdbTlsGuardFailure.PROTOCOL, 0x41414141)

        withFakeDaemon(null, timeoutMs = 1_500) { port ->
            val failure = assertThrows(AdbTlsGuardException::class.java) {
                AndChamberAdbTls.probeStls("127.0.0.1", port, API, 300)
            }
            assertEquals(AdbTlsGuardFailure.DEADLINE, failure.failure)
        }
    }

    @Test
    fun closedPortIsUnreachable() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val failure = assertThrows(AdbTlsGuardException::class.java) {
            AndChamberAdbTls.probeStls("127.0.0.1", port, API, 500)
        }
        assertEquals(AdbTlsGuardFailure.UNREACHABLE, failure.failure)
    }

    private fun assertGuardFailure(expected: AdbTlsGuardFailure, command: Int) {
        withFakeDaemon(command, timeoutMs = 2_000) { port ->
            val failure = assertThrows(AdbTlsGuardException::class.java) {
                AndChamberAdbTls.probeStls("127.0.0.1", port, API, 2_000)
            }
            assertEquals(expected, failure.failure)
        }
    }

    private fun withFakeDaemon(
        responseCommand: Int?,
        timeoutMs: Long,
        block: (Int) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = Thread {
            try {
                server.accept().use { client ->
                    client.soTimeout = 2_000
                    val request = ByteArray(24)
                    var offset = 0
                    while (offset < request.size) {
                        val read = client.getInputStream().read(request, offset, request.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    if (responseCommand == null) {
                        Thread.sleep(timeoutMs)
                    } else {
                        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(responseCommand)
                            .putInt(0)
                            .putInt(0)
                            .putInt(0)
                            .putInt(0)
                            .putInt(0)
                            .array()
                        client.getOutputStream().write(header)
                        client.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {
                // The client closing early is normal for a refusal.
            } finally {
                runCatching { server.close() }
            }
        }.apply {
            isDaemon = true
            name = "fake-adbd"
            start()
        }
        try {
            block(server.localPort)
        } finally {
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    private companion object {
        const val API = 35
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453
    }
}
