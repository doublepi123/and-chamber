package io.andchamber.app.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.andchamber.app.R
import io.andchamber.app.data.LocalApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles notification buttons and the reply field. Network work runs on [goAsync] so the broadcast
 * stays alive: each action posts its request, updates the notification with the outcome, and a short
 * "Sent" status clears itself. Failures keep the reply text in the notification so nothing is lost.
 */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    private val api: LocalApiClient by inject()
    private val notifier: OpenChamberNotifier by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val result = api.send(NotificationActions.sendReply(sessionId, text), directory)
                if (result.ok) {
                    notifier.updateStatus(notificationId, Channel.AgentFinished, context.getString(R.string.reply_sent))
                    delay(CLEAR_AFTER_MS)
                    notifier.cancel(notificationId)
                } else {
                    // Keep the notification and the reply text visible; the user can try again.
                    notifier.updateStatus(
                        notificationId,
                        Channel.Errors,
                        context.getString(R.string.reply_failed, result.body.take(MAX_REASON)),
                    )
                }
            }
            ACTION_FORM -> {
                val formId = intent.getStringExtra(EXTRA_FORM_ID) ?: return
                val fieldKey = intent.getStringExtra(EXTRA_FIELD_KEY) ?: return
                // A tapped choice carries its value; a text reply carries what the user typed.
                val value =
                    intent.getStringExtra(EXTRA_VALUE)
                        ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (value.isNullOrEmpty()) return
                // The form must still be pending and still answerable from here; otherwise cancel silently.
                val pending = api.pending()
                val stillAnswerable =
                    pending != null &&
                        PendingForms.classify(pending, sessionId, formId).let { it is FormAnswer.Choice || it is FormAnswer.Text }
                if (!stillAnswerable) {
                    notifier.cancel(notificationId)
                    return
                }
                val result = api.send(NotificationActions.formReply(sessionId, formId, fieldKey, value), directory)
                if (result.ok) {
                    notifier.cancel(notificationId)
                } else {
                    notifier.updateStatus(notificationId, Channel.Errors, context.getString(R.string.reply_failed, result.body.take(MAX_REASON)))
                }
            }
            ACTION_PERMISSION -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
                val decision = PermissionDecision.entries.firstOrNull { it.wire == intent.getStringExtra(EXTRA_DECISION) } ?: return
                // Confirm the request is still pending: a request answered elsewhere is cancelled silently.
                val pending = api.pending()
                if (pending == null || PendingPermission.find(pending, sessionId, requestId) == null) {
                    notifier.cancel(notificationId)
                    return
                }
                val result = api.send(NotificationActions.permissionReply(sessionId, requestId, decision), directory)
                if (result.ok) {
                    notifier.cancel(notificationId)
                } else {
                    notifier.updateStatus(notificationId, Channel.Errors, context.getString(R.string.reply_failed, result.body.take(MAX_REASON)))
                }
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "io.andchamber.app.action.NOTIFY_REPLY"
        const val ACTION_PERMISSION = "io.andchamber.app.action.NOTIFY_PERMISSION"
        const val ACTION_FORM = "io.andchamber.app.action.NOTIFY_FORM"
        const val EXTRA_FORM_ID = "form_id"
        const val EXTRA_FIELD_KEY = "field_key"
        const val EXTRA_VALUE = "value"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_DIRECTORY = "directory"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val KEY_REPLY = "reply_text"
        private const val CLEAR_AFTER_MS = 3_000L
        private const val MAX_REASON = 120
    }
}
