package io.andchamber.app.notify

import io.andchamber.app.notify.NotificationPayload.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One visible text input of a multi-field form. */
data class FormTextField(
    val key: String,
    val title: String,
    val description: String?,
    val required: Boolean,
    val placeholder: String?,
    val defaultValue: String?,
)

/**
 * What the notification can answer for one pending form. The real OpenCode 2.0.32 schema (form.js):
 * a form has `id`, `sessionID`, `title`, and a non-empty `fields` array. A single choice is a `string`
 * field carrying `options: [{value,label,description?}]`; there is no select type.
 *
 * A notification can answer one field directly ([Choice], [Text]) or, for a small form of plain text
 * fields, open a form screen that submits every answer at once ([Fields]). Everything else is
 * [OpenOnly].
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

    /**
     * A small form of 2 to [PendingForms.MAX_FORM_FIELDS] plain string fields. OpenCode validates the
     * whole answer map once, so the form screen submits all fields together; a field cannot be filled
     * in one notification tap after another.
     */
    data class Fields(
        val formId: String,
        val sessionId: String,
        val title: String,
        val fields: List<FormTextField>,
    ) : FormAnswer

    /** Anything else: the notification only opens the app. */
    data object OpenOnly : FormAnswer
}

object PendingForms {
    /** Buttons are only offered for choices that fit a notification. */
    const val MAX_CHOICE_BUTTONS = 3

    /** Above this many fields the form screen is not offered from a notification. */
    const val MAX_FORM_FIELDS = 8

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
        if (fields.size == 1) return classifySingleField(formId, sessionId, fields[0] as? JsonObject)
        return classifyFields(formId, sessionId, form.text("title").orEmpty(), fields) ?: FormAnswer.OpenOnly
    }

    /**
     * A single-field form keeps its direct notification actions. Conditional or hidden fields need
     * the rest of the form, so they stay open-only.
     */
    private fun classifySingleField(
        formId: String,
        sessionId: String,
        field: JsonObject?,
    ): FormAnswer {
        field ?: return FormAnswer.OpenOnly
        val key = field.text("key")?.takeIf { it.isNotBlank() } ?: return FormAnswer.OpenOnly
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
     * A form screen can collect a small set of plain text fields. An option, a conditional rule or a
     * hidden field means the form needs the app's own renderer; more than [MAX_FORM_FIELDS] fields do
     * not fit the notification path. Null means open-only.
     */
    private fun classifyFields(
        formId: String,
        sessionId: String,
        title: String,
        fields: JsonArray,
    ): FormAnswer.Fields? {
        if (fields.size !in 2..MAX_FORM_FIELDS) return null
        val parsed =
            fields.map { raw ->
                val field = raw as? JsonObject ?: return null
                val key = field.text("key")?.takeIf { it.isNotBlank() } ?: return null
                if (field.containsKey("when") || field["hidden"] == JsonPrimitive(true)) return null
                if (field.text("type") != "string") return null
                // A choice in a multi-field form needs a picker, which the form screen does not offer.
                if (field.containsKey("options")) return null
                FormTextField(
                    key = key,
                    title = field.text("title")?.takeIf { it.isNotBlank() } ?: key,
                    description = field.text("description")?.takeIf { it.isNotBlank() },
                    required = field["required"] == JsonPrimitive(true),
                    placeholder = field.text("placeholder")?.takeIf { it.isNotBlank() },
                    defaultValue = field.text("default")?.takeIf { it.isNotBlank() },
                )
            }
        return FormAnswer.Fields(formId, sessionId, title, parsed)
    }

    /**
     * Required keys whose submitted value is missing or blank after trimming. The form screen and
     * [NotificationFormController] both use this before sending anything.
     */
    fun missingRequiredFields(
        form: FormAnswer.Fields,
        values: Map<String, String>,
    ): List<String> =
        form.fields
            .filter { it.required && values[it.key]?.trim().isNullOrEmpty() }
            .map { it.key }

    /** The `answer` body the form reply route expects: `{ "answer": { <fieldKey>: <value> } }`. */
    fun answerBody(answers: Map<String, String>): JsonObject =
        buildJsonObject {
            putJsonObject("answer") {
                answers.forEach { (key, value) -> put(key, value) }
            }
        }

    /**
     * The `answer` body the form reply route expects, for one field: `{ "answer": { <fieldKey>: <value> } }`.
     * The key is the field's key (Form.Reply is a record, not a bare value).
     */
    fun answerBody(
        fieldKey: String,
        value: String,
    ): JsonObject = answerBody(mapOf(fieldKey to value))
}
