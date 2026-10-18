package io.andchamber.app.notify

import io.andchamber.app.notify.NotificationPayload.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What the notification can answer for one pending form. The real OpenCode 2.0.32 schema (form.js):
 * a form has `id`, `sessionID`, `title`, and a non-empty `fields` array. A single choice is a `string`
 * field carrying `options: [{value,label,description?}]`; there is no select type. Multi-field forms
 * and other field types are never answerable from the notification.
 */
sealed interface FormAnswer {
    /** One field, one answer: tap one of these buttons (choice with at most three options). */
    data class Choice(
        val formId: String,
        val sessionId: String,
        val fieldKey: String,
        val options: List<Pair<String, String>>,
    ) : FormAnswer

    /** One free-text field: answer with RemoteInput. */
    data class Text(
        val formId: String,
        val sessionId: String,
        val fieldKey: String,
    ) : FormAnswer

    /** Anything else: the notification only opens the app. */
    data object OpenOnly : FormAnswer
}

object PendingForms {
    /** Buttons are only offered for choices that fit a notification. */
    const val MAX_CHOICE_BUTTONS = 3

    /**
     * Finds [formId] among the session's pending forms and classifies it. Null when the form is no
     * longer pending (it was answered or cancelled elsewhere).
     */
    fun classify(
        pending: JsonObject,
        sessionId: String,
        formId: String,
    ): FormAnswer? {
        val forms = (pending[sessionId] as? JsonObject)?.get("forms") as? JsonArray ?: return null
        val form = forms.filterIsInstance<JsonObject>().firstOrNull { it.text("id") == formId } ?: return null
        return classifyForm(form)
    }

    /** The session's only pending form, or null when there are none or several (ambiguous). */
    fun singleFormFor(
        pending: JsonObject,
        sessionId: String,
    ): JsonObject? {
        val forms = (pending[sessionId] as? JsonObject)?.get("forms") as? JsonArray ?: return null
        return (forms.singleOrNull() as? JsonObject)
    }

    /** Classifies a single raw form object. Pure, so the rules are tested directly. */
    fun classifyForm(form: JsonObject): FormAnswer {
        val formId = form.text("id").orEmpty()
        val sessionId = form.text("sessionID").orEmpty()
        if (formId.isBlank() || sessionId.isBlank() || sessionId == "global") return FormAnswer.OpenOnly
        val fields = form["fields"] as? JsonArray ?: return FormAnswer.OpenOnly
        // Only a single-field form is answerable from a notification.
        if (fields.size != 1) return FormAnswer.OpenOnly
        val field = fields[0] as? JsonObject ?: return FormAnswer.OpenOnly
        val key = field.text("key")?.takeIf { it.isNotBlank() } ?: return FormAnswer.OpenOnly
        // Conditional or hidden fields need the rest of the form; leave those to the app.
        if (field.containsKey("when") || field["hidden"] == JsonPrimitive(true)) return FormAnswer.OpenOnly
        if (field.text("type") != "string") return FormAnswer.OpenOnly
        val options = field["options"] as? JsonArray
        return when {
            options == null || options.isEmpty() -> FormAnswer.Text(formId, sessionId, key)
            options.size <= MAX_CHOICE_BUTTONS -> {
                val pairs = options.filterIsInstance<JsonObject>().mapNotNull { option ->
                    val value = option.text("value")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    value to (option.text("label")?.takeIf { it.isNotBlank() } ?: value)
                }
                if (pairs.size == options.size) FormAnswer.Choice(formId, sessionId, key, pairs) else FormAnswer.OpenOnly
            }
            else -> FormAnswer.OpenOnly
        }
    }

    /**
     * The `answer` body the form reply route expects: `{ "answer": { <fieldKey>: <value> } }`. The key is
     * the field's key (Form.Reply is a record, not a bare value).
     */
    fun answerBody(
        fieldKey: String,
        value: String,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("answer") { put(fieldKey, value) }
        }
}
