package io.andchamber.app.notify

import io.andchamber.app.notify.NotificationPayload.text
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Where a question notification goes, decided from one real pending read. Pure, so the rule "a plain
 * text form is answered inside the notification, never through the form screen" is tested directly.
 *
 * - [Inline]: the session's only pending form is 1..8 plain text fields (a single text field
 *   included). It is answered field by field inside the notification.
 * - [Classic]: only [FormAnswer.Choice] keeps its buttons; anything else is [FormAnswer.OpenOnly].
 *   [FormAnswer.Fields] and [FormAnswer.Text] never come out of here, so a question can reach neither
 *   the form screen nor the old one-field reply path.
 * - [Unresolved]: the pending read failed, so the form and its field keys are unknown. The
 *   notification offers an explicit retry instead of guessing.
 * - [NoSingleForm]: no pending form, or several (the question tag carries no form id).
 */
sealed interface QuestionRoute {
    data class Inline(val formId: String) : QuestionRoute

    data class Classic(val answer: FormAnswer) : QuestionRoute

    data object Unresolved : QuestionRoute

    data object NoSingleForm : QuestionRoute

    companion object {
        fun decide(
            pending: JsonObject?,
            sessionId: String?,
        ): QuestionRoute {
            sessionId ?: return NoSingleForm
            pending ?: return Unresolved
            val form = PendingForms.singleFormFor(pending, sessionId) ?: return NoSingleForm
            val formId = form.text("id")?.takeIf { it.isNotBlank() }
            if (formId != null && PendingForms.inlineForm(pending, sessionId, formId) != null) return Inline(formId)
            return when (val answer = PendingForms.classifyForm(form)) {
                is FormAnswer.Choice -> Classic(answer)
                is FormAnswer.Text, is FormAnswer.Fields, FormAnswer.OpenOnly -> Classic(FormAnswer.OpenOnly)
            }
        }
    }
}

/** What the sink does after [InlineNotificationFormDelivery.start] / [InlineNotificationFormDelivery.startLookup]. */
enum class InlineStart {
    /** The inline notification is posted. */
    Posted,

    /** Refused on purpose (a newer form or a live draft owns the id): leave the notification alone. */
    Skipped,

    /** Not answerable inline, or the post failed: post the open-only notification instead. */
    NotPosted,
}

/** Who owns a notification id's draft, relative to one action's form key. */
internal enum class DraftOwner {
    /** This form's draft: the action may run. */
    This,

    /** Another form's draft: the action may change nothing. */
    Other,

    /** No draft at all (process restart or expiry): the only case that restarts at field one. */
    Lost,
}

/** A fixed, safe line shown above the step. Never carries user text. */
enum class InlineFormNotice {
    /** The local draft was lost (process restart or expiry); the form starts again at field one. */
    Restarted,
}

/** What one step notification shows. Built from a projection only, so no answer can reach it. */
data class InlineFormView(
    val projection: InlineFormProjection,
    val notice: InlineFormNotice? = null,
)

/** The short status a finished inline form shows before it is cleared. */
enum class InlineFormDone {
    /** The whole answer map was accepted. */
    Sent,

    /** The form was answered or closed elsewhere; nothing more can be sent. */
    Gone,
}

/** The wire names of the inline commands, carried in the action identity (never the answer). */
object InlineFormWire {
    const val REPLY = "reply"

    /** Reply on the last field: the controller may send the whole map, so "Sending…" is shown first. */
    const val REPLY_SEND = "reply-send"
    const val BACK = "back"
    const val SKIP = "skip"

    /** Skip on the last (optional) field: may send, like [REPLY_SEND]. */
    const val SKIP_SEND = "skip-send"
    const val CANCEL = "cancel"
    const val RETRY = "retry"

    /**
     * The recovery entry of a "Sending…"/"Checking…" notification. It only re-reads pending: a lost
     * draft restarts at field one, an existing unknown result stays unknown until Retry is explicit.
     */
    const val RESUME = "resume"

    /** The delete intent of a "Sending…"/"Checking…" notification: drop the draft, never re-send. */
    const val DISCARD_PENDING = "discard-pending"

