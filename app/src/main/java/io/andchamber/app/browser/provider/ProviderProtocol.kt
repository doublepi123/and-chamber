package io.andchamber.app.browser.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wire shapes for the Android browser provider. Tokens never appear in URLs.
 * A ready event names one connection; a later ready on a new connection does not revive old requests.
 */
object ProviderProtocol {
    const val PROTOCOL_VERSION = 1
    const val READY = "openchamber:event-stream-ready"
    const val REQUEST = "openchamber:browser-control-request"
    const val CANCEL = "openchamber:browser-control-cancel"
    const val TOKEN_HEADER = "X-AndChamber-Browser-Token"
    const val EVENTS_PATH = "/api/openchamber/events?provider=android"
    const val CLAIM_PATH = "/api/browser-control/claim"
    const val RESULT_PATH = "/api/browser-control/result"
    const val MAX_LINE_CHARS = 64 * 1024
    const val MAX_SSE_EVENT_CHARS = 256 * 1024
    const val MAX_SSE_FIELDS = 64
    const val NETWORK_MARGIN_MS = 250L
    const val MAX_RECONNECTS = 3
    val SUPPORTED = listOf(
        "browser.open",
        "browser.snapshot",
        "browser.click",
        "browser.type",
        "browser.scroll",
        "browser.capture",
    )
    private val LOOPBACK_ORIGIN = Regex("^http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/?$")
    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val ACTION = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")
    private val REF_SELECTOR = Regex("^\\[data-andchamber-ref=\"([A-Za-z0-9_-]{1,80})\"\\]$")
    private val json = Json { ignoreUnknownKeys = true }

    fun eventsUrl(origin: String): String {
        require(isLoopbackOrigin(origin)) { "origin refused" }
        return origin.trimEnd('/') + EVENTS_PATH
    }

    fun isLoopbackOrigin(origin: String): Boolean = LOOPBACK_ORIGIN.matches(origin)

    fun parseReady(data: String): Ready? {
        val obj = parse(data) ?: return null
        if (obj.string("type") != READY) return null
        val props = obj["properties"] as? JsonObject ?: return null
        if (props.long("protocolVersion") != PROTOCOL_VERSION.toLong()) return null
        val epoch = props.string("serverEpoch")?.takeIf(::isId) ?: return null
        val connection = props.string("connectionId")?.takeIf(::isId) ?: return null
        return Ready(epoch, connection)
    }

