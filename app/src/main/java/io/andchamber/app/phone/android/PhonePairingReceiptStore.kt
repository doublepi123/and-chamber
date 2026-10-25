package io.andchamber.app.phone.android

import android.content.Context
import android.content.SharedPreferences
import io.andchamber.app.phone.adb.AdbHostPolicy
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbPairingReceiptStore
import io.andchamber.app.phone.adb.AdbPairingReceiptStoreException

/**
 * The smallest persistence boundary the pairing-receipt store needs: one opaque, versioned blob.
 *
 * Keeping it an interface lets the codec and failure handling be tested on the JVM without
 * SharedPreferences, and keeps every Android read/write out of [AdbPairingReceiptStore] itself.
 */
internal interface PairingReceiptBackend {
    /** The last written blob, or null when nothing was stored. The codec rejects malformed text. */
    fun read(): String?

    /** Writes the whole bounded snapshot; throws when the write is not confirmed. */
    fun write(encoded: String)
}

/**
 * App-private SharedPreferences backend for the pairing receipts.
 *
 * Only the blob produced by [PhonePairingReceiptCodec] is stored: certificate fingerprint, host,
 * pairing time and record version. No pairing code, private key, grant, lease, observation
 * reference, screenshot, session, target package or connection state is ever written here.
 *
 * [write] replaces the one preferences key in a single `commit()`, so a changed snapshot is stored
 * atomically and never as a partial list; an empty snapshot is stored as an empty blob. The callback
 * runs under the driver store's lock, so this method must not call back into the store. A failed or
 * unconfirmed commit throws instead of being reported as persisted; the store then rolls its memory
 * back. Call this from the IO thread of the first driver use, never from the main thread.
 */
internal class SharedPreferencesPairingReceiptBackend(
    private val preferences: SharedPreferences,
) : PairingReceiptBackend {
    override fun read(): String? = preferences.getString(KEY_RECEIPTS, null)

    override fun write(encoded: String) {
        if (!preferences.edit().putString(KEY_RECEIPTS, encoded).commit()) {
            throw AdbPairingReceiptStoreException("pairing receipt persistence failed")
        }
    }

    companion object {
        const val FILE_NAME: String = "phone_pairing_receipts"
        const val KEY_RECEIPTS: String = "receipts"
    }
}

/**
 * Versioned text codec for the restored pairing receipts.
 *
 * One record per line: `v1|<certificateFingerprint>|<host>|<pairedAtMs>`. A blob is accepted only
 * when every line is a well-formed `v1` record, the host passes the local host policy, and there
 * are at most [AdbPairingReceiptStore.MAX_RECEIPTS] records for the current certificate. Unknown
 * versions, damaged lines, invalid hosts or timestamps and oversized blobs reject the whole blob:
 * the app then behaves as if it had never paired instead of inventing a receipt for the current
 * identity. Records that belong to another certificate fingerprint are ignored, so a replaced
 * AndroidKeyStore key simply has no receipt until the person pairs again.
 */
internal object PhonePairingReceiptCodec {
    private const val VERSION = "v1"
    private const val SEPARATOR = "|"
    /** v1, certificate fingerprint, host, pairing timestamp. */
    private const val FIELD_COUNT = 4
    private const val MAX_HOST_CHARS = 64
    private const val MAX_BLOB_CHARS = 2_048

    fun encode(receipts: List<AdbPairingReceipt>): String {
        check(receipts.size <= AdbPairingReceiptStore.MAX_RECEIPTS) { "too many pairing receipts" }
        return receipts.joinToString("\n") { receipt ->
            listOf(VERSION, receipt.certificateFingerprint, receipt.host, receipt.pairedAtMs.toString())
                .joinToString(SEPARATOR)
        }
    }

    fun decode(
        encoded: String?,
        certificateFingerprint: String,
        hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    ): List<AdbPairingReceipt> {
        if (encoded.isNullOrEmpty() || encoded.length > MAX_BLOB_CHARS) return emptyList()
        val wanted = certificateFingerprint.lowercase()
        if (!FINGERPRINT.matches(wanted)) return emptyList()

        val restored = LinkedHashMap<String, AdbPairingReceipt>()
        for (line in encoded.split('\n')) {
            val fields = line.split(SEPARATOR)
            if (fields.size != FIELD_COUNT || fields[0] != VERSION) return emptyList()
            val fingerprint = fields[1].lowercase()
            val host = fields[2]
            val pairedAtMs = fields[3].toLongOrNull() ?: return emptyList()
            if (!FINGERPRINT.matches(fingerprint)) return emptyList()
            if (pairedAtMs < 0L) return emptyList()
            if (host.length !in 1..MAX_HOST_CHARS ||
                host.any { it.isWhitespace() || it.isISOControl() }
            ) {
                return emptyList()
            }
            // Only a local-policy host could ever have produced a receipt; anything else is not ours.
            if (hostPolicy.refusal(host) != null) return emptyList()
            if (fingerprint != wanted) continue
            restored[fingerprint + SEPARATOR + host] = AdbPairingReceipt(fingerprint, host, pairedAtMs)
            if (restored.size > AdbPairingReceiptStore.MAX_RECEIPTS) return emptyList()
        }
        return restored.values.toList()
    }

    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
}

/**
 * Loads the app-private pairing receipts for one certificate and keeps them persisted.
 *
 * A restored receipt only records that this App once completed a pairing exchange for this
 * certificate and host. It is not device authorization, does not prove that the device's USB
 * debugging authorization still exists, and every connect still performs the real TLS/ADB
 * handshake. The store never restores a lease, connection, observation or action.
 */
internal object PhonePairingReceipts {
    /**
     * Builds a store over [backend]. Must run off the main thread when the backend does Android I/O
     * (the facade calls its receipt factory inside the lazy engine factory on first driver use).
     */
    fun open(
        backend: PairingReceiptBackend,
        certificateFingerprint: String,
        hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    ): AdbPairingReceiptStore {
        val restored = PhonePairingReceiptCodec.decode(backend.read(), certificateFingerprint, hostPolicy)
        return AdbPairingReceiptStore(
            initial = restored,
            onChanged = { receipts -> backend.write(PhonePairingReceiptCodec.encode(receipts)) },
        )
    }

    /** App-private variant. The SharedPreferences file is read on the caller's (IO) thread. */
    fun open(context: Context, certificateFingerprint: String): AdbPairingReceiptStore =
        open(
            backend = SharedPreferencesPairingReceiptBackend(
                context.getSharedPreferences(
                    SharedPreferencesPairingReceiptBackend.FILE_NAME,
                    Context.MODE_PRIVATE,
                ),
            ),
            certificateFingerprint = certificateFingerprint,
        )
}
