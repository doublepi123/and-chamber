package io.andchamber.app.notify

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * The identity of one notification's inline form interaction. All four parts matter: the same form can
 * be shown by two notifications (and the same notification id can later carry a different form), and
 * the same session id can live in another directory.
 */
data class InlineFormKey(
    val sessionId: String,
    val directory: String?,
    val formId: String,
    val notificationId: Int,
)

/** A per-step command. [Reply]'s text is never part of its `toString`. */
sealed interface InlineFormCommand {
    /** The text the user typed for the current field. It is stored verbatim and never logged. */
    class Reply internal constructor(text: String) : InlineFormCommand {
        internal val text: String = text

        override fun toString(): String = "Reply(<redacted>)"
    }

    /** Go back one field; the staged answers stay, so the user can edit one value. */
    data object Back : InlineFormCommand

    /** Leave the current optional field out of the answer map (or submit on the last field). */
    data object Skip : InlineFormCommand

    /** Drop the local draft and the notification interaction. Nothing is sent to the server. */
    data object Cancel : InlineFormCommand

    /** After a failed pending check or an unknown send result: check again, then retry explicitly. */
    data object Retry : InlineFormCommand
}
/**
 * One notification action. [token] is the draft's random interaction token and [generation] the step
 * it was shown with; a stale, duplicated or replayed action carries an older pair and does nothing.
 * The pair is a business identity: it is not, and must not be used as, the notification revision.
 */
data class InlineFormAction(
    val token: String,
    val generation: Long,
    val command: InlineFormCommand,
)

/** What a renderer may show. Fixed codes only; the caller owns the localized strings. */
enum class InlineFormStatus {
    /** The current field can be answered. */
    Editing,

    /** The current required field is still blank; nothing was sent. */
    RequiredMissing,

    /** The pending read failed; the draft is intact and Retry is safe (no send happened). */
    CheckFailed,

    /** The server refused the answer (3xx/4xx); it was not applied. */
    SendFailed,

    /** No answer or a 5xx: the answer may have been applied. Only an explicit Retry/check may follow. */
    ResultUnknown,

    /** The pending form changed; the old answers were dropped and the first field is shown again. */
    SchemaChanged,

    Sent,
    Gone,
    Cancelled,

    /** The form is not answerable from the notification; use the app's form screen. */
    Unavailable,

    /** Stale/duplicate/expired action: no notification side effect. */
    Ignored,
}

/** What the caller does with the notification. [Render] and the terminal outcomes only. */
enum class InlineFormOutcome {
    /** Show/update this step, or its safe failure status. */
    Render,

    /** The answer was accepted: remove the notification. */
    Sent,

    /** The form was answered or cancelled elsewhere: remove the notification. */
    Gone,

    /** The user cancelled locally: remove the notification, without touching the server form. */
    Dismissed,

    /** Not answerable inline: use the existing app form path. */
    Fallback,

    /** Nothing changed and nothing may be shown: stale, duplicate, expired or unknown draft. */
    Ignored,
}

/** The actions a notification may offer for a step. */
enum class InlineFormActionKind {
    Reply,
    Back,
    Skip,
    Cancel,
    Retry,
}

/**
 * Everything a notification renderer may see. It never carries an answer, a draft value, a default or
 * a server error body: only the field's place, its form-provided title/description, a safe status and
 * the action choices. A data class is fine here because every member is display-only.
 *
 * [index] is zero-based and [count] is the form's field count (0 when no form is known yet).
 */
data class InlineFormProjection(
    val outcome: InlineFormOutcome,
    val status: InlineFormStatus,
    val token: String? = null,
    val generation: Long = 0,
    val index: Int = 0,
    val count: Int = 0,
    val formTitle: String? = null,
    val title: String? = null,
    val description: String? = null,
    val actions: Set<InlineFormActionKind> = emptySet(),
) {
    /** True when [index] is the last field, i.e. the next Reply/Skip composes the whole map. */
    val isLast: Boolean get() = count > 0 && index == count - 1
}