    fun parseRequest(data: String, expectedConnection: String): ProviderRequest? {
        val obj = parse(data) ?: return null
        if (obj.string("type") != REQUEST) return null
        val props = obj["properties"] as? JsonObject ?: return null
        if (props.long("protocolVersion") != PROTOCOL_VERSION.toLong()) return null
        val connection = props.string("connectionId")?.takeIf(::isId) ?: return null
        if (connection != expectedConnection) return null
        val epoch = props.string("serverEpoch")?.takeIf(::isId) ?: return null
        if (props.long("deadlineAtEpochMs") == null) return null
        val id = props.string("requestId")?.takeIf(::isId) ?: return null
        val action = props.string("action")?.takeIf(ACTION::matches) ?: return null
        val context = props["context"] as? JsonObject ?: return null
        val directory = context.string("directory") ?: return null
        if (directory.length > 1_024) return null
        val session = context.string("sessionId")?.takeIf { it.length <= 128 }
        val timeout = props.long("timeoutMs") ?: return null
        if (timeout <= 0L || timeout > 120_000L) return null
        val parameters = (props["parameters"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            if (!isId(key)) return@mapNotNull null
            (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 8_192 }?.let { key to it }
        }?.toMap().orEmpty()
        return ProviderRequest(epoch, connection, id, action, parameters, directory, session, timeout)
    }

    /**
     * Broker cancellation. The event name is shared with the OpenChamber lane:
     * `openchamber:browser-control-cancel`. A missing request id is not a match.
     */
    fun parseCancel(data: String, expectedConnection: String): ProviderCancel? {
        val obj = parse(data) ?: return null
        if (obj.string("type") != CANCEL) return null
        val props = obj["properties"] as? JsonObject ?: return null
        if (props.long("protocolVersion") != PROTOCOL_VERSION.toLong()) return null
        val connection = props.string("connectionId")?.takeIf(::isId) ?: return null
        if (connection != expectedConnection) return null
        val epoch = props.string("serverEpoch")?.takeIf(::isId) ?: return null
        val id = props.string("requestId")?.takeIf(::isId) ?: return null
        val reason = props.string("reason")?.takeIf { it.length <= 64 } ?: "cancelled"
        return ProviderCancel(epoch, connection, id, reason)
    }

    /**
     * Result body the broker accepts: error is a string code, never an object, and never a token.
     * A missing nonce is not a result this client may post.
     */
    fun resultBody(requestId: String, connectionId: String, nonce: String, result: ProviderResult): String? {
        if (!isId(requestId) || !isId(connectionId) || !isId(nonce)) return null
        val error = result.errorCode?.takeIf(::isId)
        return buildString {
            append("{\"requestId\":")
            append(JsonPrimitive(requestId))
            append(",\"connectionId\":")
            append(JsonPrimitive(connectionId))
            append(",\"claimNonce\":")
            append(JsonPrimitive(nonce))
            append(",\"ok\":")
            append(result.ok && error == null)
            if (result.ok && error == null) {
                append(",\"data\":")
                append(result.data)
            }
            if (error != null) {
                append(",\"error\":")
                append(JsonPrimitive(error))
            }
            append('}')
        }
    }

    fun claimBody(requestId: String, connectionId: String): String? {
        if (!isId(requestId) || !isId(connectionId)) return null
        return """{"requestId":${JsonPrimitive(requestId)},"connectionId":${JsonPrimitive(connectionId)}}"""
    }

    fun parseClaim(body: String): ProviderClaim? {
        val obj = parse(body) ?: return null
        val granted = (obj["granted"] as? JsonPrimitive)?.contentOrNull
        if (granted != "true") return ProviderClaim(false, "", 0L)
        val nonce = obj.string("claimNonce")?.takeIf(::isId) ?: return ProviderClaim(false, "", 0L)
        val remaining = obj.long("remainingMs") ?: return ProviderClaim(false, "", 0L)
        if (remaining <= 0L) return ProviderClaim(false, "", 0L)
        return ProviderClaim(true, nonce, remaining)
    }

    fun selectorRef(selector: String?): String? = selector?.let { REF_SELECTOR.matchEntire(it)?.groupValues?.get(1) }

    fun remainingDeadline(nowElapsed: Long, remainingMs: Long): Long =
        nowElapsed + (remainingMs - NETWORK_MARGIN_MS).coerceAtLeast(0L)

    private fun isId(value: String): Boolean = ID.matches(value)

    private fun parse(data: String): JsonObject? =
        if (data.length > MAX_SSE_EVENT_CHARS) null else runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

    data class Ready(val serverEpoch: String, val connectionId: String)
}

data class ProviderCancel(
    val serverEpoch: String,
    val connectionId: String,
    val requestId: String,
    val reason: String,
)

/**
 * One SSE event from a bounded byte stream. Comment, id and retry fields are ignored.
 * event/data are the only fields kept, and only within a hard size.
 */
internal data class SseEvent(val event: String?, val data: String)

/**
 * Strict line buffer. An oversized pending line or chunk is dropped and the event is discarded.
 * A split chunk is held until a newline arrives. The buffer never grows without a bound.
 */
internal class SseLineBuffer(private val limit: Int = ProviderProtocol.MAX_LINE_CHARS) {
    private val pending = StringBuilder()
    private var dropped = false

    fun push(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var index = 0
        while (index < chunk.length) {
            if (dropped) {
                val newline = chunk.indexOf('\n', index)
                if (newline < 0) return lines
                dropped = false
                index = newline + 1
                continue
            }
            val newline = chunk.indexOf('\n', index)
            val end = if (newline < 0) chunk.length else newline
            if (pending.length > limit - (end - index)) {
                pending.clear()
                dropped = true
                index = end
                continue
            }
            pending.append(chunk, index, end)
            if (newline < 0) return lines
            lines += pending.toString().removeSuffix("\r")
            pending.clear()
            index = newline + 1
        }
        return lines
    }
}

/**
 * Assembles SSE events from lines. A blank line ends an event. More than the field or size
 * bound drops that event. Heartbeat comments never become requests.
 */
internal class SseEventParser(
    private val maxEventChars: Int = ProviderProtocol.MAX_SSE_EVENT_CHARS,
    private val maxFields: Int = ProviderProtocol.MAX_SSE_FIELDS,
) {
    private var eventName: String? = null
    private val data = StringBuilder()
    private var fields = 0
    private var dropped = false

    fun push(line: String): SseEvent? {
        if (line.isEmpty()) return finish()
        if (line.startsWith(":")) return null
        if (dropped || fields >= maxFields || line.length > maxEventChars) {
            dropped = true
            return null
        }
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val rawValue = if (colon < 0) "" else line.substring(colon + 1)
        val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
        if (field != "data" && field != "event") return null
        if (data.length + value.length > maxEventChars) {
            dropped = true
            return null
        }
        fields += 1
        when (field) {
            "event" -> if (eventName == null) eventName = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    private fun finish(): SseEvent? {
        val event = if (!dropped && data.isNotEmpty()) SseEvent(eventName, data.toString()) else null
        eventName = null
        data.clear()
        fields = 0
        dropped = false
        return event
    }
}
