package io.andchamber.app.phone.tool

import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** One validated `/v1/tool` request from the phone plugin. */
data class PhoneToolCall(
    val sessionId: String,
    val callId: String,
    val messageId: String?,
    val agent: String?,
    val action: String,
    val input: JsonObject,
)

/**
 * The fixed, closed action surface. There is no generic shell, arbitrary intent, key-event stream
 * or package listing here: each action carries exactly the fields it needs.
 */
sealed interface PhoneToolAction {
    data object Status : PhoneToolAction

    data object Snapshot : PhoneToolAction

    data class Capture(val observationId: String) : PhoneToolAction

    data class TapRef(
        val ref: String,
        val observationId: String,
    ) : PhoneToolAction

    data class TapPoint(
        val x: Int,
        val y: Int,
        val observationId: String,
    ) : PhoneToolAction

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Int,
        val observationId: String,
    ) : PhoneToolAction

    data class Input(
        val text: String,
        val ref: String?,
        val observationId: String,
    ) : PhoneToolAction

    data class Back(val observationId: String) : PhoneToolAction

    /**
     * Direct start inside the native standing scope. The native [PhoneTaskStarter] checks the
     * person-confirmed apps/actions/window, creates the per-task grant and launches the allowed app;
     * this tool never treats the model's text as authority. The old pending-confirmation proposal
     * path is not used for this primary mode.
     */
    data class Request(
        val summary: String,
        val packageName: String,
    ) : PhoneToolAction

    /** Read-only list of only the person-preauthorized apps. Never the installed-app catalogue. */
    data object Apps : PhoneToolAction

    /** Opens exactly one preauthorized package; the starter checks its scope and launches. */
    data class OpenApp(val packageName: String) : PhoneToolAction

    /** Unverified model narration for the current native task. Never a native fact. */
    data class Progress(
        val epoch: Long,
        val phase: String,
    ) : PhoneToolAction

    /** Asks for the person; only becomes waiting after a native stop/revoke acknowledgement. */
    data class NeedUser(
        val epoch: Long,
        val reason: String,
    ) : PhoneToolAction

    /** Reports the agent's end; pending verification, never a business success. */
    data class Finish(
        val epoch: Long,
        val summary: String,
    ) : PhoneToolAction
}

/**
 * Pure request/response mapping for the phone tool server.
 *
 * Everything the model can name is re-validated here, independently of the plugin's JSON schema, so
 * a direct caller of the loopback port cannot widen the surface. Error messages are fixed strings:
 * they never carry page text, labels, coordinates, tokens or a package list.
 */
object PhoneToolProtocol {
    const val VERSION = 1
    const val MAX_BODY_BYTES = 65_536

    /**
     * The plugin's setup handshake path. It is written by the plugin itself, not by the endpoint
     * file, and it exists only to prove that this launch's plugin `setup`/tool-transform finished and
     * reached this exact native launch. It grants nothing.
     */
    const val READY_PATH = "/v1/ready"
    const val READY_PLUGIN_ID = "andchamber-phone"
    const val READY_TOOL_NAME = "andchamber_phone"
    const val MAX_SESSION_CHARS = 128
    const val MAX_CALL_ID_CHARS = 256
    const val MAX_MESSAGE_CHARS = 256
    const val MAX_AGENT_CHARS = 128
    const val MAX_OBSERVATION_ID_CHARS = 128
    const val MAX_REF_CHARS = 64
    const val MAX_SUMMARY_CHARS = 200
    const val MAX_PACKAGE_CHARS = PhoneLimits.MAX_PACKAGE_CHARS
    const val MAX_COORDINATE = PhoneLimits.MAX_DISPLAY_PX
    const val DEFAULT_SWIPE_MS = 300

    /** The fixed reply for a successful proposal: the person confirms in the app, not here. */
    const val CONFIRM_IN_APP = "请在AndChamber手机控制中确认"

    /** A successful model finish is only a report; it is not a verified business success. */
    const val FINISH_REPORTED = "报告结束，待核对"

