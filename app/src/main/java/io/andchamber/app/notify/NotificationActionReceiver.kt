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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val inlineDelivery by lazy { InlineNotificationFormDelivery(notifier, LocalApiNotificationFormTransport(api)) }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // A discarded Sending/Checking notification must stop a late result from redrawing it, before
        // this broadcast can queue behind a long per-id send. It only records the revision; the draft
        // is cleaned under the lock below and nothing is ever re-sent.
        if (intent.action == ACTION_INLINE_FORM) {
            val spec = NotificationActionIdentity.inlineSpecFrom(intent)
            if (spec?.command == InlineFormWire.DISCARD_PENDING) {
                spec.revision?.let { notifier.markInlineRevisionDismissed(spec.notificationId, it) }
            }
        }
        val pending = goAsync()
        scope.launch {
            val startedAt = System.nanoTime()
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            val sentToken =
                try {
                    if (usesInlineBudget(intent.action)) {
                        // Only the inline path gets the new total budget (lock wait, pending, POST,
                        // system await). Classic reply/form/permission actions keep their previous
                        // semantics without a new silent timeout.
                        withTimeoutOrNull(TOTAL_BUDGET_MS) {
                            withNotificationLock(notificationId) {
                                val remaining = TOTAL_BUDGET_MS - elapsedMillis(startedAt) - BUDGET_SAFETY_MS
                                act(context, intent, notificationId, remaining.coerceAtLeast(1))
                            }
                        }
                    } else {
                        withNotificationLock(notificationId) {
                            act(context, intent, notificationId, TOTAL_BUDGET_MS)
                        }
                    }
                } finally {
                    // The "Sent" pause below never keeps the broadcast alive.
                    pending.finish()
                }
            if (sentToken != null) {
                // "Sent" stays a moment, in a separate process-scope job. Only this exact revision is
                // cleared: a newer event or a kept draft on the same id has another revision.
                scope.launch { clearSent(notificationId, sentToken) }
            }
        }
    }

    private suspend fun clearSent(
        notificationId: Int,
        sentToken: String,
    ) {
        var cleaned = false
        try {
            delay(CLEAR_AFTER_MS)
            notifier.cancelIfCurrent(notificationId, sentToken)
            cleaned = true
        } finally {
            if (!cleaned) notifier.forgetSent(notificationId, sentToken)
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    /** Runs one action. The revision token of the "Sent" status when a reply was sent, otherwise null. */
    private suspend fun act(
        context: Context,
        intent: Intent,
        notificationId: Int,
        budgetMillis: Long,
    ): String? {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return null
        val directory = intent.getStringExtra(EXTRA_DIRECTORY)
        when (intent.action) {
            ACTION_INLINE_FORM -> {
                // The whole identity (key, business token, generation, notification revision, command)
                // must match the Intent's own data URI; a partial or altered Intent does nothing.
                val spec = NotificationActionIdentity.inlineSpecFrom(intent) ?: return null
                // Verbatim: the controller decides what counts as empty. Never logged or shown.
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                return inlineDelivery.handle(spec, text, budgetMillis)
            }
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
                // A form answer is never echoed back (no reply history, no draft extra, no body line):
                // it can be a code or a password. New text forms are answered inline; this branch only
                // serves choice buttons and single-field notifications posted before the upgrade.
                answer(context, notificationId, FailureContext(sessionId, directory, null), { ActionDecisions.checkForm(it, sessionId, formId) }, echoServerReason = false) {
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
        echoServerReason: Boolean = true,
        send: suspend () -> ApiResult,
    ) {
        val step = ActionDecisions.beforeSend(check(api.pending()))
        if (step != ActionStep.Send) return settle(context, notificationId, step, failure, null)
        val result = send()
        val outcome = ActionDecisions.outcomeOf(result.status)
        if (outcome == SendOutcome.Sent) return notifier.cancel(notificationId)
        // A form refusal can echo the submitted answer, so its body is never shown.
        val reason = result.body.takeIf { echoServerReason }
        settle(context, notificationId, ActionDecisions.afterButton(outcome, check(api.pending())), failure, reason)
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
                ActionStep.KeepSendFailed ->
                    reason?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.reply_failed, it.take(MAX_REASON)) }
                        ?: context.getString(R.string.form_send_refused)
                ActionStep.KeepResultUnknown -> context.getString(R.string.notify_result_unknown)
                ActionStep.KeepOpenOnly -> context.getString(R.string.notify_answer_in_app)
            }
        ActionDecisions.keepPlan(step, failure, status)?.let { notifier.keep(notificationId, it) }
    }

    companion object {
        const val ACTION_REPLY = "io.andchamber.app.action.NOTIFY_REPLY"
        const val ACTION_PERMISSION = "io.andchamber.app.action.NOTIFY_PERMISSION"
        const val ACTION_FORM = "io.andchamber.app.action.NOTIFY_FORM"

        /** Every inline notification-form action (steps, swipe-away, lookup retry). */
        const val ACTION_INLINE_FORM = "io.andchamber.app.action.NOTIFY_INLINE_FORM"
        const val EXTRA_INLINE_TOKEN = "inline_token"
        const val EXTRA_INLINE_GENERATION = "inline_generation"
        const val EXTRA_INLINE_REVISION = "inline_revision"
        const val EXTRA_INLINE_COMMAND = "inline_command"
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

        /** One broadcast (lock wait + pending + POST + a bounded system wait) must fit in this. */
        private const val TOTAL_BUDGET_MS = 7_800L
        private const val BUDGET_SAFETY_MS = 250L

        /** The new total-budget path applies only to inline form actions, never to classic buttons. */
        internal fun usesInlineBudget(action: String?): Boolean = action == ACTION_INLINE_FORM

        /**
         * Fixed stripe table, one lock per stripe, for this process only. A notification id always
         * maps to the same stripe, so two actions for the same id can never get different locks; a
         * different id usually runs in parallel, while a hash collision just queues. Receivers are
         * short-lived, so the table lives here; it is fixed-size and cannot grow.
         */
        private val locks = Array(LOCK_STRIPES) { Mutex() }
        private const val LOCK_STRIPES = 64

        /**
         * Runs [block] while holding notification [id]'s stripe lock. The question sink takes the same
         * lock for an inline form's first post, so a new post and an action on the same id never interleave.
         * The caller's total budget includes any queueing caused by a hash collision.
         */
        internal suspend fun <T> withNotificationLock(
            id: Int,
            block: suspend () -> T,
        ): T = locks[Math.floorMod(id, LOCK_STRIPES)].withLock { block() }
    }
}
