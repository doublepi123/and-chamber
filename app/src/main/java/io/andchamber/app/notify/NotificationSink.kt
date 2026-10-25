package io.andchamber.app.notify

import io.andchamber.app.data.LocalApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashMap

/**
 * The notification writes a question post needs. [OpenChamberNotifier] implements it; the test fake
 * records the same calls, so the event-ordering rule is checked without Android.
 */
internal interface NotificationPoster : InlineFormSurface {
    fun post(
        event: DesktopNotification,
        channel: Channel,
        questionAnswer: FormAnswer? = null,
    )
}

/**
 * Per-notification event order for posts. A pending read happens before the per-id lock, so an older
 * event can come back with a valid snapshot after a newer event already posted. Every event that will
 * be posted gets a ticket before that asynchronous read; a question may only publish while its ticket
 * is still the newest for the id. A non-question post on the same id issues a ticket too, so it also
 * retires an older question that is still waiting.
 */
internal class NotificationEventOrder(
    private val maxEntries: Int = 128,
) {
    private val latest = LinkedHashMap<Int, Long>()

    /**
     * Globally monotonic, never per id: entries may be evicted from [latest] under pressure, and a
     * later ticket for the same id must still be larger than every older one. Resetting per id would
     * let an evicted old ticket become current again (ABA).
     */
    private var counter = 0L

    @Synchronized
    fun issue(id: Int): Long {
        counter += 1L
        val ticket = counter
        latest[id] = ticket
        if (latest.size > maxEntries) {
            latest.entries.firstOrNull()?.let { latest.remove(it.key) }
        }
        return ticket
    }

    @Synchronized
    fun isCurrent(
        id: Int,
        ticket: Long,
    ): Boolean = latest[id] == ticket
}

/**
 * Receives the `[OpenChamberDesktopNotify]` lines from the server's output pump and turns them into
 * notifications: foreground suppression, the channel, and the per-kind actions. Runs off the pump's
 * thread so a slow network call never blocks reading the server's output.
 */
class NotificationSink internal constructor(
    private val notifier: NotificationPoster,
    private val foreground: AppForeground,
    private val pendingRead: suspend () -> JsonObject?,
    private val inline: InlineNotificationFormDelivery,
    private val scope: CoroutineScope,
    private val order: NotificationEventOrder,
) {
    constructor(
        notifier: OpenChamberNotifier,
        foreground: AppForeground,
        api: LocalApiClient,
    ) : this(
        notifier = notifier,
        foreground = foreground,
        pendingRead = api::pending,
        inline = InlineNotificationFormDelivery(notifier, LocalApiNotificationFormTransport(api)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        order = NotificationEventOrder(),
    )

    internal constructor(
        notifier: NotificationPoster,
        foreground: AppForeground,
        pendingRead: suspend () -> JsonObject?,
        transport: NotificationFormTransport,
        scope: CoroutineScope,
        order: NotificationEventOrder = NotificationEventOrder(),
    ) : this(
        notifier = notifier,
        foreground = foreground,
        pendingRead = pendingRead,
        inline = InlineNotificationFormDelivery(notifier, transport),
        scope = scope,
        order = order,
    )

    /** Entry point called for each raw line. Malformed lines are ignored. */
    fun accept(line: String) {
        val event = NotificationPayload.parseLine(line) ?: return
        val id = NotificationPayload.notificationId(event.tag)
        // Left to the page only when the event asks for that (requireHidden) and the local page is on screen.
        val leaveToPage = NotifyPolicy.leaveToPage(event.requireHidden, foreground.isInForeground())
        when (val delivery = NotifyPolicy.deliveryFor(event.kind, leaveToPage)) {
            is Delivery.Suppressed -> Unit
            is Delivery.Post -> {
                // Issued synchronously, before the pending read can suspend: a later posted event on
                // the same id always has a larger ticket, whatever the network order turns out to be.
                val ticket = order.issue(id)
                val actions = ActionPlan.forEvent(event)
                if (actions == ActionPlan.Kind.Question) {
                    scope.launch { postQuestion(event, delivery.channel, id, ticket) }
                } else {
                    scope.launch { postPlain(event, delivery.channel, id, ticket) }
                }
            }
        }
    }

    /**
     * A non-question event shares the per-id lock and the same ticket rule. A newer event on the same
     * notification id therefore retires this one too, and a plain post can never land after (and
     * overwrite) a newer question just because its publish was synchronous.
     */
    private suspend fun postPlain(
        event: DesktopNotification,
        channel: Channel,
        id: Int,
        ticket: Long,
    ) {
        NotificationActionReceiver.withNotificationLock(id) {
            if (!order.isCurrent(id, ticket)) return@withNotificationLock
            notifier.post(event, channel)
        }
    }

    /**
     * A question's actions depend on its form, so the real pending state is read first.
     *
     * The question tag carries no form id (runtime.js:728), so the notification answers only when the
     * session has exactly one pending form. A form of 1..8 plain text fields (one text field included)
     * is answered field by field inside the notification ([InlineNotificationFormDelivery]); it never
     * gets the form screen. A single choice keeps its buttons. A failed read posts a notification with
     * an explicit retry instead of guessing the fields. Anything else opens the app on a tap.
     *
     * Every publish branch re-checks [ticket] inside the per-id lock, so a newer question or a plain
     * event on the same notification id can never be overwritten by this older read.
     */
    private suspend fun postQuestion(
        event: DesktopNotification,
        channel: Channel,
        id: Int,
        ticket: Long,
    ) {
        val sessionId = event.sessionId
        val pending = pendingRead()
        when (val route = QuestionRoute.decide(pending, sessionId)) {
            is QuestionRoute.Inline -> {
                // Same lock as the receiver: a new first step and an old step's action never interleave.
                NotificationActionReceiver.withNotificationLock(id) {
                    if (!order.isCurrent(id, ticket)) return@withNotificationLock
                    val posted = inline.start(id, channel, sessionId!!, event.directory, route.formId, pending!!)
                    // Skipped: a newer form (or a live draft) owns this id; never redraw or cancel it.
                    if (posted == InlineStart.NotPosted) notifier.post(event, channel, questionAnswer = FormAnswer.OpenOnly)
                }
            }
            QuestionRoute.Unresolved -> {
                NotificationActionReceiver.withNotificationLock(id) {
                    if (!order.isCurrent(id, ticket)) return@withNotificationLock
                    val posted = inline.startLookup(id, channel, sessionId!!, event.directory)
                    if (posted == InlineStart.NotPosted) notifier.post(event, channel, questionAnswer = FormAnswer.OpenOnly)
                }
            }
            is QuestionRoute.Classic -> {
                NotificationActionReceiver.withNotificationLock(id) {
                    if (!order.isCurrent(id, ticket)) return@withNotificationLock
                    notifier.post(event, channel, questionAnswer = route.answer)
                }
            }
            QuestionRoute.NoSingleForm -> {
                NotificationActionReceiver.withNotificationLock(id) {
                    if (!order.isCurrent(id, ticket)) return@withNotificationLock
                    notifier.post(event, channel, questionAnswer = null)
                }
            }
        }
    }
}