    /** True when the command may reach the network (pending check and/or the final send). */
    fun mayCallNetwork(command: String?): Boolean = command == REPLY_SEND || command == SKIP_SEND || command == RETRY || command == RESUME

    /** The step's delete intent: the user swiped it away. Same effect as Cancel. */
    const val DISMISS = "dismiss"

    /** The "couldn't read the form" notification's retry: read pending again, then start. */
    const val LOOKUP = "lookup"
}

/** The buttons of one inline step, in display order. Only [carriesInput] buttons are mutable. */
enum class InlineFormButton(val command: String, val carriesInput: Boolean) {
    Answer(InlineFormWire.REPLY, true),
    AnswerAndSend(InlineFormWire.REPLY_SEND, true),
    Back(InlineFormWire.BACK, false),
    Skip(InlineFormWire.SKIP, false),
    SkipAndSend(InlineFormWire.SKIP_SEND, false),
    Cancel(InlineFormWire.CANCEL, false),
    Retry(InlineFormWire.RETRY, false),
}

/** Button planning. Pure; Android shows at most three actions, so this never returns more. */
object InlineFormLayouts {
    const val MAX_ACTIONS = 3

    /**
     * Reply first (it is the reason for the notification), then Back and Skip when the step allows
     * them, then Cancel only if a slot is still free. Swiping the notification away always cancels,
     * so a full row never loses the way out.
     */
    fun buttons(projection: InlineFormProjection): List<InlineFormButton> {
        if (projection.outcome != InlineFormOutcome.Render || projection.token == null) return emptyList()
        val actions = projection.actions
        val ordered =
            buildList {
                if (InlineFormActionKind.Retry in actions) add(InlineFormButton.Retry)
                if (InlineFormActionKind.Reply in actions) add(if (projection.isLast) InlineFormButton.AnswerAndSend else InlineFormButton.Answer)
                if (InlineFormActionKind.Back in actions) add(InlineFormButton.Back)
                if (InlineFormActionKind.Skip in actions) add(if (projection.isLast) InlineFormButton.SkipAndSend else InlineFormButton.Skip)
                if (InlineFormActionKind.Cancel in actions) add(InlineFormButton.Cancel)
            }
        return ordered.take(MAX_ACTIONS)
    }

    /** A step with a live draft always gets a delete intent, so a swipe drops the draft. */
    fun hasDismiss(projection: InlineFormProjection): Boolean =
        projection.outcome == InlineFormOutcome.Render && projection.token != null
}

/**
 * The notification side of the inline flow, implemented by [OpenChamberNotifier]. Every write after
 * the first is revision-scoped: a write that names a revision the system no longer shows (or that a
 * newer local write replaced) changes nothing and returns null/false.
 */
interface InlineFormSurface {
    /** True while [id] still shows [revision] and no newer local write exists. */
    fun isInlineRevisionCurrent(
        id: Int,
        revision: String,
    ): Boolean

    /** True when a newer write than [revision] exists for [id] (locally or on screen). */
    fun hasNewerThan(
        id: Int,
        revision: String,
    ): Boolean

    /** Posts the first step of a form; replaces whatever [id] showed. The new revision, or null. */
    fun postInline(
        id: Int,
        channel: Channel,
        key: InlineFormKey,
        view: InlineFormView,
    ): String?

    /** Replaces [id]'s step only while it still shows [revision]. The new revision, or null when stale. */
    fun updateInlineIfRevision(
        id: Int,
        revision: String,
        key: InlineFormKey,
        view: InlineFormView,
    ): String?

    /**
     * Waits (bounded) until the system shows [revision] for [id]. Posting is asynchronous, and every
     * revision-scoped write requires the system to show the revision it names, so a write that follows
     * another one immediately waits for it first. False on timeout.
     */
    suspend fun awaitShown(
        id: Int,
        revision: String,
    ): Boolean

    /**
     * "Sending…" (or "Checking…" for [retry]) with no actions, while [id] shows [revision]. Written
     * before the controller may call the network. The new revision, or null when stale or refused.
     */
    fun inlineSendingIfRevision(
        id: Int,
        revision: String,
        key: InlineFormKey,
        retry: Boolean,
    ): String?

