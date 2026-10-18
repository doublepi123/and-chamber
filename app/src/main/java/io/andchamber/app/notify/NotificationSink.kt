package io.andchamber.app.notify

import io.andchamber.app.data.LocalApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the `[OpenChamberDesktopNotify]` lines from the server's output pump and turns them into
 * notifications: foreground suppression, the channel, and the per-kind actions. Runs off the pump's
 * thread so a slow network call never blocks reading the server's output.
 */
class NotificationSink(
    private val notifier: OpenChamberNotifier,
    private val foreground: AppForeground,
    private val api: LocalApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Entry point called for each raw line. Malformed lines are ignored. */
    fun accept(line: String) {
        val event = NotificationPayload.parseLine(line) ?: return
        when (val delivery = NotifyPolicy.deliveryFor(event.kind, foreground.isInForeground())) {
            is Delivery.Suppressed -> Unit
            is Delivery.Post -> {
                val actions = ActionPlan.forEvent(event)
                if (actions == ActionPlan.Kind.Question) {
                    scope.launch { postQuestion(event, delivery.channel) }
                } else {
                    notifier.post(event, delivery.channel)
                }
            }
        }
    }

    /**
     * A question's actions depend on its form: fetch the pending forms, classify, then post with the
     * answer actions (choice buttons or a reply field). Anything unclassifiable opens the app instead.
     */
    private suspend fun postQuestion(
        event: DesktopNotification,
        channel: Channel,
    ) {
        val sessionId = event.sessionId
        val pending = api.pending()
        // The question tag carries no form id (runtime.js:728), so answer from the notification only
        // when the session has exactly one pending form. With several, the notification can't know
        // which one it refers to, and opening the app is the safe answer.
        val answer =
            if (sessionId != null && pending != null) {
                PendingForms.singleFormFor(pending, sessionId)?.let { PendingForms.classifyForm(it) }
            } else {
                null
            }
        notifier.post(event, channel, questionAnswer = answer)
    }
}
