package io.andchamber.app.notify

import io.andchamber.app.data.ApiResult
import io.andchamber.app.data.LocalApiClient
import kotlinx.serialization.json.JsonObject

/** The pending/send pair the form screen needs. Production wires [LocalApiClient]; tests use a fake. */
interface NotificationFormTransport {
    suspend fun pending(): JsonObject?

    suspend fun send(
        request: ApiRequest,
        directory: String?,
    ): ApiResult
}

/** Adapts the production client to [NotificationFormTransport] without touching [LocalApiClient]. */
class LocalApiNotificationFormTransport(
    private val api: LocalApiClient,
) : NotificationFormTransport {
    override suspend fun pending(): JsonObject? = api.pending()

    override suspend fun send(
        request: ApiRequest,
        directory: String?,
    ): ApiResult = api.send(request, directory)
}

/** What the form screen found when it opened. */
sealed interface NotificationFormLoad {
    /** The form is still pending and this notification can fill it. */
    data class Ready(val form: FormAnswer.Fields) : NotificationFormLoad

    /** The read worked and the form is no longer pending. */
    data object Gone : NotificationFormLoad

    /** The pending read failed; nothing is known about the form. */
    data object CheckFailed : NotificationFormLoad

    /** The form is still pending but a notification cannot fill it. */
    data object Unavailable : NotificationFormLoad
}

/**
 * The step the form screen applies after a submit. [reason] is only a safe local label (the first
 * missing required field's title); a failed request's body is never carried here, since it may echo
 * the submitted answer or other sensitive text.
 */
data class NotificationFormSubmission(
    val step: ActionStep,
    val reason: String?,
)

/**
 * Loads one pending form and submits its whole answer map once. OpenCode 2.0.32 validates a form's
 * answer map in one request, so a field cannot be submitted on its own; every value is sent together
 * and never retried automatically.
 *
 * The controller only decides; the activity renders [NotificationFormLoad] and applies
 * [NotificationFormSubmission]. It is pure apart from [transport], so the rules are JVM-tested.
 */
class NotificationFormController(
    private val transport: NotificationFormTransport,
    private val sessionId: String,
    private val directory: String?,
    private val formId: String,
) {
    /**
     * Reads the pending state. A failed read is [NotificationFormLoad.CheckFailed], never
     * [NotificationFormLoad.Gone]: an unknown state must not tell the user the form was answered.
     */
    suspend fun load(): NotificationFormLoad {
        val pending = transport.pending() ?: return NotificationFormLoad.CheckFailed
        return when (val answer = PendingForms.classify(pending, sessionId, formId)) {
            null -> NotificationFormLoad.Gone
            is FormAnswer.Fields -> NotificationFormLoad.Ready(answer)
            is FormAnswer.Choice, is FormAnswer.Text, FormAnswer.OpenOnly -> NotificationFormLoad.Unavailable
        }
    }

    /**
     * Answers the form once. Values are limited to the form's fields, trimmed, and blank values are
     * dropped. A missing required value keeps the screen open and sends nothing. Otherwise the send
     * reuses the notification rules: a fresh pending check before sending, and after a refusal or an
     * unknown result a second check that can remove the form but never resend it.
     */
    suspend fun submit(
        form: FormAnswer.Fields,
        values: Map<String, String>,
    ): NotificationFormSubmission {
        val answers = sanitized(form, values)
        val missing = PendingForms.missingRequiredFields(form, answers)
        if (missing.isNotEmpty()) {
            return NotificationFormSubmission(ActionStep.KeepSendFailed, labelFor(form, missing.first()))
        }
        val before = ActionDecisions.beforeSend(ActionDecisions.checkForm(transport.pending(), sessionId, formId))
        if (before != ActionStep.Send) return NotificationFormSubmission(before, null)
        val result = transport.send(NotificationActions.formReply(sessionId, formId, answers), directory)
        val outcome = ActionDecisions.outcomeOf(result.status)
        if (outcome == SendOutcome.Sent) return NotificationFormSubmission(ActionStep.Cancel, null)
        val after = ActionDecisions.checkForm(transport.pending(), sessionId, formId)
        // Never echo result.body: it may contain the answer or other sensitive text. The screen shows
        // a fixed status instead; only the locally computed required-field title is a reason.
        return NotificationFormSubmission(ActionDecisions.afterButton(outcome, after), null)
    }

    /** The reason is the first missing field's title, or its key when the title is blank. */
    private fun labelFor(
        form: FormAnswer.Fields,
        key: String,
    ): String = form.fields.firstOrNull { it.key == key }?.title?.takeIf { it.isNotBlank() } ?: key

    companion object {
        /** Only the form's own fields, trimmed, with blank values omitted. Unknown keys are dropped. */
        fun sanitized(
            form: FormAnswer.Fields,
            values: Map<String, String>,
        ): Map<String, String> =
            form.fields.mapNotNull { field ->
                values[field.key]?.trim()?.takeIf { it.isNotEmpty() }?.let { value -> field.key to value }
            }.toMap()
    }
}
