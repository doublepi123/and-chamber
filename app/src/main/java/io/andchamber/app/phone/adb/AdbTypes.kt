// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneLimits
import java.io.IOException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * The ADB identity the caller provides. It is a normal app-owned RSA key and certificate, not a
 * hardware-backed attestation and not a claim that the device recognizes this app as an authorized
 * ADB host.
 *
 * There is intentionally no key generation here: the caller owns key creation and private storage.
 * The key and certificate must stay out of logs, state, errors and the repository.
 */
class AdbIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val deviceName: String,
) {
    init {
        require(privateKey.algorithm.equals("RSA", ignoreCase = true)) { "RSA key required" }
        require(certificate.publicKey is RSAPublicKey) { "RSA certificate required" }
        require(deviceName.isNotBlank() && deviceName.length <= MAX_DEVICE_NAME_CHARS) {
            "device name"
        }
        require(deviceName.none { it.isISOControl() }) { "device name" }
    }

    /** SHA-256 of the DER certificate. Stable identity for the pairing receipt, safe to store. */
    val certificateFingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** True when [privateKey] can sign a challenge that [certificate]'s public key verifies. */
    fun isConsistent(): Boolean = consistency

    private val consistency: Boolean by lazy {
        runCatching {
            val payload = byteArrayOf(0x41, 0x44, 0x42)
            val signer = java.security.Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(payload)
            val signature = signer.sign()
            val verifier = java.security.Signature.getInstance("SHA256withRSA")
            verifier.initVerify(certificate.publicKey)
            verifier.update(payload)
            verifier.verify(signature)
        }.getOrDefault(false)
    }

    private companion object {
        const val MAX_DEVICE_NAME_CHARS = 64
    }
}

/**
 * A local record that this app completed the pairing exchange for this certificate and host.
 * A connection is not a pairing receipt: on a `ro.adb.secure=0` image any fresh key can connect
 * even though the device never paired it. Production may persist the record; this first version
 * keeps it in memory and never stores the pairing code.
 */
data class AdbPairingReceipt(
    val certificateFingerprint: String,
    val host: String,
    val pairedAtMs: Long,
)

/** Validation, capacity, or persistence-callback failure. The receipt was not retained. */
class AdbPairingReceiptStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Pure, bounded local pairing-receipt store.
 *
 * A receipt only means "this App completed a pairing exchange for this certificate and host". It is
 * never device authorization: every connect still performs the real TLS/ADB identity handshake, and
 * a restored receipt does not restore a lease, connection, observation or action.
 *
 * Persistence is injected as [onChanged]; this class performs no Android I/O. The callback runs under
 * the store lock, receives a bounded read-only snapshot after each changed mutation, and must be a
 * short, atomic write (do not re-enter record/revoke/clear). If it throws, the in-memory mutation is
 * rolled back and [AdbPairingReceiptStoreException] is thrown: the store never claims a persistence
 * success it did not observe. Construction does not invoke the callback, and no-op revoke/clear do
 * not either.
 *
 * Imported records are validated here for shape only: 64 hex certificate fingerprint, host 1..64
 * characters without whitespace/control/'|', non-negative timestamp. The local host policy (numeric,
 * loopback/own address, pairing receipt target) remains the caller's responsibility.
 */
