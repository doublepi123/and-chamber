package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbDriverEndpoint
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbPairingReceiptStore
import io.andchamber.app.phone.adb.AdbPairingReceiptStoreException
import io.andchamber.app.phone.adb.AdbTransportSecurity
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paired identity must survive an app restart: the AndroidKeyStore key is persistent, so the
 * app-private receipt is too. These tests use the real [AdbPairingReceiptStore] and receipt codec
 * over an in-memory backend, with an engine that behaves like the service (pair records a receipt,
 * connect needs one). A new facade instance over the same backend is the "process restart".
 */
class PhoneDriverFacadePairingReceiptTest {
    private val certificate = "ab".repeat(32)
    private val otherCertificate = "cd".repeat(32)
    private val host = "127.0.0.1"

    private class FakeBackend(
        var blob: String? = null,
    ) : PairingReceiptBackend {
        var writes = 0
        var failWrites = false

        override fun read(): String? = blob

        override fun write(encoded: String) {
            if (failWrites) throw IOException("no space left")
            writes += 1
            blob = encoded
        }
    }

    /** Mirrors the service surface the facade relies on: the receipt gates a real connect. */
    private class FakeEngine(
        private val certificateFingerprint: String,
        private val receipts: AdbPairingReceiptStore,
    ) : PhoneDriverEngine {
        var engineHost: String? = null
        var pairCalls = 0
        var connectCalls = 0
        var connected = false

        override fun capabilities(): PhoneCapabilities = PhoneCapabilities(observe = true)

        override suspend fun connect(deadlineMs: Long): PhoneConnection {
            val target = checkNotNull(engineHost) { "connect without a configured endpoint" }
            if (!receipts.has(certificateFingerprint, target)) {
                throw PhoneDriverException(PhoneDriverFailure.NOT_CONNECTED)
            }
            connected = true
            connectCalls += 1
            return PhoneConnection(connectionId = "conn-$connectCalls", runtimeId = "phone-control")
        }

        override suspend fun disconnect(deadlineMs: Long) {
            connected = false
        }

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot = error("unused")

        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage =
            error("unused")

        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt =
            error("no request may be replayed")

        override fun configure(host: String, connectPort: Int): AdbDriverEndpoint {
            engineHost = host
            return AdbDriverEndpoint(host, connectPort)
        }

        override fun configurePairing(host: String, pairingPort: Int) {
            engineHost = host
        }

        override fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt {
            val target = checkNotNull(engineHost) { "pair without a configured host" }
            pairCalls += 1
            val receipt = AdbPairingReceipt(certificateFingerprint, target, PAIRED_AT_MS)
            receipts.record(receipt)
            return receipt
        }

        override fun cancelPairing() = Unit

        override fun hasPairingReceipt(host: String): Boolean = receipts.has(certificateFingerprint, host)

        override fun configuredEndpoint(): AdbDriverEndpoint? = null

        override val transportSecurity: AdbTransportSecurity = AdbTransportSecurity.TLS
    }

    /** One facade instance with a fresh engine and store over [backend], like a process start. */
    private class Harness(
        backend: FakeBackend,
        ownerFingerprint: String,
    ) {
        var engine: FakeEngine? = null
        val facade = PhoneDriverFacade(
            identity = { error("identity must stay lazy") },
            runtimeIdentity = { "launch-1" },
            platformSupported = true,
            engineFactory = {
                val store = PhonePairingReceipts.open(backend, ownerFingerprint)
                FakeEngine(ownerFingerprint, store).also { engine = it }
            },
        )

        val created: FakeEngine get() = checkNotNull(engine)
    }

    @Test
    fun `a restart restores the paired certificate and allows the explicit connect`() = runTest {
        val backend = FakeBackend()
        val first = Harness(backend, certificate)
        first.facade.configure(host, 5555)
        assertFalse(first.facade.hasPairingReceipt(host))
        assertTrue(runCatching { first.facade.connect(1_000) }.isFailure)

        val receipt = first.facade.pair(charArrayOf('1', '2', '3', '4', '5', '6'), 1_000)
        assertEquals(certificate, receipt.certificateFingerprint)
        assertEquals(1, backend.writes)
        assertTrue(first.facade.hasPairingReceipt(host))

        // Process restart: a new facade, a new engine and a new store over the same app-private blob.
        val restarted = Harness(backend, certificate)
        assertTrue(restarted.facade.hasPairingReceipt(host))
        assertEquals(1, backend.writes)
        // Restoring a receipt is not a reconnect and not a re-pair.
        assertEquals(0, restarted.created.pairCalls)
        assertEquals(0, restarted.created.connectCalls)

        restarted.facade.configure(host, 5555)
        val connection = restarted.facade.connect(1_000)

        assertEquals("conn-1", connection.connectionId)
        assertEquals(1, restarted.created.connectCalls)
        assertTrue(restarted.created.connected)
    }

    @Test
    fun `a changed certificate cannot use the stored receipt`() = runTest {
        val backend = FakeBackend()
        val first = Harness(backend, certificate)
        first.facade.configure(host, 5555)
        first.facade.pair(charArrayOf('1', '2', '3', '4', '5', '6'), 1_000)

        val changed = Harness(backend, otherCertificate)

        assertFalse(changed.facade.hasPairingReceipt(host))
        changed.facade.configure(host, 5555)
        assertTrue(runCatching { changed.facade.connect(1_000) }.isFailure)
        assertEquals(0, changed.created.connectCalls)
    }

    @Test
    fun `a failed receipt write leaves the host unauthorized`() = runTest {
        val backend = FakeBackend().apply { failWrites = true }
        val harness = Harness(backend, certificate)
        harness.facade.configure(host, 5555)

        assertThrows(AdbPairingReceiptStoreException::class.java) {
            harness.facade.pair(charArrayOf('1', '2', '3', '4', '5', '6'), 1_000)
        }

        assertFalse(harness.facade.hasPairingReceipt(host))
        assertNotNull(harness.engine)
        assertTrue(runCatching { harness.facade.connect(1_000) }.isFailure)
    }

    private companion object {
        const val PAIRED_AT_MS = 42L
    }
}
