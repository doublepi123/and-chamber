package io.andchamber.app.phone.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.andchamber.app.MainActivity

/**
 * The notification's "Return to AndChamber" action.
 *
 * Android 12+ blocks a notification trampoline that starts an Activity from a service, so the Return
 * action is a direct Activity PendingIntent. This activity is invisible, not exported and never kept
 * in history: it validates the intent (this process's nonce and the live lease epoch), revokes that
 * exact lease, stops the foreground stop entry, and only then opens the real screen from an Activity
 * context. It never grants, never reconnects, and finishes in `onCreate` on every path.
 *
 * A stale action (no live lease, or another epoch) finishes without touching state or opening AndChamber.
 */
class PhoneControlReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = PhoneIntegrationHolder.current()
        val intent = intent
        val epoch = intent?.getLongExtra(PhoneControlService.EXTRA_EPOCH, NO_EPOCH) ?: NO_EPOCH
        val nonce = intent?.getStringExtra(PhoneControlService.EXTRA_NONCE) ?: phoneIntentNonce(intent?.dataString)
        val accepted =
            phone != null &&
                returnActionAccepted(
                    nonceMatches = PhoneProcessNonce.matches(nonce),
                    actionEpoch = epoch,
                    liveEpoch = phone.liveLeaseEpoch(),
                )
        if (!accepted) {
            // No live lease to stop: this action belongs to an older grant or process. No side effects.
            finish()
            return
        }
        // Revoke exactly this epoch, stop the foreground stop entry, then open AndChamber from here:
        // an Activity context, not a service trampoline. The controller's revoke has already dropped
        // the grant before the screen resumes, so MainActivity's onResume finds nothing to revoke.
        if (phone?.onNotificationReturn(epoch) != true) {
            finish()
            return
        }
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(PhoneControlService.ACTION_OPEN_PHONE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    private companion object {
        const val NO_EPOCH = -1L
    }
}
