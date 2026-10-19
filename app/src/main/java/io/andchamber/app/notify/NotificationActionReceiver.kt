package io.andchamber.app.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import io.andchamber.app.R
import io.andchamber.app.data.ApiResult
import io.andchamber.app.data.LocalApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles notification buttons and the reply field. Network work runs on [goAsync] so the broadcast
 * stays alive. A sent reply shows "Sent" briefly; an answered button removes the notification. Anything
 * that didn't go through keeps the notification (session tap, buttons, reply text) with a plain status,
 * and is never sent again by itself (see [ActionDecisions]).
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
        // One action at a time per notification: a second tap waits, then reads the state the first left.
        val sentToken = lockFor(notificationId).withLock { act(context, intent, sessionId, directory, notificationId) }
        if (sentToken != null) {
            // "Sent" stays a moment, outside the lock. Only this exact "Sent" revision is cleared: a newer
            // event or a kept draft on the same id has a different revision and is left alone. The pending
            // record is released on every exit, so an interrupted wait cannot leave it behind.
            var cleaned = false
            try {
                delay(CLEAR_AFTER_MS)
                notifier.cancelIfCurrent(notificationId, sentToken)
                cleaned = true
            } finally {
                if (!cleaned) notifier.forgetSent(notificationId, sentToken)
            }
        }
    }

    /** Runs one action. The revision token of the "Sent" status when a reply was sent, otherwise null. */
    private suspend fun act(
        context: Context,
        intent: Intent,
        sessionId: String,
        directory: String?,
        notificationId: Int,
    ): String? {
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return null
                val result = api.send(NotificationActions.sendReply(sessionId, text), directory)
                val outcome = ActionDecisions.outcomeOf(result.status)
                if (outcome == SendOutcome.Sent) {
                    return notifier.updateStatus(notificationId, Channel.AgentFinished, context.getString(R.string.reply_sent))
                }
                // Never sent again by itself: the notification keeps the session, the reply field and the text.
                settle(context, notificationId, ActionDecisions.afterReply(outcome), FailureContext(sessionId, directory, text), result.body)
            }
            ACTION_FORM -> {
                val formId = intent.getStringExtra(EXTRA_FORM_ID) ?: return null
                val fieldKey = intent.getStringExtra(EXTRA_FIELD_KEY) ?: return null
                // A tapped choice carries its value; a text reply carries what the user typed.
                val value =
                    intent.getStringExtra(EXTRA_VALUE)
                        ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                if (value.isNullOrEmpty()) return null
                // A typed answer is kept as a draft; a tapped choice is still on its button.
                val typed = value.takeIf { intent.getStringExtra(EXTRA_VALUE) == null }
                answer(context, notificationId, FailureContext(sessionId, directory, typed), { ActionDecisions.checkForm(it, sessionId, formId) }) {
                    api.send(NotificationActions.formReply(sessionId, formId, fieldKey, value), directory)
                }
            }
            ACTION_PERMISSION -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return null
                val decision = PermissionDecision.entries.firstOrNull { it.wire == intent.getStringExtra(EXTRA_DECISION) } ?: return null
                answer(context, notificationId, FailureContext(sessionId, directory, null), { ActionDecisions.checkPermission(it, sessionId, requestId) }) {
                    api.send(NotificationActions.permissionReply(sessionId, requestId, decision), directory)
                }
            }
        }
        return null
    }

    /**
     * Answers a pending request: read the pending state first and send only if it is still pending.
     * A failed read never removes the notification. A send that didn't go through is checked again
     * and kept or removed; it is never sent a second time.
     */
    private suspend fun answer(
        context: Context,
        notificationId: Int,
        failure: FailureContext,
        check: (JsonObject?) -> PendingCheck,
        send: suspend () -> ApiResult,
    ) {
        val step = ActionDecisions.beforeSend(check(api.pending()))
        if (step != ActionStep.Send) return settle(context, notificationId, step, failure, null)
        val result = send()
        val outcome = ActionDecisions.outcomeOf(result.status)
        if (outcome == SendOutcome.Sent) return notifier.cancel(notificationId)
        settle(context, notificationId, ActionDecisions.afterButton(outcome, check(api.pending())), failure, result.body)
    }

    /** Applies a decided step: cancel, or keep the notification with a plain status for the user. */
    private fun settle(
        context: Context,
        notificationId: Int,
        step: ActionStep,
        failure: FailureContext,
        reason: String?,
    ) {
        val status =
            when (step) {
                ActionStep.Send -> return
                ActionStep.Cancel -> return notifier.cancel(notificationId)
                ActionStep.KeepCheckFailed -> context.getString(R.string.notify_check_failed)
                ActionStep.KeepSendFailed -> context.getString(R.string.reply_failed, reason.orEmpty().take(MAX_REASON))
                ActionStep.KeepResultUnknown -> context.getString(R.string.notify_result_unknown)
                ActionStep.KeepOpenOnly -> context.getString(R.string.notify_answer_in_app)
            }
        ActionDecisions.keepPlan(step, failure, status)?.let { notifier.keep(notificationId, it) }
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

        /**
         * One lock per notification id, for this process only. Receivers are short-lived, so the
         * table lives here; it is bounded (old idle entries are dropped), never grows without limit.
         */
        private val locks = LinkedHashMap<Int, Mutex>()
        private const val MAX_LOCKS = 64

        @Synchronized
        private fun lockFor(id: Int): Mutex {
            locks[id]?.let { return it }
            if (locks.size >= MAX_LOCKS) {
                locks.entries.firstOrNull { !it.value.isLocked }?.let { locks.remove(it.key) }
            }
            return Mutex().also { locks[id] = it }
        }
    }
}
