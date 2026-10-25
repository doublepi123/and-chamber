package io.andchamber.app.notify

import io.andchamber.app.notify.NotificationPayload.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The request body for a notification action: method, path and optional JSON body. */
data class ApiRequest(
    val method: String,
    val path: String,
    val body: JsonObject?,
)

/**
 * Request builders for each notification action. Pure, so the exact routes and bodies are tested
 * against the verified OpenCode 2.0.32 contract.
 */
object NotificationActions {
    /** POST /api/session/:sid/prompt, body `{ "text": ... }` (PromptInput.Prompt needs only text). */
    fun sendReply(sessionId: String, text: String): ApiRequest =
        ApiRequest(
            "POST",
            "/api/session/${encodeSegment(sessionId)}/prompt",
            buildJsonObject { put("text", text) },
        )

    /**
     * POST /api/session/:sid/permission/:rid/reply, body `{ "decision": once|always|reject }`.
     * The decision values are OpenCode's `Permission.Reply` literals.
     */
    fun permissionReply(sessionId: String, requestId: String, decision: PermissionDecision): ApiRequest =
        ApiRequest(
            "POST",
            "/api/session/${encodeSegment(sessionId)}/permission/${encodeSegment(requestId)}/reply",
            buildJsonObject { put("decision", decision.wire) },
        )

    /** POST /api/session/:sid/form/:fid/reply, body `{ "answer": { <fieldKey>: value } }`. */
    fun formReply(
        sessionId: String,
        formId: String,
        fieldKey: String,
        value: String,
    ): ApiRequest = formReply(sessionId, formId, mapOf(fieldKey to value))

    /**
     * POST /api/session/:sid/form/:fid/reply with the whole answer map. OpenCode validates a form's
     * answer once, so every field must be present in this one request.
     */
    fun formReply(
        sessionId: String,
        formId: String,
        answers: Map<String, String>,
    ): ApiRequest =
        ApiRequest(
            "POST",
            "/api/session/${encodeSegment(sessionId)}/form/${encodeSegment(formId)}/reply",
            PendingForms.answerBody(answers),
        )

    /** Path segments are opaque ids; keep them safe in the URL. */
    fun encodeSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}

enum class PermissionDecision(val wire: String) {
    Once("once"),
    Always("always"),
    Reject("reject"),
}

/** What a pending permission looks like to the notification, from `pending[sid].permissions[]`. */
data class PendingPermission(
    val id: String,
    val action: String,
    val resources: List<String>,
) {
    /** Body line: what it wants to do, e.g. "bash: npm test". */
    fun summary(): String {
        val target = resources.firstOrNull().orEmpty()
        return listOf(action, target).filter { it.isNotBlank() }.joinToString(": ")
    }

    companion object {
        /** Finds the request by id among a session's pending permissions. Null when not pending. */
        fun find(
            pending: JsonObject,
            sessionId: String,
            requestId: String,
        ): PendingPermission? {
            val permissions = (pending[sessionId] as? JsonObject)?.get("permissions") as? JsonArray ?: return null
            val item = permissions.filterIsInstance<JsonObject>().firstOrNull { it.text("id") == requestId } ?: return null
            val resources =
                (item["resources"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            return PendingPermission(id = requestId, action = item.text("action").orEmpty(), resources = resources)
        }
    }
}
