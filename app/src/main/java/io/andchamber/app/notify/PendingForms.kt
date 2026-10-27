package io.andchamber.app.notify

import io.andchamber.app.notify.NotificationPayload.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

/**
 * The inline path's accepted fields and a canonical fingerprint of their raw answering constraints.
 * The fingerprint covers every raw key of each field except the display-only title/description/
 * placeholder, so a pattern, custom permission, option list, required flag, default or any future
 * constraint change is detected before a map collected under the old schema can be sent.
 */
internal data class InlineFormSpec(
    val fields: FormAnswer.Fields,
    val schema: String,
)

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
     * The inline notification path's classification. OpenCode v2.0.32 question fields normally carry
     * `type:"string"`, an `options` array and `custom:true`; a custom field is free text even when it
     * has suggestion options (q0 with three values to merge is one such field). The rules are:
     *
     *  - no `options` key: free text;
     *  - `options` present: free text only when `custom` is explicitly JSON `true`;
     *  - `options` present without explicit `custom:true`: the notification path cannot know whether
     *    the answer may be arbitrary, so it refuses inline and keeps [classifyForm]'s Choice/OpenOnly
     *    fallback.
     *
     * An illegal `options` type or illegal `custom` type, a duplicate field key, `when` or a hidden
     * field is refused. [classifyForm]'s existing Fields classification is not widened here.
     */
    fun inlineForm(form: JsonObject): FormAnswer.Fields? = inlineFormSpec(form)?.fields

    /** Finds [formId] among the session's pending forms for the inline notification path. */
    fun inlineForm(
        pending: JsonObject,
        sessionId: String,
        formId: String,
    ): FormAnswer.Fields? = inlineFormSpec(pending, sessionId, formId)?.fields

    /** [inlineForm] plus the raw-schema fingerprint the send fence compares. */
    internal fun inlineFormSpec(form: JsonObject): InlineFormSpec? {
        val formId = form.text("id").orEmpty()
        val sessionId = form.text("sessionID").orEmpty()
        if (formId.isBlank() || sessionId.isBlank() || sessionId == "global") return null
        val fields = form["fields"] as? JsonArray ?: return null
        if (fields.size !in 1..MAX_FORM_FIELDS) return null
        return parseInlineFields(formId, sessionId, form.text("title").orEmpty(), fields)
    }

    internal fun inlineFormSpec(
        pending: JsonObject,
        sessionId: String,
        formId: String,
    ): InlineFormSpec? {
        val forms = (pending[sessionId] as? JsonObject)?.get("forms") as? JsonArray ?: return null
        val form = forms.filterIsInstance<JsonObject>().firstOrNull { it.text("id") == formId } ?: return null
        // The reply is addressed to [sessionId]; a form claiming another session is not answerable here.
        if (form.text("sessionID") != sessionId) return null
        return inlineFormSpec(form)
    }

    /**
     * Parses the inline path's 1..[MAX_FORM_FIELDS] string fields. Unlike the form-screen parser, an
     * options array is allowed only when the field explicitly permits custom input. Invalid option or
     * custom types are refused rather than coerced.
     */
    private fun parseInlineFields(
        formId: String,
        sessionId: String,
        title: String,
        fields: JsonArray,
    ): InlineFormSpec? {
        val parsed = ArrayList<FormTextField>(fields.size)
        for (raw in fields) {
            val field = raw as? JsonObject ?: return null
            val key = field.text("key")?.takeIf { it.isNotBlank() } ?: return null
            if (field.containsKey("when") || field["hidden"] == JsonPrimitive(true)) return null
            if (field.text("type") != "string") return null
            val custom =
                when (val present = field["custom"]) {
                    null -> false
                    is JsonPrimitive -> {
                        if (present.isString) return null
                        present.booleanOrNull ?: return null
                    }
                    else -> return null
                }
            if (field.containsKey("options")) {
                field["options"] as? JsonArray ?: return null
                // Options without explicit custom:true mean the answer must be one of the choices.
                // Empty or suggestion-only options are fine for a custom free-text field; the array
                // does not constrain the typed value.
                if (!custom) return null
            }
            parsed +=
                FormTextField(
                    key = key,
                    title = field.text("title")?.takeIf { it.isNotBlank() } ?: key,
                    description = field.text("description")?.takeIf { it.isNotBlank() },
                    required = field["required"] == JsonPrimitive(true),
                    placeholder = field.text("placeholder")?.takeIf { it.isNotBlank() },
                    defaultValue = field.text("default")?.takeIf { it.isNotBlank() },
                )
        }
        if (parsed.map { it.key }.distinct().size != parsed.size) return null
        return InlineFormSpec(
            fields = FormAnswer.Fields(formId, sessionId, title, parsed),
            schema = inlineSchema(fields),
        )
    }

    /**
     * The single-choice subset the native conversation may answer: one string field with
     * 1..[MAX_CHOICE_BUTTONS] options. OpenCode 2.0.32's built-in questions always carry
     * `custom:true`, but that only allows free text; the overlay deliberately offers no free-text
     * input and submits one of the listed option values only.
     */
    internal fun closedChoice(form: JsonObject): FormAnswer.Choice? = classifyForm(form) as? FormAnswer.Choice

    /**
     * The same raw-schema fingerprint for a classified form, used by the native conversation
     * projection so a changed choice set or constraint invalidates an in-flight answer. Metadata
     * (including the tool/message id) is part of the digest; the raw fields and metadata are never
     * stored or logged.
     */
    internal fun formSchemaFingerprint(form: JsonObject): String? =
        (form["fields"] as? JsonArray)?.let { fields ->
            val fieldSchema = runCatching { inlineSchema(fields) }.getOrNull() ?: return null
            // Stable identity keys only, never the raw metadata blob: the tool/message id tells one
            // question turn from another without keeping or logging any server text.
            val metadata = form["metadata"] as? JsonObject
            val tool = metadata?.get("tool") as? JsonObject
            val identity =
                listOf(
                    "kind=" + metadata?.text("kind").orEmpty(),
                    "tool=" + tool?.text("id").orEmpty(),
                    "message=" + tool?.text("messageID").orEmpty(),
                ).joinToString(";")
            val canonical = "$fieldSchema\u001D$identity"
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            digest.joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

    /**
     * A stable fingerprint of every raw field constraint except display-only text. It deliberately
     * includes unknown keys too, so a newly added pattern or any constraint the model does not store
     * still invalidates a staged answer instead of being silently ignored.
     */
    private fun inlineSchema(fields: JsonArray): String =
        fields.joinToString(separator = "\u001F") { raw ->
            val field = raw as JsonObject
            field.keys
                .filterNot { it == "title" || it == "description" || it == "placeholder" }
                .sorted()
                .joinToString(separator = "\u001E") { key -> "$key=${field[key]}" }
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
        // An options key is a closed set of choices. An empty array is not free text: without a
        // custom flag the notification cannot know the answer may be arbitrary, so it must open the app.
        // Custom question fields are answered by the inline path; this legacy direct-action classifier
        // is only the fallback for a closed field.
        if (!field.containsKey("options")) return FormAnswer.Text(formId, sessionId, key)
        val options = field["options"] as? JsonArray ?: return FormAnswer.OpenOnly
        return when {
            options.isEmpty() -> FormAnswer.OpenOnly
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
        return parseFields(formId, sessionId, title, fields)
    }

    /** Parses 1..[MAX_FORM_FIELDS] plain text fields; the caller decides the accepted size. */
    private fun parseFields(
        formId: String,
        sessionId: String,
        title: String,
        fields: JsonArray,
    ): FormAnswer.Fields? {
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