    /**
     * A short fixed status (no actions, nothing to tap) while [id] shows [revision], recorded as a
     * pending "Sent" so the caller can clear exactly this revision later. The new revision, or null.
     */
    fun inlineDoneIfRevision(
        id: Int,
        revision: String,
        done: InlineFormDone,
    ): String?

    /** "Couldn't read the form" with an explicit retry. The new revision, or null. */
    fun postInlineLookup(
        id: Int,
        channel: Channel,
        sessionId: String,
        directory: String?,
    ): String?

    /** The lookup notification again (its retry failed), only while it still shows [revision]. */
    fun updateInlineLookupIfRevision(
        id: Int,
        revision: String,
        sessionId: String,
        directory: String?,
    ): String?

    /** "Answer this in OpenChamber": tap to open, no actions; only while [id] shows [revision]. */
    fun inlineFallbackIfRevision(
        id: Int,
        revision: String,
        sessionId: String,
        directory: String?,
    ): String?

    /** Removes [id] only while it shows [revision]. */
    fun cancelIfRevision(
        id: Int,
        token: String,
    ): Boolean

    /**
     * Recovery write after a revision-scoped write was refused or the system never showed it: posts
     * [view] again only while [revision] is still the newest revision this process wrote for [id].
     * It deliberately does not require the system to show [revision] (the system may lag), but it can
     * never overwrite a newer notification for the same id. The new revision, or null.
     */
    fun repostInlineIfLatest(
        id: Int,
        revision: String,
        key: InlineFormKey,
        view: InlineFormView,
    ): String?

    /**
     * The same local-ownership recovery for a finished status: posts [done] only while [revision] is
     * still the newest revision this process wrote for [id]. Used when the system never showed the
     * "Sending…" revision, so the normal revision-scoped done write could not run.
     */
    fun repostInlineDoneIfLatest(
        id: Int,
        revision: String,
        done: InlineFormDone,
    ): String?
}

/**
 * A monotonic budget for one broadcast. Wall-clock time is used on purpose: the notification must
 * not keep a PendingResult waiting, so a slow lock, pending read, system update or send all consume
 * the same allowance.
 */
internal class InlineFormBudget(
    private val deadlineNanos: Long,
) {
    fun remainingMillis(): Long = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)

    companion object {
        fun after(millis: Long): InlineFormBudget = InlineFormBudget(System.nanoTime() + millis.coerceAtLeast(1) * 1_000_000L)
    }
}

/**
 * Wires [InlineNotificationFormController] to the notification: the first post, every action, the
 * swipe-away and the lookup retry. It never starts an Activity and never sends anything itself: the
 * only POST is the controller's final whole-map send.
 *
 * Callers hold the per-notification lock ([NotificationActionReceiver.withNotificationLock]) around
 * every call, so the revision check, the business step and the revision-scoped write cannot
 * interleave with another action or a new question post on the same id.
 *
 * Safety rules:
 * - An action whose notification revision is no longer current does nothing at all (no step, no
 *   write, no network): a duplicated or replayed broadcast, or a button of a replaced notification.
 * - The draft that owns the notification id decides what an action with a current revision may do
 *   ([DraftOwner]):
 *   - [DraftOwner.Lost] (no draft at all: process restart or expiry) is the only case that restarts.
 *     [InlineNotificationFormController.begin] re-reads the real pending form and shows field one
 *     with [InlineFormNotice.Restarted]. The action's text is dropped: it never fills a field, skips a
 *     step or submits.
 *   - [DraftOwner.Other] (another form owns this id): nothing at all, no step, no write, no restart.
 *   - [DraftOwner.This]: the action runs. If the controller ignores it (a duplicate, an older
 *     generation, or a step behind the draft after a refused refresh), [begin] only re-renders this
 *     form's live draft at its true step; it never resets that draft. This re-render is also what
 *     ends the system's "sending" spinner on the notification.
 * - Sent and Gone first replace the step with a short fixed status, then the caller clears exactly
 *   that revision after a moment. Cancelling right away while the system still shows its reply spinner
 *   lets SystemUI keep a copy of the notification with the just-typed text inserted; an update does not.
 * - Dismissed (Cancel button) removes the notification at once; Fallback only says "answer in
 *   OpenChamber" with a tap to open, it never opens anything by itself.
 */
