package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbHostPolicy
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbPairingReceiptStore
import io.andchamber.app.phone.adb.AdbPairingReceiptStoreException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing receipt persists across a process restart only as "this App completed a pairing
 * exchange for this certificate and host". These tests pin the blob shape (nothing beyond version,
 * fingerprint, host, pairing time), the certificate filter, and fail-closed handling of damaged
 * data and failed writes. They use no Android runtime: the backend is a plain in-memory blob.
 */
class PhonePairingReceiptStoreTest {
    private val certificate = "0123456789abcdef".repeat(4)
    private val otherCertificate = "fedcba9876543210".repeat(4)
    private val host = "127.0.0.1"

    private class FakeBackend(
        var blob: String? = null,
    ) : PairingReceiptBackend {
        var writes = 0
        var attempts = 0
        var failWrites = false

        override fun read(): String? = blob

        override fun write(encoded: String) {
            attempts += 1
            if (failWrites) throw IOException("no space left")
            writes += 1
            blob = encoded
        }
    }

    @Test
    fun `a new store restores the same certificate receipt and stores only the four fields`() {
        val backend = FakeBackend()
        val first = PhonePairingReceipts.open(backend, certificate)
        first.record(AdbPairingReceipt(certificate, host, 1_700_000_000_000L))

        // No pairing code, key, grant, lease, session, target or connection state can be stored.
        assertEquals("v1|$certificate|$host|1700000000000", backend.blob)
        assertEquals(1, backend.writes)

        val restarted = PhonePairingReceipts.open(backend, certificate)
        assertEquals(
            listOf(AdbPairingReceipt(certificate, host, 1_700_000_000_000L)),
            restarted.snapshot(),
        )
        assertTrue(restarted.has(certificate, host))
        // Loading never writes: the stored history is untouched by a restart.
        assertEquals(1, backend.writes)
    }

    @Test
    fun `a receipt for another certificate is never applied`() {
        val backend = FakeBackend()
        PhonePairingReceipts.open(backend, certificate).record(AdbPairingReceipt(certificate, host, 1L))

        val changed = PhonePairingReceipts.open(backend, otherCertificate)

        assertTrue(changed.snapshot().isEmpty())
        assertFalse(changed.has(otherCertificate, host))
        assertFalse(changed.has(certificate, host))
    }

    @Test
    fun `a damaged unknown or oversized record rejects the blob instead of inventing a pairing`() {
        val damaged = listOf(
            "v2|$certificate|$host|1",
            "v1|$certificate|$host",
            "v1|$certificate|$host|later",
            "v1|$certificate|$host with space|1",
            "v1|$certificate|$host|1|extra",
            "v1|not-a-fingerprint|$host|1",
            "v1|$certificate|$host|-1",
            "garbage",
        )
        for (blob in damaged) {
            val restored = PhonePairingReceipts.open(FakeBackend(blob), certificate)
            assertTrue("accepted: $blob", restored.snapshot().isEmpty())
        }

        val tooMany = (1..AdbPairingReceiptStore.MAX_RECEIPTS + 1).joinToString("\n") {
            "v1|$certificate|127.0.0.$it|$it"
        }
        assertTrue(PhonePairingReceipts.open(FakeBackend(tooMany), certificate).snapshot().isEmpty())
        val oversizedDuplicates = List(100) { "v1|$certificate|$host|1" }.joinToString("\n")
        assertTrue(PhonePairingReceipts.open(FakeBackend(oversizedDuplicates), certificate).snapshot().isEmpty())
    }

    @Test
    fun `a host outside the local policy is not restored`() {
        // 192.0.2.5 is a documentation address, never this device's own interface.
        val backend = FakeBackend("v1|$certificate|192.0.2.5|1")
        val policy = AdbHostPolicy(localAddresses = { emptyList() })

        assertTrue(PhonePairingReceipts.open(backend, certificate, policy).snapshot().isEmpty())
    }

    @Test
    fun `duplicate certificate and host records collapse to the latest`() {
        val backend = FakeBackend(
            "v1|$certificate|$host|1\nv1|$certificate|$host|2",
        )

        val restored = PhonePairingReceipts.open(backend, certificate).snapshot()

        assertEquals(listOf(AdbPairingReceipt(certificate, host, 2L)), restored)
    }

    @Test
    fun `a revoke persists only the remaining receipts`() {
        val backend = FakeBackend()
        val store = PhonePairingReceipts.open(backend, certificate)
        store.record(AdbPairingReceipt(certificate, "127.0.0.1", 1L))
        store.record(AdbPairingReceipt(certificate, "127.0.0.2", 2L))

        store.revoke(certificate, "127.0.0.1")

        assertEquals("v1|$certificate|127.0.0.2|2", backend.blob)
        assertFalse(store.has(certificate, "127.0.0.1"))
        assertEquals(
            listOf("127.0.0.2"),
            PhonePairingReceipts.open(backend, certificate).snapshot().map { it.host },
        )
    }

    @Test
    fun `clearing persists an empty snapshot`() {
        val backend = FakeBackend()
        val store = PhonePairingReceipts.open(backend, certificate)
        store.record(AdbPairingReceipt(certificate, host, 1L))

        store.clear()

        assertEquals("", backend.blob)
        assertTrue(store.snapshot().isEmpty())
        assertTrue(PhonePairingReceipts.open(backend, certificate).snapshot().isEmpty())
    }

    @Test
    fun `a failed write is not reported as a persisted receipt`() {
        val backend = FakeBackend().apply { failWrites = true }
        val store = PhonePairingReceipts.open(backend, certificate)

        assertThrows(AdbPairingReceiptStoreException::class.java) {
            store.record(AdbPairingReceipt(certificate, host, 1L))
        }

        assertFalse(store.has(certificate, host))
        assertNull(backend.blob)
        assertEquals(1, backend.attempts)
    }
}