/**
 * The notification-bar form flow for one form and one notification.
 *
 * A form of 1..8 plain string fields (including the single text field) is collected one field at a
 * time: Reply stores the text locally and advances, Back re-opens the previous field without losing
 * the other values, Skip omits an optional field, Cancel drops the local interaction without answering
 * or cancelling the server form. Only a Reply/Skip on the last field composes the whole map; a missing
 * required value blocks the send. Defaults are kept for untouched fields. The user's text is stored
 * and sent verbatim (only the required-field emptiness check trims); unknown keys can never enter the
 * map because it is built from the form's fields. OpenCode question fields may carry suggestion
 * options: a field is free text when it has no options, or when it explicitly sets `custom:true`;
 * options without that permission are a closed choice and stay on the app/fallback path.
 *
 * Before the send the pending state is read again. A failed read keeps the draft with [CheckFailed] and
 * sends nothing (Retry is explicit). A form that is gone dismisses the notification; a form that is no
 * longer inline-answerable falls back; a changed raw schema (options/custom/pattern/required/default
 * or any other answering constraint) never sends the old map and restarts at the first field
 * ([SchemaChanged]). A 3xx/4xx is [SendFailed] (not applied), while 0/5xx is [ResultUnknown] (may have
 * been applied) and is never retried automatically. Success removes the draft; the caller removes the
 * notification.
 *
 * All begin/act calls for one notification id are serialized by [store], so several receivers and
 * duplicate broadcasts cannot send twice on the same notification; different notifications proceed
 * in parallel. Actions are additionally fenced by [InlineFormKey] + random token + generation, and a
 * new form on the same notification id replaces the old draft.
 *
 * Applying a projection to a notification is the caller's job and must still pass the existing
 * [NotificationRevisionGate] with the revision its PendingIntent carried. [InlineFormProjection.token]
 * is a business token, not a notification revision; the two are deliberately separate.
 */
