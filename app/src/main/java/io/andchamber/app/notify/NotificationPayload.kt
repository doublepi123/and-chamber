package io.andchamber.app.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One OpenChamber desktop-notification event, parsed from a `[OpenChamberDesktopNotify] {json}` line. */
data class DesktopNotification(
    val kind: Kind,
    val title: String,
    val body: String,
    /** Replacement key. Same tag means "update this notification in place". */
    val tag: String,
    val sessionId: String?,
    val directory: String?,
) {
    /** The kinds OpenChamber emits (server/index.js:1521, notifications/runtime.js:566-884). */
    enum class Kind(val wire: String) {
        Ready("ready"),
        Error("error"),
        Question("question"),
        Permission("permission"),
        Goal("goal"),
        Plugin("plugin"),
        RestartInterrupted("opencode-restart-interrupted"),
        Unknown("unknown"),
        ;

        companion object {
            fun of(wire: String): Kind = entries.firstOrNull { it.wire == wire } ?: Unknown
        }
    }
}

/**
 * Parses the stdout protocol. Only lines with the exact prefix are considered, and a malformed or
 * incomplete payload is ignored. The raw JSON never leaves this function (it may contain text).
 */
object NotificationPayload {
    const val PREFIX = "[OpenChamberDesktopNotify] "

    /** Returns null for lines that are not notifications, or whose JSON is not usable. */
    fun parseLine(line: String): DesktopNotification? {
        if (!line.startsWith(PREFIX)) return null
        val obj = runCatching { Json.parseToJsonElement(line.substring(PREFIX.length)) as JsonObject }.getOrNull()
            ?: return null
        val tag = obj.text("tag")?.takeIf { it.isNotBlank() } ?: return null
        return DesktopNotification(
            kind = DesktopNotification.Kind.of(obj.text("kind").orEmpty()),
            title = obj.text("title").orEmpty(),
            body = obj.text("body").orEmpty(),
            tag = tag,
            sessionId = obj.text("sessionId")?.takeIf { it.isNotBlank() },
            directory = obj.text("directory")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Stable notification id for a tag. Same tag → same id, so a later event replaces the earlier one.
     * FNV-1a over the UTF-8 bytes, kept non-negative for NotificationManager.
     */
    fun notificationId(tag: String): Int {
        var hash = -0x7ee3623b // FNV-1a offset basis 0x811c9dc5, as a signed Int
        for (byte in tag.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xff)
            hash *= 0x01000193
        }
        return hash and 0x7fffffff
    }

    /** The `permission-<sid>:<rid>` tag yields the request id; anything else yields null. */
    fun permissionRequestId(tag: String, sessionId: String): String? {
        val prefix = "permission-$sessionId:"
        if (!tag.startsWith(prefix)) return null
        return tag.substring(prefix.length).takeIf { it.isNotBlank() }
    }

    internal fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
