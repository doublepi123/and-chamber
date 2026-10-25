package io.andchamber.app.phone.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Receives the two RemoteInput replies of the pairing notification and the "open the system screen"
 * tap. It is a broadcast receiver on purpose: answering must not bring an Activity forward, so the
 * system's own wireless-debugging and pairing dialogs can stay on top while the person types.
 *
 * The six-digit code exists only as a `CharArray` inside the coordinator call and is cleared there.
 * It is never logged, stored, put in an Intent extra, or kept after the bounded exchange.
 *
 * Declared `exported=false` with an explicit component in the PendingIntent, so only our own
 * notification can deliver here.
 */
class PhonePairingActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Only a reply this process minted may act: a PendingIntent from an earlier process, or a
        // forged/forwarded intent, has a different nonce and is ignored outright.
        val nonce = intent.getStringExtra(EXTRA_NONCE) ?: phoneIntentNonce(intent.dataString)
        if (!PhoneProcessNonce.matches(nonce)) return
        val integration = PhoneIntegrationHolder.current() ?: return
        when (intent.action) {
            ACTION_PAIR_PORT -> {
                val generation = intent.getLongExtra(EXTRA_GENERATION, -1L)
                val text = remoteInput(intent, KEY_PORT) ?: return
                integration.onPairingPortReply(generation, text)
            }
            ACTION_PAIR_CODE -> {
                val generation = intent.getLongExtra(EXTRA_GENERATION, -1L)
                val text = remoteInput(intent, KEY_CODE) ?: return
                integration.onPairingCodeReply(generation, text)
            }
            ACTION_CANCEL -> integration.onPairingCancelRequested()
            ACTION_OPEN_SETTINGS -> integration.openWirelessDebuggingSettings()
        }
    }

    private fun remoteInput(
        intent: Intent,
        key: String,
    ): String? =
        runCatching {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(key)
                ?.toString()
        }.getOrNull()

    companion object {
        const val ACTION_PAIR_PORT = "io.andchamber.app.phone.PAIR_PORT"
        const val ACTION_PAIR_CODE = "io.andchamber.app.phone.PAIR_CODE"
        const val ACTION_CANCEL = "io.andchamber.app.phone.PAIR_CANCEL"
        const val ACTION_OPEN_SETTINGS = "io.andchamber.app.phone.OPEN_WIRELESS_SETTINGS"

        const val EXTRA_GENERATION = "generation"
        const val EXTRA_STEP = "step"
        const val EXTRA_NONCE = "intent_nonce"

        /** RemoteInput keys. The code key's value is never persisted by this app. */
        const val KEY_PORT = "pairing_port"
        const val KEY_CODE = "pairing_code"
    }
}

/**
 * Process-wide handle for components the system starts on its own (notification replies and the
 * foreground service). Set once by the Koin graph; cleared by tests. It is not a grant and holds no
 * authority: every call it forwards goes through the coordinator and the controller.
 */
internal object PhoneIntegrationHolder {
    @Volatile
    private var integration: PhoneIntegration? = null

    fun install(value: PhoneIntegration) {
        integration = value
    }

    fun current(): PhoneIntegration? = integration

    internal fun clearForTests() {
        integration = null
    }
}
