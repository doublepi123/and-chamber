// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import android.os.Build
import android.os.SystemClock
import io.github.muntashirakon.adb.AdbPairingException
import io.github.muntashirakon.adb.AdbPairingFailure
import io.github.muntashirakon.adb.AndChamberPairingTransport

/**
 * Lets a UI cancel a pairing exchange without pretending that a coroutine timeout stopped it.
 * [cancel] closes the transport socket; a code entered after cancellation is refused.
 */
class AdbPairingCancellation {
    @Volatile
    private var cancelled = false

    @Volatile
    private var transport: AndChamberPairingTransport? = null

    fun cancel() {
        cancelled = true
        transport?.cancel()
    }

    internal fun attach(value: AndChamberPairingTransport) {
        transport = value
        if (cancelled) value.cancel()
    }

    internal fun detach() {
        transport = null
    }
}

/**
 * The single application entry point for ADB pairing. The P0 probe and the production driver both
 * call this function, so a probe success is evidence about the production pairing path, not about a
 * separately written test client.
 *
 * The pairing code is only held in memory for one bounded exchange and is never stored in the
 * receipt, logged, or copied into an exception.
 */
object AdbPhonePairing {
    const val MAX_DEADLINE_MS: Long = 60_000L

    fun pair(
        identity: AdbIdentity,
        host: String,
        port: Int,
        pairingCode: CharArray,
        deadlineMs: Long,
        receipts: AdbPairingReceiptStore,
        hostPolicy: AdbHostPolicy = AdbHostPolicy(),
        clock: () -> Long = { SystemClock.elapsedRealtime() },
        cancellation: AdbPairingCancellation? = null,
    ): AdbPairingReceipt {
        if (!identity.isConsistent()) throw AdbPairingException(AdbPairingFailure.INVALID_INPUT)
        if (port !in 1..65535 || pairingCode.size != CODE_LENGTH) {
            throw AdbPairingException(AdbPairingFailure.INVALID_INPUT)
        }
        if (hostPolicy.refusal(host) != null) {
            throw AdbPairingException(AdbPairingFailure.POLICY)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // The pairing exchange needs TLS 1.3 exported keying material; API 29 is the first
            // platform version that provides it without a custom Conscrypt provider.
            throw AdbPairingException(AdbPairingFailure.TLS_UNAVAILABLE)
        }

        val code = pairingCode.copyOf()
        try {
            if (code.any { it !in '0'..'9' }) {
                throw AdbPairingException(AdbPairingFailure.INVALID_INPUT)
            }
            val bounded = deadlineMs.coerceIn(1L, MAX_DEADLINE_MS)
            val transport = AndChamberPairingTransport(
                host = host,
                port = port,
                pairingCode = code,
                privateKey = identity.privateKey,
                certificate = identity.certificate,
                deviceName = identity.deviceName,
            )
            cancellation?.attach(transport)
            try {
                transport.pair(bounded)
            } finally {
                cancellation?.detach()
            }
        } finally {
            code.fill('\u0000')
        }

        val receipt = AdbPairingReceipt(
            certificateFingerprint = identity.certificateFingerprint,
            host = host,
            pairedAtMs = clock(),
        )
        receipts.record(identity, host, receipt.pairedAtMs)
        return receipt
    }

    private const val CODE_LENGTH = 6
}
