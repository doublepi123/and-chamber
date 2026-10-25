package io.andchamber.app.notify

import kotlinx.serialization.json.JsonObject

/** What a fresh read of the pending requests says about the one a notification button answers. */
enum class PendingCheck {
    /** Still waiting and still answerable from the notification. */
    Pending,

    /** The read worked and the request is gone: answered or cancelled elsewhere. */
    Gone,

    /** Still waiting, but the form can no longer be answered from a notification; the app can. */
    NotAnswerable,

    /** The read failed (pending() returned null). Nothing is known, so nothing may be removed. */
    Unknown,
}

/** What the receiver does with the notification. Every Keep step leaves the session entry in place. */
enum class ActionStep(
    /** The original buttons and reply field stay. False only when they can no longer answer anything. */
    val keepsActions: Boolean,
) {
    /** Send the answer. */
    Send(true),

    /** Remove the notification: the request is gone. */
    Cancel(false),

    /** Keep it with its buttons: the pending state couldn't be read, so nothing was sent. */
    KeepCheckFailed(true),

    /** Keep it with its buttons: the server refused the request, so it was not applied. */
    KeepSendFailed(true),

    /** Keep it with its buttons: it may or may not have arrived; the user checks the session first. */
    KeepResultUnknown(true),

    /** Keep a notification that opens the session, without answer buttons. */
    KeepOpenOnly(false),
}

/** How a send ended, from its HTTP status (0 means the request never completed or was dropped). */
enum class SendOutcome {
    Sent,

    /** The server answered with a refusal (3xx or 4xx): the request was not applied. */
    Failed,

    /** No answer, or a server error (5xx): it may have been applied. */
    Unknown,
}

/** Where the notification came from, and what the user typed (null for a tapped button). */
data class FailureContext(
    val sessionId: String,
    val directory: String?,
    val draft: String?,
)

/** A notification kept after an action didn't go through. Pure data; OpenChamberNotifier applies it. */
data class KeepPlan(
    val status: String,
    /** Keep the original buttons and reply field. */
    val keepActions: Boolean,
    /** Tapping opens this session, even if the original notification is gone. */
    val sessionId: String,
    val directory: String?,
    /** Shown again in the notification; the app never sends it again by itself. */
    val draft: String?,
)

/**
 * Pure rules for notification buttons. A failed read never removes a notification; only a read that
 * worked and shows the request gone does. Nothing here sends anything twice.
 */
object ActionDecisions {
    fun checkPermission(
        pending: JsonObject?,
        sessionId: String,
        requestId: String,
    ): PendingCheck {
        pending ?: return PendingCheck.Unknown
        return if (PendingPermission.find(pending, sessionId, requestId) != null) PendingCheck.Pending else PendingCheck.Gone
    }

    fun checkForm(
        pending: JsonObject?,
        sessionId: String,
        formId: String,
    ): PendingCheck {
        pending ?: return PendingCheck.Unknown
        return when (PendingForms.classify(pending, sessionId, formId)) {
            null -> PendingCheck.Gone
            is FormAnswer.Choice, is FormAnswer.Text, is FormAnswer.Fields -> PendingCheck.Pending
            FormAnswer.OpenOnly -> PendingCheck.NotAnswerable
        }
    }

    /** Before sending: only a confirmed pending request is answered. */
    fun beforeSend(check: PendingCheck): ActionStep =
        when (check) {
            PendingCheck.Pending -> ActionStep.Send
            PendingCheck.Gone -> ActionStep.Cancel
            PendingCheck.NotAnswerable -> ActionStep.KeepOpenOnly
            PendingCheck.Unknown -> ActionStep.KeepCheckFailed
        }

    /** 2xx sent; 3xx/4xx refused (not applied); 0 or 5xx unknown (may have been applied). */
    fun outcomeOf(status: Int): SendOutcome =
        when (status) {
            in 200..299 -> SendOutcome.Sent
            in 300..499 -> SendOutcome.Failed
            else -> SendOutcome.Unknown
        }

    /** A reply that didn't go through. It is never sent again automatically. */
    fun afterReply(outcome: SendOutcome): ActionStep =
        if (outcome == SendOutcome.Failed) ActionStep.KeepSendFailed else ActionStep.KeepResultUnknown

    /**
     * After a button's send didn't go through, from a second read. Gone: answered meanwhile (maybe by
     * this very request), so it is removed. Otherwise it stays, and nothing is sent again: never Send.
     */
    fun afterButton(
        outcome: SendOutcome,
        check: PendingCheck,
    ): ActionStep =
        when (check) {
            PendingCheck.Gone -> ActionStep.Cancel
            PendingCheck.NotAnswerable -> ActionStep.KeepOpenOnly
            PendingCheck.Pending, PendingCheck.Unknown ->
                if (outcome == SendOutcome.Failed) ActionStep.KeepSendFailed else ActionStep.KeepResultUnknown
        }

    /** The kept notification for [step], or null when it is not a Keep step (Send or Cancel). */
    fun keepPlan(
        step: ActionStep,
        context: FailureContext,
        status: String,
    ): KeepPlan? {
        if (step == ActionStep.Send || step == ActionStep.Cancel) return null
        return KeepPlan(status, step.keepsActions, context.sessionId, context.directory, context.draft?.takeIf { it.isNotBlank() })
    }
}
