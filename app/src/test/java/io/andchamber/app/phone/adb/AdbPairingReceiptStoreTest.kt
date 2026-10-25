package io.andchamber.app.phone.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingReceiptStoreTest {
    private val fingerprint = "a".repeat(64)
    private val otherFingerprint = "b".repeat(64)
    private val host = "127.0.0.1"

    @Test
    fun defaultMemoryStoreRecordsAndRevokesWithoutAndroidIo() {
        val store = AdbPairingReceiptStore()
        val receipt = receipt(pairedAtMs = 10L)
        store.record(receipt)
        assertTrue(store.has(fingerprint, host))
        assertEquals(listOf(receipt), store.snapshot())

        store.revoke(fingerprint, host)
        assertFalse(store.has(fingerprint, host))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun initialRecordsAreValidatedNormalizedDedupedAndDoNotWriteBack() {
        var callbacks = 0
        val later = receipt(pairedAtMs = 20L)
        val store = AdbPairingReceiptStore(
            initial = listOf(
                receipt(pairedAtMs = 10L),
                later.copy(certificateFingerprint = fingerprint.uppercase()),
            ),
            onChanged = { callbacks += 1 },
        )
        assertEquals(1, store.snapshot().size)
        assertEquals(later, store.snapshot().single())
        assertTrue(store.has(fingerprint, host))
        assertEquals(0, callbacks)
    }

    @Test
    fun importedRecordsAreBoundedToEightUniqueKeys() {
        val eight = (0 until AdbPairingReceiptStore.MAX_RECEIPTS).map { index ->
            receipt(host = "192.0.2.${index + 1}")
        }
        val store = AdbPairingReceiptStore(initial = eight)
        assertEquals(AdbPairingReceiptStore.MAX_RECEIPTS, store.snapshot().size)

        assertThrows(AdbPairingReceiptStoreException::class.java) {
            AdbPairingReceiptStore(initial = eight + receipt(host = "198.51.100.24"))
        }

        val duplicated = eight + receipt(host = "192.0.2.1")
        assertEquals(
            AdbPairingReceiptStore.MAX_RECEIPTS,
            AdbPairingReceiptStore(initial = duplicated).snapshot().size,
        )
    }

    @Test
    fun capacityFullRefusesNewKeysButAllowsUpdatingAnExistingKey() {
        var callbacks = 0
        val store = AdbPairingReceiptStore(onChanged = { callbacks += 1 })
        for (index in 0 until AdbPairingReceiptStore.MAX_RECEIPTS) {
            store.record(receipt(host = "192.0.2.${index + 1}", pairedAtMs = index.toLong()))
        }
        assertEquals(AdbPairingReceiptStore.MAX_RECEIPTS, store.snapshot().size)
        assertEquals(AdbPairingReceiptStore.MAX_RECEIPTS, callbacks)

        assertThrows(AdbPairingReceiptStoreException::class.java) {
            store.record(receipt(host = "198.51.100.24"))
        }
        assertEquals(AdbPairingReceiptStore.MAX_RECEIPTS, store.snapshot().size)

        store.record(receipt(host = "192.0.2.1", pairedAtMs = 99L))
        assertEquals(99L, store.snapshot().first { it.host == "192.0.2.1" }.pairedAtMs)
    }

    @Test
    fun invalidImportedShapesFailClosed() {
        val invalid = listOf(
            receipt(certificateFingerprint = "a".repeat(63)),
            receipt(certificateFingerprint = "a".repeat(65)),
            receipt(certificateFingerprint = "z".repeat(64)),
            receipt(host = ""),
            receipt(host = "a".repeat(65)),
            receipt(host = "127.0.0.1 "),
            receipt(host = "127.0.0.1\n"),
            receipt(host = "127.0.0.1|other"),
            receipt(pairedAtMs = -1L),
        )
        invalid.forEach { candidate ->
            assertThrows(AdbPairingReceiptStoreException::class.java) {
                AdbPairingReceiptStore(initial = listOf(candidate))
            }
            assertThrows(AdbPairingReceiptStoreException::class.java) {
                AdbPairingReceiptStore().record(candidate)
            }
        }
        assertFalse(AdbPairingReceiptStore().has("bad", host))
        assertFalse(AdbPairingReceiptStore().has(fingerprint, ""))
    }

    @Test
    fun callbacksSeeABoundedSnapshotAfterRecordRevokeClearAndNoOpDoesNotNotify() {
        val snapshots = mutableListOf<List<AdbPairingReceipt>>()
        val store = AdbPairingReceiptStore(onChanged = { snapshots += it })
        store.record(receipt(pairedAtMs = 10L))
        store.record(receipt(pairedAtMs = 20L))
        store.revoke(fingerprint, host)
        store.revoke(fingerprint, host)
        store.clear()
        store.clear()

        assertEquals(3, snapshots.size)
        assertEquals(1, snapshots[0].size)
        assertEquals(20L, snapshots[1].single().pairedAtMs)
        assertTrue(snapshots[2].isEmpty())
        assertTrue(snapshots.all { it.size <= AdbPairingReceiptStore.MAX_RECEIPTS })
    }

    @Test
    fun persistenceCallbackFailureRollsBackAndThrows() {
        var failing = false
        val store = AdbPairingReceiptStore(
            onChanged = {
                if (failing) error("persistence unavailable")
            },
        )
        failing = true
        assertThrows(AdbPairingReceiptStoreException::class.java) {
            store.record(receipt())
        }
        assertFalse(store.has(fingerprint, host))
        assertTrue(store.snapshot().isEmpty())

        failing = false
        store.record(receipt(pairedAtMs = 10L))
        failing = true
        assertThrows(AdbPairingReceiptStoreException::class.java) {
            store.revoke(fingerprint, host)
        }
        assertTrue(store.has(fingerprint, host))
        assertThrows(AdbPairingReceiptStoreException::class.java) {
            store.clear()
        }
        assertTrue(store.has(fingerprint, host))
    }

    @Test
    fun snapshotIsABoundedCopyOfTheStore() {
        val store = AdbPairingReceiptStore()
        store.record(receipt())
        val first = store.snapshot()
        store.record(receipt(host = "192.0.2.2"))
        assertEquals(1, first.size)
        assertEquals(2, store.snapshot().size)
        assertTrue(first !== store.snapshot())
    }

    @Test
    fun receiptShapeHasNoCodeOrPrivateKeyAndRestoresOnlyCertPlusHost() {
        val fields = AdbPairingReceipt::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(
            setOf("certificateFingerprint", "host", "pairedAtMs"),
            fields,
        )
        assertTrue(fields.none { it.contains("code", ignoreCase = true) })
        assertTrue(fields.none { it.contains("private", ignoreCase = true) })

        val store = AdbPairingReceiptStore(initial = listOf(receipt()))
        assertTrue(store.has(fingerprint, host))
        assertFalse(store.has(otherFingerprint, host))
        assertFalse(store.has(fingerprint, "192.0.2.99"))
        // The store has no connection/lease/action surface; restoring a receipt cannot restore one.
        val methods = AdbPairingReceiptStore::class.java.declaredMethods.map { it.name }
        assertTrue(methods.none { it.contains("connect", ignoreCase = true) })
        assertTrue(methods.none { it.contains("lease", ignoreCase = true) })
        assertTrue(methods.none { it.contains("dispatch", ignoreCase = true) })
    }

    private fun receipt(
        certificateFingerprint: String = fingerprint,
        host: String = this.host,
        pairedAtMs: Long = 1L,
    ) = AdbPairingReceipt(
        certificateFingerprint = certificateFingerprint,
        host = host,
        pairedAtMs = pairedAtMs,
    )
}