class InlineNotificationFormController(
    private val transport: NotificationFormTransport,
    private val key: InlineFormKey,
    private val store: InlineNotificationFormStore = InlineNotificationFormStore.shared,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
    /**
     * One act/begin/resume must finish within this budget. It bounds a pending read (which is then
     * clearly "not sent") and a send (which becomes [InlineFormStatus.ResultUnknown] if started).
     */
    private val budgetMillis: Long = DEFAULT_BUDGET_MS,
) {
    /**
     * Reads the real pending state and shows the first unfilled field (or keeps a live draft when the
     * same form/schema is still pending). Never submits anything. This is also the process-loss path:
     * no answer can be restored, so the flow starts again from field one.
     */
    suspend fun begin(): InlineFormProjection =
        store.serialized(key.notificationId) {
            try {
                withTimeout(budget()) { beginLocked(transport.pending()) }
            } catch (_: TimeoutCancellationException) {
                // The read timed out: nothing was sent, so the draft is a check failure, never Gone.
                beginLocked(null)
            }
        }

    /**
     * [begin] with a pending snapshot the caller already read (the notification sink fetches it to
     * classify the form). A null snapshot means the read failed; it is never treated as Gone.
     */
    suspend fun begin(pending: JsonObject?): InlineFormProjection = store.serialized(key.notificationId) { beginLocked(pending) }

    /**
     * Applies one notification action and returns what to show. An [InlineFormOutcome.Ignored] result
     * has no side effect; if the draft was lost (process death) the caller may [begin] again to re-read
     * the pending form, but must never replay the old answers.
     */
    suspend fun act(action: InlineFormAction): InlineFormProjection =
        store.serialized(key.notificationId) {
            try {
                withTimeout(budget()) { actLocked(action) }
            } catch (_: TimeoutCancellationException) {
                // A send sets ResultUnknown before it starts; a pending check sets CheckFailed before
                // it starts. Rendering the draft keeps Retry/Cancel available in either case.
                afterTimeout()
            }
        }

    /**
     * The recovery entry of a "Sending…"/"Checking…" notification that survived process death or a
     * timeout. It re-reads pending but never submits: an existing draft keeps its step/status (an
     * unknown result stays unknown with Retry), and a lost draft starts again at field one.
     */
    suspend fun resume(): InlineFormProjection =
        store.serialized(key.notificationId) {
            try {
                withTimeout(budget()) { resumeLocked() }
            } catch (_: TimeoutCancellationException) {
                val live = store.find(key.notificationId)
                if (live == null) {
                    // The check itself timed out and there is no draft yet: no send happened.
                    beginLocked(null)
                } else {
                    if (live.key == key) store.touch(live)
                    if (live.key == key) render(live) else ignored()
                }
            }
        }

    /**
     * The user swiped a Sending/Checking notification. Drop this form's draft if it still owns the id;
     * another form's draft is untouched. Never sends.
     */
    suspend fun dismiss(): InlineFormProjection =
        store.serialized(key.notificationId) {
            val draft = store.find(key.notificationId) ?: return@serialized ignored()
            if (draft.key != key || draft.sending) return@serialized ignored()
            store.remove(draft)
            InlineFormProjection(InlineFormOutcome.Dismissed, InlineFormStatus.Cancelled)
        }

    private fun beginLocked(pending: JsonObject?): InlineFormProjection {
        val live = store.find(key.notificationId)
        val current = live?.takeIf { it.key == key }
        if (pending == null) {
            // A failed read by an old identity must not create a draft over a newer form's live one.
            if (live != null && current == null) return ignored()
            val draft =
                current
                    ?: store.newDraft(key, newToken(), form = null, status = InlineFormStatus.CheckFailed)
                    ?: return fallback()
            // A failed read never downgrades an unknown send result and never clears the draft.
            if (draft.status != InlineFormStatus.ResultUnknown) draft.status = InlineFormStatus.CheckFailed
            store.touch(draft)
            return render(draft)
        }

        val fresh = PendingForms.inlineFormSpec(pending, key.sessionId, key.formId)
        if (fresh == null) {
            // Only this key's draft can be discarded here. A newer form may already own the same
            // notification id (a stale read of form A must not delete form B's live draft, nor tell
            // the caller to remove the notification that now shows B).
            if (current != null) {
                if (current.sending) return ignored()
                store.remove(current)
            } else if (live != null) {
                return ignored()
            }
            return if (PendingForms.classify(pending, key.sessionId, key.formId) == null) gone() else fallback()
        }

        if (current == null) {
            val draft =
                store.newDraft(key, newToken(), fresh.fields, InlineFormStatus.Editing, schema = fresh.schema)
                    ?: return fallback()
            return render(draft)
        }

        if (current.form == null) {
            initialize(current, fresh)
            current.bump()
            store.touch(current)
            return render(current)
        }

        if (current.schema != fresh.schema) {
            restart(current, fresh, InlineFormStatus.SchemaChanged)
            current.bump()
            store.touch(current)
            return render(current)
        }

        // Same schema: refresh display data, drop anything not in the form, keep staged answers.
        val form = fresh.fields
        current.form = form
        current.schema = fresh.schema
        prune(current, form)
        // A final answer whose pre-send check failed needs the explicit Retry; a mid-edit failure can
        // simply continue editing. Never turn either into an automatic send.
        if (current.status == InlineFormStatus.CheckFailed && !current.pendingSend) {
            current.status = InlineFormStatus.Editing
            current.bump()
        }
        current.index = current.index.coerceIn(0, form.fields.lastIndex)
        store.touch(current)
        return render(current)
    }

    private suspend fun actLocked(action: InlineFormAction): InlineFormProjection {
        val draft = store.find(key.notificationId) ?: return ignored()
        if (draft.key != key || draft.token != action.token || draft.generation != action.generation) return ignored()
        store.touch(draft)
        return when (val command = action.command) {
            is InlineFormCommand.Reply -> reply(draft, command.text)
            InlineFormCommand.Back -> back(draft)
            InlineFormCommand.Skip -> skip(draft)
            InlineFormCommand.Cancel -> {
                store.remove(draft)
                InlineFormProjection(InlineFormOutcome.Dismissed, InlineFormStatus.Cancelled)
            }
            InlineFormCommand.Retry -> retry(draft)
        }
    }

    /**
     * Checks pending without sending. A lost draft starts from the first field; a live draft of this
     * key only refreshes its form and keeps its status (in particular ResultUnknown), so the user still
     * has to choose Retry explicitly.
     */
    private suspend fun resumeLocked(): InlineFormProjection {
        val live = store.find(key.notificationId)
        val current = live?.takeIf { it.key == key }
        // Another form owns this notification id: never restart, redraw or replace it.
        if (live != null && current == null) return ignored()
        if (current == null) return beginLocked(transport.pending())
        val pending = transport.pending()
        // A failed read keeps the draft and its status; nothing is known about the form.
        if (pending == null) {
            store.touch(current)
            return render(current)
        }
        val fresh = PendingForms.inlineFormSpec(pending, key.sessionId, key.formId)
        if (fresh == null) {
            if (current.sending) return ignored()
            store.remove(current)
            return if (PendingForms.classify(pending, key.sessionId, key.formId) == null) gone() else fallback()
        }
        if (current.form == null) {
            initialize(current, fresh)
            store.touch(current)
            return render(current)
        }
        if (current.schema != fresh.schema) {
            // The pending form changed: the old staged answers cannot be reused, and nothing is sent.
            restart(current, fresh, InlineFormStatus.SchemaChanged)
            store.touch(current)
            return render(current)
        }
        current.form = fresh.fields
        current.schema = fresh.schema
        prune(current, fresh.fields)
        current.index = current.index.coerceIn(0, fresh.fields.fields.lastIndex)
        store.touch(current)
        return render(current)
    }

    private suspend fun reply(
        draft: InlineNotificationFormDraft,
        text: String,
    ): InlineFormProjection {
        if (awaitingExplicitRetry(draft)) return ignored()
        val form = draft.form ?: return ignored()
        val field = form.fields.getOrNull(draft.index) ?: return ignored()
        draft.bump()
        draft.pendingSend = false
        draft.status = InlineFormStatus.Editing
        // An empty input means "no new text": a default (if any) stays available. Everything else is
        // kept exactly as typed, including whitespace.
        if (text.isEmpty()) draft.values.remove(field.key) else draft.values[field.key] = text
        draft.skipped.remove(field.key)
        val current = draft.values[field.key] ?: field.defaultValue
        if (field.required && current.isNullOrBlank()) {
            draft.status = InlineFormStatus.RequiredMissing
            return render(draft)
        }
        if (draft.index < form.fields.lastIndex) {
            draft.index += 1
            return render(draft)
        }
        val answers = compose(draft)
        if (PendingForms.missingRequiredFields(form, answers).isNotEmpty()) {
            draft.status = InlineFormStatus.RequiredMissing
            return render(draft)
        }
        draft.pendingSend = true
        return checkAndSend(draft)
    }

    private fun back(draft: InlineNotificationFormDraft): InlineFormProjection {
        if (awaitingExplicitRetry(draft)) return ignored()
        if (draft.form == null || draft.index <= 0) return ignored()
        draft.bump()
        draft.index -= 1
        draft.status = InlineFormStatus.Editing
        draft.pendingSend = false
        return render(draft)
    }

    private suspend fun skip(draft: InlineNotificationFormDraft): InlineFormProjection {
        if (awaitingExplicitRetry(draft)) return ignored()
        val form = draft.form ?: return ignored()
        val field = form.fields.getOrNull(draft.index) ?: return ignored()
        draft.bump()
        if (field.required) {
            draft.status = InlineFormStatus.RequiredMissing
            return render(draft)
        }
        draft.status = InlineFormStatus.Editing
        draft.pendingSend = false
        draft.skipped.add(field.key)
        draft.values.remove(field.key)
        if (draft.index < form.fields.lastIndex) {
            draft.index += 1
            return render(draft)
        }
        val answers = compose(draft)
        if (PendingForms.missingRequiredFields(form, answers).isNotEmpty()) {
            draft.status = InlineFormStatus.RequiredMissing
            return render(draft)
        }
        draft.pendingSend = true
        return checkAndSend(draft)
    }

    private suspend fun retry(draft: InlineNotificationFormDraft): InlineFormProjection {
        if (draft.status != InlineFormStatus.CheckFailed && draft.status != InlineFormStatus.ResultUnknown) {
            return ignored()
        }
        draft.bump()
        val pending = transport.pending()
        if (pending == null) {
            // A failed read is not evidence about the request: keep CheckFailed or ResultUnknown as is.
            // In particular, an unknown result must not degrade into "nothing was sent".
            return render(draft)
        }
        val fresh = PendingForms.inlineFormSpec(pending, key.sessionId, key.formId)
        if (fresh == null) {
            store.remove(draft)
            return if (PendingForms.classify(pending, key.sessionId, key.formId) == null) gone() else fallback()
        }
        val current = draft.form
        if (current == null) {
            // The initial read had failed; this successful read shows the first field, never a send.
            initialize(draft, fresh)
            return render(draft)
        }
        if (draft.schema != fresh.schema) {
            restart(draft, fresh, InlineFormStatus.SchemaChanged)
            return render(draft)
        }
        draft.form = fresh.fields
        draft.schema = fresh.schema
        prune(draft, fresh.fields)
        if (!draft.pendingSend) {
            draft.status = InlineFormStatus.Editing
            return render(draft)
        }
        val answers = compose(draft)
        if (PendingForms.missingRequiredFields(fresh.fields, answers).isNotEmpty()) {
            draft.pendingSend = false
            draft.status = InlineFormStatus.RequiredMissing
            return render(draft)
        }
        return send(draft, answers)
    }

    /** Re-reads pending, refuses a changed schema, then sends the whole map once. */
    private suspend fun checkAndSend(draft: InlineNotificationFormDraft): InlineFormProjection {
        draft.form ?: return ignored()
        // Tentative: if this read times out (or fails), nothing was sent and Retry is the next step.
        draft.status = InlineFormStatus.CheckFailed
        val pending = transport.pending()
        if (pending == null) {
            // Temporary failure, not "not delivered": keep the draft, send nothing, offer Retry.
            draft.status = InlineFormStatus.CheckFailed
            return render(draft)
        }
        val fresh = PendingForms.inlineFormSpec(pending, key.sessionId, key.formId)
        if (fresh == null) {
            store.remove(draft)
            return if (PendingForms.classify(pending, key.sessionId, key.formId) == null) gone() else fallback()
        }
        if (draft.schema != fresh.schema) {
            // Never send a map built for another schema.
            restart(draft, fresh, InlineFormStatus.SchemaChanged)
            return render(draft)
        }
        draft.form = fresh.fields
        draft.schema = fresh.schema
        prune(draft, fresh.fields)
        val answers = compose(draft)
        if (PendingForms.missingRequiredFields(fresh.fields, answers).isNotEmpty()) {
            draft.pendingSend = false
            draft.status = InlineFormStatus.RequiredMissing
            return render(draft)
        }
        return send(draft, answers)
    }

    /** The one place a form answer leaves the process. The response body is never carried anywhere. */
    private suspend fun send(
        draft: InlineNotificationFormDraft,
        answers: Map<String, String>,
    ): InlineFormProjection {
        draft.sending = true
        // Tentative until a definite result arrives. If the coroutine is cancelled or the timeout
        // fires while the request is in flight, the answer may have been applied, so it must stay
        // ResultUnknown and must never be retried automatically.
        draft.status = InlineFormStatus.ResultUnknown
        val result =
            try {
                transport.send(NotificationActions.formReply(key.sessionId, key.formId, answers), key.directory)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The send had already started: it is unknown, not "nothing was sent". Keep pendingSend
                // so only an explicit Retry (which checks first) may send again.
                draft.status = InlineFormStatus.ResultUnknown
                throw e
            } finally {
                // A send may take longer than the TTL; refresh before clearing the in-flight flag so a
                // concurrent find can never observe an old timestamp with sending already false.
                store.touch(draft)
                draft.sending = false
            }
        return when (ActionDecisions.outcomeOf(result.status)) {
            SendOutcome.Sent -> {
                store.remove(draft)
                InlineFormProjection(InlineFormOutcome.Sent, InlineFormStatus.Sent)
            }
            SendOutcome.Failed -> {
                // Applied by nobody: the draft stays for an explicit edit/retry, never an automatic resend.
                draft.pendingSend = false
                draft.status = InlineFormStatus.SendFailed
                render(draft)
            }
            SendOutcome.Unknown -> {
                // May have been applied. Keep the draft and require an explicit Retry (which checks first).
                draft.status = InlineFormStatus.ResultUnknown
                render(draft)
            }
        }
    }

    /** The whole map from the current form only: defaults for untouched fields, skips omitted. */
    private fun compose(draft: InlineNotificationFormDraft): Map<String, String> {
        val form = draft.form ?: return emptyMap()
        return buildMap {
            form.fields.forEach { field ->
                if (field.key in draft.skipped) return@forEach
                val value = draft.values[field.key] ?: field.defaultValue ?: return@forEach
                put(field.key, value)
            }
        }
    }

    private fun render(draft: InlineNotificationFormDraft): InlineFormProjection {
        val form = draft.form
        val field = form?.fields?.getOrNull(draft.index)
        return InlineFormProjection(
            outcome = InlineFormOutcome.Render,
            status = draft.status,
            token = draft.token,
            generation = draft.generation,
            index = if (form == null) 0 else draft.index,
            count = form?.fields?.size ?: 0,
            formTitle = form?.title?.takeIf { it.isNotBlank() },
            title = field?.title,
            description = field?.description,
            actions = actionsFor(draft, field?.required != false),
        )
    }

    private fun actionsFor(
        draft: InlineNotificationFormDraft,
        required: Boolean,
    ): Set<InlineFormActionKind> =
        when (draft.status) {
            InlineFormStatus.CheckFailed, InlineFormStatus.ResultUnknown -> setOf(InlineFormActionKind.Retry, InlineFormActionKind.Cancel)
            else ->
                buildSet {
                    add(InlineFormActionKind.Reply)
                    if (draft.index > 0) add(InlineFormActionKind.Back)
                    if (!required) add(InlineFormActionKind.Skip)
                    add(InlineFormActionKind.Cancel)
                }
        }

    private fun initialize(
        draft: InlineNotificationFormDraft,
        spec: InlineFormSpec,
    ) {
        draft.form = spec.fields
        draft.schema = spec.schema
        draft.index = 0
        draft.status = InlineFormStatus.Editing
        draft.pendingSend = false
        draft.values.clear()
        draft.skipped.clear()
    }

    private fun restart(
        draft: InlineNotificationFormDraft,
        spec: InlineFormSpec,
        status: InlineFormStatus,
    ) {
        initialize(draft, spec)
        draft.status = status
    }

    private fun prune(
        draft: InlineNotificationFormDraft,
        form: FormAnswer.Fields,
    ) {
        val keys = form.fields.map { it.key }.toSet()
        draft.values.keys.retainAll(keys)
        draft.skipped.retainAll(keys)
    }

    private fun ignored(): InlineFormProjection = InlineFormProjection(InlineFormOutcome.Ignored, InlineFormStatus.Ignored)

    /** A timeout kept whatever status the interrupted step had already recorded (safe/actionable). */
    private fun afterTimeout(): InlineFormProjection {
        val draft = store.find(key.notificationId) ?: return ignored()
        if (draft.key != key) return ignored()
        store.touch(draft)
        return render(draft)
    }

    private fun budget(): Long = budgetMillis.coerceAtLeast(1)

    /** After a failed check or an unknown result, only the explicit Retry (and Cancel) may act. */
    private fun awaitingExplicitRetry(draft: InlineNotificationFormDraft): Boolean =
        draft.status == InlineFormStatus.CheckFailed || draft.status == InlineFormStatus.ResultUnknown

    private fun gone(): InlineFormProjection = InlineFormProjection(InlineFormOutcome.Gone, InlineFormStatus.Gone)

    private fun fallback(): InlineFormProjection = InlineFormProjection(InlineFormOutcome.Fallback, InlineFormStatus.Unavailable)

    private companion object {
        /** Bounded so one broadcast (pending + one POST) finishes inside the receiver's 8 s budget. */
        const val DEFAULT_BUDGET_MS = 6_500L
    }
}