class InlineNotificationFormDelivery(
    private val surface: InlineFormSurface,
    private val transport: NotificationFormTransport,
    private val store: InlineNotificationFormStore = InlineNotificationFormStore.shared,
) {
    private fun controller(
        key: InlineFormKey,
        budget: InlineFormBudget = InlineFormBudget.after(DEFAULT_BUDGET_MS),
    ) = InlineNotificationFormController(
        transport = transport,
        key = key,
        store = store,
        budgetMillis = budget.remainingMillis().coerceAtLeast(1),
    )

    /**
     * Posts the first step for [formId] from the pending snapshot the sink already read. Never sends
     * anything. [InlineStart.Skipped] means the controller refused (for example a late question of an
     * older form while a newer form owns this id): the caller must not touch the notification.
     * [InlineStart.NotPosted] means the form is not answerable inline after all, or posting failed; the
     * caller then posts its open-only notification.
     */
    suspend fun start(
        id: Int,
        channel: Channel,
        sessionId: String,
        directory: String?,
        formId: String,
        pending: JsonObject,
    ): InlineStart {
        val key = InlineFormKey(sessionId, directory, formId, id)
        val projection = controller(key).begin(pending)
        return when (projection.outcome) {
            InlineFormOutcome.Render ->
                if (surface.postInline(id, channel, key, InlineFormView(projection)) != null) InlineStart.Posted else InlineStart.NotPosted
            InlineFormOutcome.Ignored -> InlineStart.Skipped
            InlineFormOutcome.Sent, InlineFormOutcome.Gone, InlineFormOutcome.Dismissed, InlineFormOutcome.Fallback -> InlineStart.NotPosted
        }
    }

    /**
     * The pending read failed: post a notification that can retry the lookup. A live draft on this id
     * keeps its working notification ([InlineStart.Skipped]); a failed read never redraws it.
     */
    fun startLookup(
        id: Int,
        channel: Channel,
        sessionId: String,
        directory: String?,
    ): InlineStart {
        if (store.find(id) != null) return InlineStart.Skipped
        return if (surface.postInlineLookup(id, channel, sessionId, directory) != null) InlineStart.Posted else InlineStart.NotPosted
    }

    /** Who owns notification [key]'s id right now. Read under the caller's per-id lock. */
    private fun ownerOf(key: InlineFormKey): DraftOwner {
        val live = store.find(key.notificationId) ?: return DraftOwner.Lost
        return if (live.key == key) DraftOwner.This else DraftOwner.Other
    }

    /**
     * Handles one delivered inline action. [replyText] is the RemoteInput text, verbatim (not trimmed;
     * the controller decides what counts as empty). It is never logged or shown.
     *
     * Returns the revision of a finished status ([InlineFormDone]) the caller clears after a moment
     * with [OpenChamberNotifier.cancelIfCurrent], or null.
     */
    internal suspend fun handle(
        spec: NotificationActionIdentity.Spec,
        replyText: String?,
        budgetMillis: Long = DEFAULT_BUDGET_MS,
    ): String? {
        val revision = spec.revision ?: return null
        val budget = InlineFormBudget.after(budgetMillis)
        return when (spec.command) {
            InlineFormWire.LOOKUP -> lookup(spec, revision, budget)
            InlineFormWire.DISMISS -> {
                dismiss(spec, revision)
                null
            }
            InlineFormWire.DISCARD_PENDING -> {
                discardPending(spec, revision)
                null
            }
            InlineFormWire.RESUME -> resume(spec, revision, budget)
            else -> step(spec, revision, replyText, budget)
        }
    }

    private suspend fun step(
        spec: NotificationActionIdentity.Spec,
        shown: String,
        replyText: String?,
        budget: InlineFormBudget,
    ): String? {
        val key = keyOf(spec) ?: return null
        val token = spec.token ?: return null
        val generation = spec.generation ?: return null
        val command =
            when (spec.command) {
                InlineFormWire.REPLY, InlineFormWire.REPLY_SEND -> replyText?.let { InlineFormCommand.Reply(it) }
                InlineFormWire.BACK -> InlineFormCommand.Back
                InlineFormWire.SKIP, InlineFormWire.SKIP_SEND -> InlineFormCommand.Skip
                InlineFormWire.CANCEL -> InlineFormCommand.Cancel
                InlineFormWire.RETRY -> InlineFormCommand.Retry
                else -> return null
            }
        // A replaced or replayed notification's button: nothing may change, not even the draft.
        if (!surface.isInlineRevisionCurrent(key.notificationId, shown)) return null
        when (ownerOf(key)) {
            // Another form owns this id: never restart, redraw or cancel it.
            DraftOwner.Other -> return null
            // The only restart: no draft exists at all. Field one again, from a real read; the text of
            // this action is dropped and nothing is sent.
            DraftOwner.Lost -> {
                val restarted = safely { controller(key, budget).begin() } ?: FALLBACK
                val notice = InlineFormNotice.Restarted.takeIf { restarted.outcome == InlineFormOutcome.Render }
                return apply(key, shown, restarted, notice)
            }
            DraftOwner.This -> Unit
        }
        // The step may check and send: replace it with "Sending…" first, which ends the system's reply
        // spinner and removes every button. If that write is refused, the old revision stays current.
        var revision = shown
        if (command != null && InlineFormWire.mayCallNetwork(spec.command)) {
            val sending =
                withTimeoutOrNull(budget.remainingMillis().coerceAtLeast(1)) {
                    surface.inlineSendingIfRevision(key.notificationId, revision, key, retry = command == InlineFormCommand.Retry)
                }
            if (sending != null) {
                revision = sending
                // The result may come back at once (a blank required field); its write needs the
                // system to show "Sending…" first. A lagging system is not trusted: apply() falls
                // back to a local-ownership recovery post below.
                withTimeoutOrNull(budget.remainingMillis().coerceAtLeast(1)) {
                    surface.awaitShown(key.notificationId, sending)
                }
            }
        }
        val controller = controller(key, budget)
        var projection =
            command?.let { safely { controller.act(InlineFormAction(token, generation, it)) } }
                ?: IGNORED
        if (projection.outcome == InlineFormOutcome.Ignored) {
            // This form's draft is live but did not take the action (a duplicate, an older generation,
            // or the shown step is behind after a refused refresh). Re-render its true step: begin()
            // keeps a live draft of the same key and schema, so nothing is reset, nothing is sent, and
            // the text of this action is dropped. A newer owner that appeared meanwhile gets nothing.
            if (ownerOf(key) != DraftOwner.This) return null
            projection = safely { controller.begin() } ?: FALLBACK
        }
        return apply(key, revision, projection, null)
    }

    /** The user swiped the step away. The notification is already gone, so only a newer one vetoes. */
    private suspend fun dismiss(
        spec: NotificationActionIdentity.Spec,
        revision: String,
    ) {
        val key = keyOf(spec) ?: return
        val token = spec.token ?: return
        val generation = spec.generation ?: return
        if (surface.hasNewerThan(key.notificationId, revision)) return
        controller(key).act(InlineFormAction(token, generation, InlineFormCommand.Cancel))
    }

    /**
     * The user swiped a Sending/Checking notification. The revision was already marked dismissed
     * synchronously in the receiver, so a late result cannot redraw it. Here only this form's draft is
     * removed; another form's draft, or a newer notification, is left alone. Nothing is sent.
     */
    private suspend fun discardPending(
        spec: NotificationActionIdentity.Spec,
        revision: String,
    ) {
        val key = keyOf(spec) ?: return
        // A late delete broadcast must not clear a draft that a newer write already owns.
        if (surface.hasNewerThan(key.notificationId, revision)) return
        controller(key).dismiss()
    }

    private suspend fun lookup(
        spec: NotificationActionIdentity.Spec,
        revision: String,
        budget: InlineFormBudget,
    ): String? {
        val id = spec.notificationId
        if (!surface.isInlineRevisionCurrent(id, revision)) return null
        val pending =
            withTimeoutOrNull(budget.remainingMillis().coerceAtLeast(1)) {
                transport.pending()
            }
        when (val route = QuestionRoute.decide(pending, spec.sessionId)) {
            is QuestionRoute.Inline -> {
                val key = InlineFormKey(spec.sessionId, spec.directory, route.formId, id)
                return apply(key, revision, controller(key, budget).begin(pending), null)
            }
            QuestionRoute.Unresolved -> surface.updateInlineLookupIfRevision(id, revision, spec.sessionId, spec.directory)
            is QuestionRoute.Classic, QuestionRoute.NoSingleForm ->
                surface.inlineFallbackIfRevision(id, revision, spec.sessionId, spec.directory)
        }
        return null
    }

    /**
     * The recovery button of a "Sending…"/"Checking…" notification. It re-reads pending and never
     * sends: a lost draft restarts at the first field, an existing unknown result stays unknown with
     * its explicit Retry. It can never replay an older answer because it carries no business action.
     */
    private suspend fun resume(
        spec: NotificationActionIdentity.Spec,
        revision: String,
        budget: InlineFormBudget,
    ): String? {
        val id = spec.notificationId
        if (!surface.isInlineRevisionCurrent(id, revision)) return null
        val key = keyOf(spec) ?: return null
        return when (ownerOf(key)) {
            // Another form owns this id: never restart, redraw or replace it.
            DraftOwner.Other -> null
            DraftOwner.Lost -> {
                val restarted = safely { controller(key, budget).begin() } ?: FALLBACK
                val notice = InlineFormNotice.Restarted.takeIf { restarted.outcome == InlineFormOutcome.Render }
                apply(key, revision, restarted, notice)
            }
            DraftOwner.This -> {
                val checked = safely { controller(key, budget).resume() } ?: FALLBACK
                apply(key, revision, checked, null)
            }
        }
    }

    private fun apply(
        key: InlineFormKey,
        revision: String,
        projection: InlineFormProjection,
        notice: InlineFormNotice?,
    ): String? {
        val id = key.notificationId
        val written: String? =
            when (projection.outcome) {
                InlineFormOutcome.Render -> surface.updateInlineIfRevision(id, revision, key, InlineFormView(projection, notice))
                InlineFormOutcome.Sent -> {
                    val done = surface.inlineDoneIfRevision(id, revision, InlineFormDone.Sent)
                    return done ?: surface.repostInlineDoneIfLatest(id, revision, InlineFormDone.Sent)
                }
                InlineFormOutcome.Gone -> {
                    val done = surface.inlineDoneIfRevision(id, revision, InlineFormDone.Gone)
                    return done ?: surface.repostInlineDoneIfLatest(id, revision, InlineFormDone.Gone)
                }
                InlineFormOutcome.Dismissed -> {
                    surface.cancelIfRevision(id, revision)
                    null
                }
                InlineFormOutcome.Fallback -> surface.inlineFallbackIfRevision(id, revision, key.sessionId, key.directory)
                InlineFormOutcome.Ignored -> null
            }
        if (written != null || projection.outcome != InlineFormOutcome.Render) return null
        // The system lagged or refused the write. The local revision may still own this id; if so,
        // post an actionable recovery notification without waiting for the system to catch up. A
        // newer local revision (another event or form) makes this a no-op, so it is never overwritten.
        surface.repostInlineIfLatest(id, revision, key, InlineFormView(projection, notice))
        return null
    }

    /** An unexpected failure is null, never a guess. Cancellation still propagates. */
    private suspend fun safely(block: suspend () -> InlineFormProjection): InlineFormProjection? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** Receiver-side budget when a caller has no tighter allowance. */
        const val DEFAULT_BUDGET_MS = 7_000L

        val IGNORED = InlineFormProjection(InlineFormOutcome.Ignored, InlineFormStatus.Ignored)
        val FALLBACK = InlineFormProjection(InlineFormOutcome.Fallback, InlineFormStatus.Unavailable)
    }

    private fun keyOf(spec: NotificationActionIdentity.Spec): InlineFormKey? {
        val formId = spec.formId?.takeIf { it.isNotBlank() } ?: return null
        return InlineFormKey(spec.sessionId, spec.directory, formId, spec.notificationId)
    }
}