    const val MAX_EVENT_TEXT_CHARS = 120

    /** Placeholder action for a request that failed before its action could be read. */
    const val UNKNOWN_ACTION = "phone.unknown"

    val ACTIONS = setOf(
        "status",
        "request",
        "apps",
        "open_app",
        "progress",
        "need_user",
        "finish",
        "snapshot",
        "capture",
        "tap",
        "swipe",
        "input",
        "back",
    )

    private val BODY_KEYS = setOf("version", "sessionID", "callID", "messageID", "agent", "input")

    /** The ready body is closed: exactly these three keys, nothing session/task/text related. */
    private val READY_BODY_KEYS = setOf("version", "plugin", "tool")

    private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val REF_PATTERN = Regex("^[A-Za-z0-9:_-]{1,64}$")
    private val OBSERVATION_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val json = Json { ignoreUnknownKeys = false }

    fun serverEpoch(launchId: Long): String = "launch-$launchId"

    /**
     * Exact, closed readiness body: `{version:1,plugin:"andchamber-phone",tool:"andchamber_phone"}`.
     * Any extra or missing key, a string version, a different plugin/tool name, or any session,
     * task, text or token field is refused. The handshake never carries identity or content.
     */
    fun isReadyBody(raw: String): Boolean =
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root.keys != READY_BODY_KEYS) return@runCatching false
            val version = root["version"] as? JsonPrimitive ?: return@runCatching false
            if (version.isString) return@runCatching false
            version.intOrNull == VERSION &&
                root.readyString("plugin") == READY_PLUGIN_ID &&
                root.readyString("tool") == READY_TOOL_NAME
        }.getOrDefault(false)

    private fun JsonObject.readyString(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Bounded ack for a successful handshake: no port, token, session or launch detail. */
    fun readyAck(): String =
        buildJsonObject {
            put("version", VERSION)
            put("ready", true)
        }.toString()

    /** A callID that is already a safe request id is kept; anything else becomes a digest. */
    fun requestId(callId: String): String =
        if (REQUEST_ID_PATTERN.matches(callId)) callId else "c-" + sha256(callId).take(40)

    /** Returns null for anything that does not match the contract's body shape. */
    fun parseBody(raw: String): PhoneToolCall? =
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root.keys.any { it !in BODY_KEYS }) return@runCatching null
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return@runCatching null
            val sessionId = root.string("sessionID")?.takeIf {
                it.length in 1..MAX_SESSION_CHARS && it.none(Char::isISOControl)
            } ?: return@runCatching null
            val callId = root.string("callID")?.takeIf { it.length in 1..MAX_CALL_ID_CHARS }
                ?: return@runCatching null
            val input = root["input"] as? JsonObject ?: return@runCatching null
            val action = input["action"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: return@runCatching null
            if (action !in ACTIONS) return@runCatching null
            val messageId = root.optionalString("messageID", MAX_MESSAGE_CHARS) ?: return@runCatching null
            val agent = root.optionalString("agent", MAX_AGENT_CHARS) ?: return@runCatching null
            PhoneToolCall(
                sessionId = sessionId,
                callId = callId,
                messageId = messageId,
                agent = agent,
                action = action,
                input = input,
            )
        }.getOrNull()

    /**
     * Strict per-action validation. Every action has its own closed key set; an unknown key, a
     * non-integer coordinate, a partial point, a mixed ref/coordinate tap or an out-of-range value
     * refuses the whole call instead of being ignored.
     */
    fun parseAction(call: PhoneToolCall): PhoneToolAction? =
        runCatching {
            val input = call.input
            when (call.action) {
                "status" -> {
                    requireKeys(input, emptySet())
                    PhoneToolAction.Status
                }
                "request" -> {
                    requireKeys(input, setOf("summary", "packageName"))
                    val summary = input.string("summary") ?: return@runCatching null
                    if (summary.isBlank() ||
                        summary.length > MAX_SUMMARY_CHARS ||
                        summary.any(Char::isISOControl)
                    ) {
                        return@runCatching null
                    }
                    val packageName = requiredPackage(input) ?: return@runCatching null
                    PhoneToolAction.Request(summary, packageName)
                }
                "apps" -> {
                    requireKeys(input, emptySet())
                    PhoneToolAction.Apps
                }
                "open_app" -> {
                    requireKeys(input, setOf("packageName"))
                    val packageName = requiredPackage(input) ?: return@runCatching null
                    PhoneToolAction.OpenApp(packageName)
                }
                "progress" -> {
                    requireKeys(input, setOf("epoch", "phase", "message"))
                    val epoch = requiredEpoch(input) ?: return@runCatching null
                    val phase = optionalEventText(input, "phase")
                    val message = optionalEventText(input, "message")
                    if ((phase == null) == (message == null)) return@runCatching null
                    PhoneToolAction.Progress(epoch, phase ?: message!!)
                }
                "need_user" -> {
                    requireKeys(input, setOf("epoch", "reason", "message"))
                    val epoch = requiredEpoch(input) ?: return@runCatching null
                    val reason = optionalEventText(input, "reason")
                    val message = optionalEventText(input, "message")
                    if ((reason == null) == (message == null)) return@runCatching null
                    PhoneToolAction.NeedUser(epoch, reason ?: message!!)
                }
                "finish" -> {
                    requireKeys(input, setOf("epoch", "summary"))
                    val epoch = requiredEpoch(input) ?: return@runCatching null
                    val summary = optionalEventText(input, "summary") ?: return@runCatching null
                    PhoneToolAction.Finish(epoch, summary)
                }
                "snapshot" -> {
                    requireKeys(input, emptySet())
                    PhoneToolAction.Snapshot
                }
                "capture" -> {
                    requireKeys(input, setOf("action", "observationId"))
                    PhoneToolAction.Capture(observationId(input))
                }
                "tap" -> {
                    val ref = optionalRef(input)
                    val x = optionalInt(input, "x")
                    val y = optionalInt(input, "y")
                    val observation = observationId(input)
                    when {
                        ref != null && (x != null || y != null) -> null
                        ref != null -> {
                            requireKeys(input, setOf("action", "observationId", "ref"))
                            PhoneToolAction.TapRef(ref, observation)
                        }
                        x != null && y != null -> {
                            requireKeys(input, setOf("action", "observationId", "x", "y"))
                            requireCoordinate(x)
                            requireCoordinate(y)
                            PhoneToolAction.TapPoint(x, y, observation)
                        }
                        else -> null
                    }
                }
                "swipe" -> {
                    requireKeys(
                        input,
                        setOf("action", "observationId", "x", "y", "x2", "y2", "durationMs"),
                    )
                    val x = requiredInt(input, "x") ?: return@runCatching null
                    val y = requiredInt(input, "y") ?: return@runCatching null
                    val x2 = requiredInt(input, "x2") ?: return@runCatching null
                    val y2 = requiredInt(input, "y2") ?: return@runCatching null
                    listOf(x, y, x2, y2).forEach(::requireCoordinate)
                    val duration = optionalInt(input, "durationMs") ?: DEFAULT_SWIPE_MS
                    if (duration !in PhoneLimits.MIN_SWIPE_MS..PhoneLimits.MAX_SWIPE_MS) {
                        return@runCatching null
                    }
                    if (x == x2 && y == y2) return@runCatching null
                    PhoneToolAction.Swipe(x, y, x2, y2, duration, observationId(input))
                }
                "input" -> {
                    requireKeys(input, setOf("action", "observationId", "text", "ref"))
                    val text = input.string("text") ?: return@runCatching null
                    if (text.isBlank() || text.length > PhoneLimits.MAX_INPUT_CHARS) {
                        return@runCatching null
                    }
                    if (text.any(Char::isISOControl)) return@runCatching null
                    PhoneToolAction.Input(text, optionalRef(input), observationId(input))
                }
                "back" -> {
                    requireKeys(input, setOf("action", "observationId"))
                    PhoneToolAction.Back(observationId(input))
                }
                else -> null
            }
        }.getOrNull()

    /**
     * Every envelope carries `version`, `ok`, `action` and the grant `epoch`.
     *
     * On success `data` is the supplied payload (an empty object when none is given). On failure
     * `data` is absent unless [startFailureStage] is given, in which case it contains exactly
     * `startFailureStage` as the fixed enum name; the generic [data] argument is ignored for a
     * failure so no arbitrary success-shaped payload can leak. `error.code`/`error.message` are
     * fixed values on failure.
     */
    fun envelope(
        action: String,
        ok: Boolean,
        epoch: Long,
        data: JsonObject? = null,
        code: String? = null,
        startFailureStage: PhoneTaskStartFailureStage? = null,
    ): String =
        buildJsonObject {
            put("version", VERSION)
            put("ok", ok)
            put("action", action)
            put("epoch", epoch)
            if (ok) {
                put("data", data ?: buildJsonObject { })
            } else {
                val error = code ?: PhoneToolCodes.OUTCOME_UNKNOWN
                put(
                    "error",
                    buildJsonObject {
                        put("code", error)
                        put("message", fixedMessage(error))
                    },
                )
                // Typed metadata only: the enum name, never page text, labels, an exception or
                // typed user content. The ordinary success `data` argument is not consulted here.
                startFailureStage?.let { stage ->
                    put(
                        "data",
                        buildJsonObject {
                            put("startFailureStage", stage.name)
                        },
                    )
                }
            }
        }.toString()

    fun errorBody(
        code: String,
        action: String = UNKNOWN_ACTION,
        epoch: Long = 0L,
    ): String = envelope(action = action, ok = false, epoch = epoch, code = code)

    /** Fixed messages only: driver output may quote labels or page content. */
    fun fixedMessage(code: String): String =
        when (code) {
            PhoneToolCodes.DISABLED -> "Phone control is turned off on the phone."
            PhoneToolCodes.NOT_GRANTED ->
                "Phone control is not granted for this call. The saved standing scope may still exist; check status and whether the phone shows a waiting task or a native continue entry before asking the person to grant anything again. Do not re-pair."
            PhoneToolCodes.LEASE_EXPIRED -> "The phone-control grant expired. Ask the person to grant it again."
            PhoneToolCodes.EPOCH_CHANGED -> "The phone-control grant changed. Take a new snapshot and use the new epoch."
            PhoneToolCodes.PAUSED -> "Phone control is paused on the phone. Ask the person to resume it."
            PhoneToolCodes.OUTCOME_UNKNOWN ->
                "The action's outcome is unconfirmed. Do not repeat it. Take a fresh snapshot if possible, then ask the person to check the phone."
            PhoneToolCodes.DUPLICATE, PhoneToolCodes.DUPLICATE_CALL -> "This call was already handled."
            PhoneToolCodes.CAPACITY -> "Too many phone-control requests for this launch."
            PhoneToolCodes.BUSY -> "The phone is busy with another action."
            PhoneToolCodes.TARGET_FORBIDDEN -> "That app cannot be controlled."
            PhoneToolCodes.TARGET_MISMATCH -> "The foreground app changed. Take a new snapshot."
            PhoneToolCodes.WINDOW_CHANGED -> "The phone window changed. Take a new snapshot."
            PhoneToolCodes.GEOMETRY_CHANGED -> "The screen geometry changed. Take a new snapshot."
            PhoneToolCodes.STALE_OBSERVATION -> "The observation expired. Take a new snapshot."
            PhoneToolCodes.OBSERVATION_REQUIRED -> "Take a snapshot first and use its observationId."
            PhoneToolCodes.UNSUPPORTED_ACTION -> "Unsupported action."
            PhoneToolCodes.UNICODE_UNSUPPORTED -> "This text cannot be entered on this device."
            PhoneToolCodes.ACTION_NOT_GRANTED -> "That action is not included in the grant."
            PhoneToolCodes.INVALID_REQUEST ->
                "The fields for this action are not valid. Nothing was done. Fix the call and try again: \"snapshot\", \"status\" and \"apps\" take no other fields; \"tap\" takes observationId plus either ref or both x and y; \"progress\", \"need_user\" and \"finish\" need the current epoch from status."
            PhoneToolCodes.OUT_OF_BOUNDS -> "The point is outside the current screen. Take a new snapshot."
            PhoneToolCodes.OVERLAY_OBSTRUCTED ->
                "That point or swipe would land on the AndChamber progress capsule, so nothing was sent. Control is still active. Choose another point, scroll the target into the open area, or ask the person to move the capsule."
            PhoneToolCodes.NOT_CONNECTED -> "The phone is not connected."
            PhoneToolCodes.DRIVER_UNAVAILABLE ->
                "The phone returned output that could not be read for this call. This does not prove the device is unsupported or that the connection is down, and it does not confirm whether an earlier action applied. Do not repeat a mutating action; take a fresh snapshot, and if this keeps happening ask the person to check the phone."
            PhoneToolCodes.DEADLINE -> "The action timed out."
            PhoneToolCodes.HIERARCHY_NOT_IDLE ->
                "The phone did not provide a valid hierarchy or page. This does not mean the connection is down, and it does not by itself revoke an existing task. Do not call need_user for this. Use status to see whether the original task is still live or a start was rolled back; if it is live, keep it and wait for the page to settle. If no task is live, do not fake a grant with snapshot or resume in place; only the existing saved scope and authority can support a new legal request. This error never authorises automatic recovery of stopped control, and retries are bounded."
            PhoneToolCodes.READ_TIMEOUT ->
                "Reading the phone timed out. This control connection is no longer used. Ask the person to check the phone and reconnect explicitly; this does not prove the network is down or that the physical socket has closed, and this tool cannot recover it automatically."
            PhoneToolCodes.CANCELLED -> "Cancelled."
            PhoneToolCodes.SESSION_BUSY ->
                "Another chat session's phone task is still running or waiting. Do not retry in a loop. After that task ends (or the person ends it on the phone), this session can send a new request; no new setup is needed."
            PhoneToolCodes.ROUTE_NOT_PUBLISHED ->
                "Phone control is not ready for this grant yet. Do not retry in a loop; the person must confirm the task in AndChamber phone control."
            PhoneToolCodes.NO_TASK -> "There is no confirmed phone task for this epoch."
            PhoneToolCodes.STALE_TASK -> "The phone task changed. Use status and the current epoch."
            PhoneToolCodes.TASK_NOT_ACTIVE -> "The phone task is no longer accepting agent updates."
            PhoneToolCodes.TASK_EVENTS_UNAVAILABLE -> "Phone task progress is not available."
            PhoneToolCodes.STOP_UNCONFIRMED ->
                "The phone has not confirmed that control stopped. Do not claim it stopped; ask the person to check the phone."
            PhoneToolCodes.GRANT_ACTIVE ->
                "Phone control already has an active grant. Finish or revoke it on the phone before requesting another task."
            PhoneToolCodes.RUNTIME_NOT_READY ->
                "The local runtime is not ready. Open the AndChamber chat screen on the phone and try again."
            PhoneToolCodes.PHONE_UNAVAILABLE ->
                "AndChamber phone control is not available. Open the AndChamber chat screen on the phone and try again."
            PhoneToolCodes.STANDING_UNAVAILABLE ->
                "Phone control is not configured for direct chat requests. Ask the person to set up the standing scope on the phone."
            PhoneToolCodes.STANDING_BLOCKED ->
                "Phone control is waiting for the person on the phone. The saved standing scope may still exist; check status and the native waiting/continue entry. Do not re-pair, and do not retry in a loop. Only the person can clear the block; this tool cannot resume or recover it automatically."
            PhoneToolCodes.STANDING_EXPIRED ->
                "The phone-control standing scope expired. Ask the person to set it up again on the phone."
            PhoneToolCodes.START_FAILED, PhoneToolCodes.TARGET_LAUNCH_FAILED ->
                "The phone task could not be started. Nothing is running; ask the person to check the phone before trying again."
            PhoneToolCodes.UNAUTHORIZED -> "Unauthorized."
            PhoneToolCodes.FORBIDDEN -> "Forbidden."
            PhoneToolCodes.NOT_FOUND -> "Not found."
            PhoneToolCodes.TOO_LARGE -> "Request too large."
            PhoneToolCodes.BAD_REQUEST -> "Invalid request."
            else -> "The phone action failed."
        }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun JsonObject.optionalString(
        name: String,
        maxChars: Int,
    ): String? {
        val value = this[name] ?: return ""
        if (value is JsonNull) return ""
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val text = primitive.content
        if (text.isEmpty()) return ""
        if (text.length > maxChars || text.any(Char::isISOControl)) return null
        return text
    }

    private fun observationId(input: JsonObject): String {
        val id = input.string("observationId") ?: throw Refused()
        if (id.length > MAX_OBSERVATION_ID_CHARS || !OBSERVATION_ID_PATTERN.matches(id)) throw Refused()
        return id
    }

    private fun optionalRef(input: JsonObject): String? {
        val raw = input["ref"] ?: return null
        if (raw is JsonNull) return null
        val primitive = raw as? JsonPrimitive ?: throw Refused()
        if (!primitive.isString) throw Refused()
        val ref = primitive.content
        if (ref.length > MAX_REF_CHARS || !REF_PATTERN.matches(ref)) throw Refused()
        return ref
    }

    private fun optionalPackage(input: JsonObject): String? {
        val raw = input["packageName"] ?: return null
        if (raw is JsonNull) return null
        val primitive = raw as? JsonPrimitive ?: throw Refused()
        if (!primitive.isString) throw Refused()
        val packageName = primitive.content
        if (packageName.isBlank() ||
            packageName.length > MAX_PACKAGE_CHARS ||
            packageName.any(Char::isISOControl) ||
            !PACKAGE_PATTERN.matches(packageName)
        ) {
            throw Refused()
        }
        return packageName
    }

    /** The direct standing request requires an explicit target from the native preauthorized list. */
    private fun requiredPackage(input: JsonObject): String? = optionalPackage(input)

    private fun requiredInt(
        input: JsonObject,
        name: String,
    ): Int? {
        val raw = input[name] ?: return null
        val primitive = raw as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun optionalInt(
        input: JsonObject,
        name: String,
    ): Int? {
        if (!input.containsKey(name)) return null
        val raw = input[name]
        if (raw is JsonNull) throw Refused()
        return requiredInt(input, name) ?: throw Refused()
    }

    private fun requiredEpoch(input: JsonObject): Long? {
        val raw = input["epoch"] ?: return null
        val primitive = raw as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val epoch = primitive.longOrNull ?: return null
        return epoch.takeIf { it >= 0 }
    }

    private fun optionalEventText(
        input: JsonObject,
        name: String,
    ): String? {
        val raw = input[name] ?: return null
        if (raw is JsonNull) return null
        val primitive = raw as? JsonPrimitive ?: throw Refused()
        if (!primitive.isString) throw Refused()
        val text = primitive.content
        if (text.isBlank() ||
            text.length > MAX_EVENT_TEXT_CHARS ||
            text.any(Char::isISOControl)
        ) {
            throw Refused()
        }
        return text
    }

    private fun requireCoordinate(value: Int) {
        if (value !in 0..MAX_COORDINATE) throw Refused()
    }

    /**
     * Context keys a model commonly copies from `status`/`snapshot` into the next call. They carry
     * no authority here (epoch admission and observation binding are checked separately where they
     * matter), so an action that does not use them ignores them instead of refusing the whole call.
     */
    private val IGNORED_CONTEXT_KEYS = setOf("epoch", "observationId")

    private fun requireKeys(
        input: JsonObject,
        allowed: Set<String>,
    ) {
        if (input.keys.any { it != "action" && it !in allowed && it !in IGNORED_CONTEXT_KEYS }) {
            throw Refused()
        }
        if ("action" !in input || input["action"]?.jsonPrimitive?.takeIf { it.isString } == null) throw Refused()
    }

    private class Refused : Exception()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Protocol codes beyond the core enum: transport/session state, never a new authority. */
object PhoneToolCodes {
    const val DISABLED = "DISABLED"
    const val NOT_GRANTED = "NOT_GRANTED"
    const val LEASE_EXPIRED = "LEASE_EXPIRED"
    const val EPOCH_CHANGED = "EPOCH_CHANGED"
    const val PAUSED = "PAUSED"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    const val DUPLICATE = "DUPLICATE"
    const val CAPACITY = "CAPACITY"
    const val BUSY = "BUSY"
    const val TARGET_FORBIDDEN = "TARGET_FORBIDDEN"
    const val TARGET_MISMATCH = "TARGET_MISMATCH"
    const val WINDOW_CHANGED = "WINDOW_CHANGED"
    const val GEOMETRY_CHANGED = "GEOMETRY_CHANGED"
    const val STALE_OBSERVATION = "STALE_OBSERVATION"
    const val OBSERVATION_REQUIRED = "OBSERVATION_REQUIRED"
    const val UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION"
    const val UNICODE_UNSUPPORTED = "UNICODE_UNSUPPORTED"
    const val ACTION_NOT_GRANTED = "ACTION_NOT_GRANTED"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val OUT_OF_BOUNDS = "OUT_OF_BOUNDS"
    const val OVERLAY_OBSTRUCTED = "OVERLAY_OBSTRUCTED"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val DRIVER_UNAVAILABLE = "DRIVER_UNAVAILABLE"
    const val DEADLINE = "DEADLINE"

    /**
     * Read-only observation never reached a stable hierarchy/page. This is not a connection-loss
     * claim and never hints at a retry: the page may still be changing.
     */
    const val HIERARCHY_NOT_IDLE = "HIERARCHY_NOT_IDLE"

    /**
     * The read watchdog gave up on this control connection. It is not a mutation outcome and does
     * not prove the network dropped or that the physical socket close completed.
     */
    const val READ_TIMEOUT = "READ_TIMEOUT"

    const val CANCELLED = "CANCELLED"

    /** Transport-level refusals. They add no capability and grant nothing. */
    const val SESSION_BUSY = "SESSION_BUSY"
    const val GRANT_ACTIVE = "GRANT_ACTIVE"
    const val ROUTE_NOT_PUBLISHED = "ROUTE_NOT_PUBLISHED"
    const val NO_TASK = "NO_TASK"
    const val STALE_TASK = "STALE_TASK"
    const val TASK_NOT_ACTIVE = "TASK_NOT_ACTIVE"
    const val TASK_EVENTS_UNAVAILABLE = "TASK_EVENTS_UNAVAILABLE"
    const val STOP_UNCONFIRMED = "STOP_UNCONFIRMED"
    const val RUNTIME_NOT_READY = "RUNTIME_NOT_READY"
    const val PHONE_UNAVAILABLE = "PHONE_UNAVAILABLE"
    const val STANDING_UNAVAILABLE = "STANDING_UNAVAILABLE"
    const val STANDING_BLOCKED = "STANDING_BLOCKED"
    const val STANDING_EXPIRED = "STANDING_EXPIRED"

    /**
     * A native start that the tool had already admitted could not be kept executable. The native
     * writer maps PhoneStandingRefusal.START_FAILED here; the start was rolled back, so the chat must
     * never treat the call as a running task.
     */
    const val START_FAILED = "START_FAILED"

    /** Alias used by the native launch path when the allowed app could not be launched at all. */
    const val TARGET_LAUNCH_FAILED = "TARGET_LAUNCH_FAILED"
    const val DUPLICATE_CALL = "DUPLICATE_CALL"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val TOO_LARGE = "TOO_LARGE"
    const val BAD_REQUEST = "BAD_REQUEST"
}
