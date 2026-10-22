package io.andchamber.app.browser.tool

import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** One validated `/v1/tool` request from the OpenCode plugin. */
data class ToolCall(
    val sessionId: String,
    val callId: String,
    val input: JsonObject,
    /** Transport-only `open` wait, already range-checked. Never part of `input`. */
    val allowWaitMs: Int = 0,
) {
    val action: String get() = input.getValue("action").jsonPrimitive.content
}

/**
 * Pure request/response mapping for the plugin tool server. No page text is ever put into an error
 * message; codes are mapped to fixed strings.
 */
object BrowserToolProtocol {
    const val VERSION = 1
    const val MAX_BODY_BYTES = 65_536
    const val MAX_PARAM_CHARS = 8_192
    const val MAX_SESSION_CHARS = 128
    const val MAX_CALL_ID_CHARS = 256
    const val OPEN_TIMEOUT_MS = 45_000L
    const val ACTION_TIMEOUT_MS = 20_000L
    /** Upper bound for the transport-only `open` allow wait. The server timeout adds it on top. */
    const val MAX_ALLOW_WAIT_MS = 120_000

    /** Placeholder action for a request that failed before its action could be read. */
    const val UNKNOWN_ACTION = "browser.unknown"

    val ACTIONS = setOf("open", "snapshot", "click", "type", "scroll", "capture", "resize")

    private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val json = Json { ignoreUnknownKeys = false }

    fun serverEpoch(launchId: Long): String = "launch-$launchId"

    /** A callID that is already a safe request id is kept; anything else becomes a digest. */
    fun requestId(callId: String): String =
        if (REQUEST_ID_PATTERN.matches(callId)) callId else "c-" + sha256(callId).take(40)

    /** Returns null for anything that does not match the contract's body shape. */
    fun parseToolBody(raw: String): ToolCall? =
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return@runCatching null
            val sessionId = root.string("sessionID")?.takeIf { it.length in 1..MAX_SESSION_CHARS }
                ?: return@runCatching null
            val callId = root.string("callID")?.takeIf { it.length in 1..MAX_CALL_ID_CHARS }
                ?: return@runCatching null
            val input = root["input"] as? JsonObject ?: return@runCatching null
            val action = input["action"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: return@runCatching null
            if (action !in ACTIONS) return@runCatching null
            // Transport-only and optional. Absent is zero; anything else must be a plain integer in
            // range, so a malformed wait is refused instead of being silently ignored.
            val allowWaitMs = when (val raw = root["allowWaitMs"]) {
                null -> 0
                is JsonPrimitive -> raw.takeIf { !it.isString }?.intOrNull
                    ?.takeIf { it in 0..MAX_ALLOW_WAIT_MS }
                    ?: return@runCatching null
                else -> return@runCatching null
            }
            ToolCall(sessionId = sessionId, callId = callId, input = input, allowWaitMs = allowWaitMs)
        }.getOrNull()

    /** Returns null when a parameter cannot be represented (too long, or a nested value). */
    fun parameters(input: JsonObject): Map<String, String>? {
        val params = linkedMapOf<String, String>()
        for ((key, value) in input) {
            if (key == "action") continue
            if (value is JsonNull) continue
            val primitive = value as? JsonPrimitive ?: continue
            val text = primitive.content
            if (text.length > MAX_PARAM_CHARS) return null
            params[key] = text
        }
        return params
    }

    fun toProviderRequest(
        launchId: Long,
        connectionId: String,
        call: ToolCall,
        parameters: Map<String, String>,
    ): ProviderRequest =
        ProviderRequest(
            serverEpoch = serverEpoch(launchId),
            connectionId = connectionId,
            requestId = requestId(call.callId),
            action = "browser." + call.action,
            parameters = parameters,
            directory = "",
            sessionId = call.sessionId,
            // The page load keeps its own budget; an open allow wait is added on top so a person's
            // tap is never cut off by the load deadline.
            timeoutMs = if (call.action == "open") {
                OPEN_TIMEOUT_MS + call.allowWaitMs.toLong()
            } else {
                ACTION_TIMEOUT_MS
            },
            allowWaitMs = call.allowWaitMs,
        )

    fun encodeResult(
        action: String,
        result: ProviderResult,
    ): String =
        buildJsonObject {
            put("version", VERSION)
            put("ok", result.ok)
            put("action", action)
            if (result.ok) {
                put("data", result.data)
            } else {
                val code = result.errorCode ?: ProviderCodes.OUTCOME_UNKNOWN
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", fixedMessage(code))
                    },
                )
            }
        }.toString()

    /**
     * Every envelope carries `version`, `ok` and `action`: the plugin refuses a body without an
     * action, even when it is only reporting an error. [action] is the request's action when it is
     * known; the fixed [UNKNOWN_ACTION] otherwise.
     */
    fun errorBody(
        code: String,
        message: String = fixedMessage(code),
        action: String = UNKNOWN_ACTION,
    ): String =
        buildJsonObject {
            put("version", VERSION)
            put("ok", false)
            put("action", action)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString()

    /** Fixed messages only: a provider message may quote page content. */
    fun fixedMessage(code: String): String =
        when (code) {
            ProviderCodes.BROWSER_PAGE_UNAVAILABLE -> "Open the AndChamber chat screen on the phone."
            ProviderCodes.AUTHORIZATION_REQUIRED -> "The page is not allowed. Tap Allow in the AndChamber browser window."
            ProviderCodes.REF_EXPIRED -> "The element reference expired. Take a new snapshot."
            ProviderCodes.BUSY -> "The browser is busy with another action."
            ProviderCodes.DEADLINE -> "The action timed out."
            ProviderCodes.OUTCOME_UNKNOWN -> "The action's outcome is unknown. Re-open the page."
            ProviderCodes.CANCELLED -> "Cancelled."
            ProviderCodes.UNSUPPORTED_ACTION, ProviderCodes.UNSUPPORTED_PARAMETER -> "Unsupported action."
            ProviderCodes.INVALID_REQUEST -> "Invalid request."
            "DUPLICATE_CALL" -> "This call was already handled."
            "UNAUTHORIZED" -> "Unauthorized."
            "FORBIDDEN" -> "Forbidden."
            "NOT_FOUND" -> "Not found."
            "TOO_LARGE" -> "Request too large."
            "BAD_REQUEST" -> "Invalid request."
            else -> "The browser action failed."
        }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