class AdbPairingReceiptStore(
    initial: Collection<AdbPairingReceipt> = emptyList(),
    private val onChanged: (List<AdbPairingReceipt>) -> Unit = {},
) {
    private data class ReceiptKey(
        val certificateFingerprint: String,
        val host: String,
    )

    private val lock = Any()
    private var receipts: LinkedHashMap<ReceiptKey, AdbPairingReceipt>

    init {
        val loaded = LinkedHashMap<ReceiptKey, AdbPairingReceipt>()
        for (receipt in initial) {
            val valid = validate(receipt)
            // Duplicate certificate+host records are idempotent; the last one wins.
            loaded[ReceiptKey(valid.certificateFingerprint, valid.host)] = valid
        }
        if (loaded.size > MAX_RECEIPTS) {
            throw AdbPairingReceiptStoreException("too many pairing receipts")
        }
        receipts = loaded
    }

    /** Bounded copy of the current receipts, at most [MAX_RECEIPTS]. */
    fun snapshot(): List<AdbPairingReceipt> = synchronized(lock) { receipts.values.toList() }

    fun has(identity: AdbIdentity, host: String): Boolean =
        has(identity.certificateFingerprint, host)

    fun has(certificateFingerprint: String, host: String): Boolean {
        val key = runCatching { keyOf(certificateFingerprint, host) }.getOrNull() ?: return false
        return synchronized(lock) { receipts.containsKey(key) }
    }

    fun record(identity: AdbIdentity, host: String, pairedAtMs: Long) {
        record(AdbPairingReceipt(identity.certificateFingerprint, host, pairedAtMs))
    }

    /** Records one receipt; throws instead of evicting when [MAX_RECEIPTS] is reached. */
    fun record(receipt: AdbPairingReceipt) {
        val valid = validate(receipt)
        val key = ReceiptKey(valid.certificateFingerprint, valid.host)
        synchronized(lock) {
            val next = LinkedHashMap(receipts)
            if (key !in next && next.size >= MAX_RECEIPTS) {
                throw AdbPairingReceiptStoreException("pairing receipt capacity reached")
            }
            next[key] = valid
            commitLocked(next)
        }
    }

    fun revoke(identity: AdbIdentity, host: String) {
        revoke(identity.certificateFingerprint, host)
    }

    fun revoke(certificateFingerprint: String, host: String) {
        val key = keyOf(certificateFingerprint, host)
        synchronized(lock) {
            if (key !in receipts) return
            val next = LinkedHashMap(receipts)
            next.remove(key)
            commitLocked(next)
        }
    }

    fun clear() {
        synchronized(lock) {
            if (receipts.isEmpty()) return
            commitLocked(LinkedHashMap())
        }
    }

    private fun commitLocked(next: LinkedHashMap<ReceiptKey, AdbPairingReceipt>) {
        val previous = receipts
        receipts = next
        try {
            onChanged(next.values.toList())
        } catch (failure: Throwable) {
            receipts = previous
            throw AdbPairingReceiptStoreException(
                "pairing receipt persistence callback failed",
                failure,
            )
        }
    }

    private fun keyOf(certificateFingerprint: String, host: String): ReceiptKey {
        val valid = validate(AdbPairingReceipt(certificateFingerprint, host, 0L))
        return ReceiptKey(valid.certificateFingerprint, valid.host)
    }

    private fun validate(receipt: AdbPairingReceipt): AdbPairingReceipt {
        if (!FINGERPRINT.matches(receipt.certificateFingerprint)) {
            throw AdbPairingReceiptStoreException("invalid certificate fingerprint")
        }
        val host = receipt.host
        if (host.length !in 1..MAX_HOST_CHARS ||
            host.any { it.isWhitespace() || it.isISOControl() || it == '|' }
        ) {
            throw AdbPairingReceiptStoreException("invalid pairing host")
        }
        if (receipt.pairedAtMs < 0L) {
            throw AdbPairingReceiptStoreException("invalid pairing timestamp")
        }
        return receipt.copy(certificateFingerprint = receipt.certificateFingerprint.lowercase())
    }

    companion object {
        const val MAX_RECEIPTS: Int = 8
        private const val MAX_HOST_CHARS: Int = 64
        private val FINGERPRINT = Regex("^[0-9a-fA-F]{64}$")
    }
}

/** Fixed transport security state. Refusing plaintext is part of the driver contract. */
enum class AdbTransportSecurity {
    PLAINTEXT,
    TLS,
}

/** Fixed device failures. No failure carries a host, port, serial, command or page text. */
enum class AdbDeviceFailure {
    NOT_CONNECTED,
    UNREACHABLE,
    DEADLINE,
    CANCELLED,
    POLICY,
    PAIRING_REQUIRED,
    TLS_REQUIRED,
    TLS_UNAVAILABLE,
    AUTH_FAILED,
    LIMIT,
    /** A stream was opened but the transport died before normal CLSE completion. */
    OUTCOME_UNKNOWN,
    /**
     * The hierarchy service returned the fixed AOSP `ERROR: could not get idle state.` text instead
     * of XML. The page was not observed; this is not a connection-loss claim.
     */
    HIERARCHY_NOT_IDLE,
    /**
     * The read-only ADB service transaction hit its watchdog: the exact connection that served it
     * has been abandoned and is no longer usable for control. This covers an open that expired
     * before any byte was read as well as a read that expired mid-stream.
     */
    READ_TIMEOUT,
    DRIVER,
}

class AdbDeviceException(
    val failure: AdbDeviceFailure,
    /** The fixed command may have reached the device before this failure. */
    val afterWrite: Boolean = false,
    cause: Throwable? = null,
) : IOException("ADB device failed (${failure.name})", cause)

/**
 * The capability report the UI/tool layer can show. [phone] keeps the core contract; the extra
 * fields state the first version's honest text-input limits instead of pretending full Unicode.
 */
data class AdbCapabilityReport(
    val phone: PhoneCapabilities = PhoneCapabilities(),
    val inputAsciiOnly: Boolean = true,
    val unicodeInputSupported: Boolean = false,
    val submitSupported: Boolean = false,
    val inputMaxChars: Int = AdbCommandSchema.MAX_INPUT_CHARS,
    val observeMaxNodes: Int = PhoneLimits.MAX_NODES,
    val captureMaxBytes: Int = PhoneLimits.MAX_CAPTURE_BYTES,
    val captureMimeType: String = "image/png",
    val windowIdKind: String = "window-identity(focus+app-token)+content-witness",
)
