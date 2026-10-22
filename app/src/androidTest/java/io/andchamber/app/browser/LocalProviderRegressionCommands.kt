package io.andchamber.app.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed command protocol shared with the host lane. One command per id, current run only; arbitrary
 * shell, URL or JavaScript source is never a command. Pure Kotlin so the parser is testable without
 * a device.
 */
internal object LocalProviderRegressionProtocol {
    enum class Kind { TOOL, DEVICE, FINISH }

    data class Command(
        val runId: String,
        val id: String,
        val kind: Kind,
        val action: String?,
        val input: JSONObject,
        val text: String?,
        val summary: JSONObject?,
        val sessionId: String?,
    )

    sealed interface Parsed {
        data class Ok(val command: Command) : Parsed
        data class Rejected(val reason: String) : Parsed
    }

    val TOOL_ACTIONS: Set<String> = setOf(
        "browser.open",
        "browser.snapshot",
        "browser.click",
        "browser.type",
        "browser.scroll",
        "browser.capture",
        "browser.resize",
    )

    /** Fixed UI labels the host may read or tap through accessibility. */
    val UI_LABELS: Set<String> = setOf(
        "More", "Computer layout", "Phone layout", "Confirm", "Mouse", "Touch",
        "Enlarge", "Restore", "Allow agent", "Take over", "Reopen", "Open", "Close", "Turn off",
        "Browser", "New chat", "New session", "Open sessions and projects",
        "AndChamber menu", "Open web page", "Address", "Cancel",
    )

    /**
     * Main-chat and app-owned native labels for ui-target-chat; the host reads and taps them. The
     * edge handle, its menu and the OpenBrowserUrlDialog are Compose UI, not patched chat markup.
     */
    val CHAT_UI_LABELS: Set<String> = setOf(
        "Open sessions and projects", "New chat",
        "AndChamber menu", "Open web page", "Address", "Open", "Cancel",
    )

    /** Non-clickable window semantics that ui-state reports with the same fixed labels. */
    val UI_STATE_LABELS: Set<String> = UI_LABELS + setOf("BrowserFloatSurface", "BrowserFloatTight")

    /** Login-form and focused-fixture labels that may receive a native pointer tap. */
    val FIXTURE_TAP_LABELS: Set<String> = setOf("login-user", "Sign in", "note", "Apply")

    /**
     * The complete finish list: the six existing lifecycle case names plus the two retirement case
     * names. A different list, a duplicate or an unknown case is refused.
     */
    val EXPECTED_CASES: List<String> = listOf(
        "lifecycle-b5-enlarge-restore",
        "lifecycle-b5-user-scroll-wins",
        "lifecycle-b5-document-change",
        "lifecycle-b3-mode-mobile-to-desktop",
        "lifecycle-b3-mode-desktop-to-mobile",
        "lifecycle-b3-post-roundtrip",
        "retirement-a-primary-drag-lock",
        "retirement-b-true-cancel-reopen",
    )
    val LIFECYCLE_CASE_NAMES: List<String> = EXPECTED_CASES.take(6)
    val RETIREMENT_CASE_NAMES: List<String> = EXPECTED_CASES.drop(6)

    /** A validated, non-empty subset. A full all-eight list is the only non-partial request. */
    data class CaseRequest(
        val cases: List<String>,
        val scope: String,
        val partial: Boolean,
    )

    sealed interface CasesParsed {
        data class Ok(val request: CaseRequest) : CasesParsed
        data class Rejected(val reason: String) : CasesParsed
    }

    /** Fixed fixture retirement selectors; the only DOM ids any command may name. */
    val FIXTURE_TARGETS: Set<String> = setOf("box", "next")
    const val TEST_COOKIE_NAME = "oc_fixture_retire"

    private val DEVICE_EXACT: Set<String> = setOf(
        "session:new",
        "lifecycle-diag",
        "geometry-diag",
        "composer-layout",
        "page-rect",
        "fixture-state",
        "observe",
        "ui-state",
        "input-value",
        "cookie-read",
        "presentation-observe",
        "site-state-read",
        "site-state-clear",
        "profile-isolation",
        "native-blocked-input",
        "native-entry-state",
        "a11y-off:begin",
        "a11y-off:end",
    )

    private val FIXTURE_FIELDS = setOf("login-user", "Sign in", "note", "Apply")
    private val GATE_FINGER_ACTIONS = setOf("down", "move", "secondary-down", "secondary-up", "primary-up", "up", "cancel")
    private val GATE_NATIVE_ACTIONS = setOf("enter", "exit", "hover", "wheel")
    private val ID = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private const val COORD_LIMIT = 20_000f
    private const val DELTA_LIMIT = 5_000f
    private const val WHEEL_LIMIT = 2_000f
    private const val HOLD_LIMIT = 5_000f

    fun parse(raw: String, expectedRunId: String, seenIds: Set<String>): Parsed {
        val body = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Parsed.Rejected("invalid-json")
        if (body.optString("runId") != expectedRunId) return Parsed.Rejected("stale-run")
        val id = body.optString("id")
        if (!ID.matches(id)) return Parsed.Rejected("invalid-id")
        if (id in seenIds) return Parsed.Rejected("duplicate-id")
        val kind = when (body.optString("kind")) {
            "tool" -> Kind.TOOL
            "device" -> Kind.DEVICE
            "finish" -> Kind.FINISH
            else -> return Parsed.Rejected("unknown-kind")
        }
        val sessionId = body.optString("sessionId").takeIf { it.isNotBlank() }
        if (body.has("sessionId") && sessionId == null) return Parsed.Rejected("invalid-session-id")
        return when (kind) {
            Kind.TOOL -> {
                val action = body.optString("action")
                if (action !in TOOL_ACTIONS) return Parsed.Rejected("unknown-action")
                if (body.has("input") && body.optJSONObject("input") == null) return Parsed.Rejected("invalid-input")
                val input = body.optJSONObject("input") ?: JSONObject()
                if (input.has("sessionId")) return Parsed.Rejected("nested-session-id")
                Parsed.Ok(Command(expectedRunId, id, kind, action, input, null, null, sessionId))
            }
            Kind.DEVICE -> {
                val text = body.optString("text")
                if (!deviceAllowed(text)) return Parsed.Rejected("unknown-device-command")
                Parsed.Ok(Command(expectedRunId, id, kind, null, JSONObject(), text, null, sessionId))
            }
            Kind.FINISH -> {
                val summary = body.optJSONObject("summary")
                    ?: return Parsed.Rejected("finish-summary-missing")
                Parsed.Ok(Command(expectedRunId, id, kind, null, JSONObject(), null, summary, sessionId))
            }
        }
    }

    fun deviceAllowed(text: String): Boolean = when {
        text in DEVICE_EXACT -> true
        text.startsWith("select:") -> text.removePrefix("select:").let { it.isNotBlank() && it.length <= 120 }
        text.startsWith("ui-target:") -> text.removePrefix("ui-target:") in UI_LABELS
        text.startsWith("tap-label:") ->
            text.removePrefix("tap-label:") in (UI_LABELS + FIXTURE_TAP_LABELS)
        text.startsWith("fixture-field:") -> text.removePrefix("fixture-field:") in FIXTURE_FIELDS
        text.startsWith("fixture-target:") -> fixtureTargetAllowed(text.removePrefix("fixture-target:"))
        text.startsWith("cookie-write:") -> isTestCookieValue(text.removePrefix("cookie-write:"))
        text.startsWith("site-state-write:") -> isSiteStateMarker(text.removePrefix("site-state-write:"))
        text.startsWith("ui-target-chat:") -> text.removePrefix("ui-target-chat:") in CHAT_UI_LABELS
        text.startsWith("gate-native:") -> gateNativeAllowed(text.removePrefix("gate-native:"))
        text.startsWith("gate-finger:") -> gateFingerAllowed(text.removePrefix("gate-finger:"))
        text.startsWith("finger:") -> fingerAllowed(text.removePrefix("finger:"))
        text.startsWith("two-finger-scroll:") -> twoFingerAllowed(text.removePrefix("two-finger-scroll:"))
        text.startsWith("pinch:") -> pinchAllowed(text.removePrefix("pinch:"))
        else -> false
    }

    private fun gateNativeAllowed(spec: String): Boolean {
        val parts = spec.split(",")
        val action = parts.firstOrNull().orEmpty()
        if (action !in GATE_NATIVE_ACTIONS) return false
        val expected = if (action == "wheel") 4 else 3
        if (parts.size != expected) return false
        if (!finitePoint(parts.getOrNull(1), parts.getOrNull(2))) return false
        if (action == "wheel") {
            val dy = parts.getOrNull(3)?.toFloatOrNull() ?: return false
            if (!dy.isFinite() || kotlin.math.abs(dy) > WHEEL_LIMIT) return false
        }
        return true
    }

    private fun gateFingerAllowed(spec: String): Boolean {
        val parts = spec.split(",")
        if (parts.size != 3) return false
        if (parts[0] !in GATE_FINGER_ACTIONS) return false
        return finitePoint(parts[1], parts[2])
    }

    private fun fingerAllowed(spec: String): Boolean {
        val parts = spec.split(",")
        if (parts.size != 6) return false
        val values = parts.map { it.toFloatOrNull() ?: return false }
        if (!values[0].isFinite() || !values[1].isFinite() || !values[2].isFinite() || !values[3].isFinite()) return false
        if (kotlin.math.abs(values[0]) > COORD_LIMIT || kotlin.math.abs(values[1]) > COORD_LIMIT) return false
        if (kotlin.math.abs(values[0] + values[2]) > COORD_LIMIT || kotlin.math.abs(values[1] + values[3]) > COORD_LIMIT) return false
        if (kotlin.math.abs(values[2]) > DELTA_LIMIT || kotlin.math.abs(values[3]) > DELTA_LIMIT) return false
        val hold = values[4]
        if (!hold.isFinite() || hold < 0f || hold > HOLD_LIMIT) return false
        val steps = values[5]
        if (!steps.isFinite() || steps < 1f || steps > 64f || steps != Math.floor(steps.toDouble()).toFloat()) return false
        return true
    }

    private fun twoFingerAllowed(spec: String): Boolean {
        val parts = spec.split(",")
        if (parts.size != 3) return false
        val values = parts.map { it.toFloatOrNull() ?: return false }
        if (kotlin.math.abs(values[0]) > COORD_LIMIT || kotlin.math.abs(values[1]) > COORD_LIMIT) return false
        if (!values[2].isFinite() || kotlin.math.abs(values[2]) > DELTA_LIMIT || values[2] == 0f) return false
        return true
    }

    private fun pinchAllowed(spec: String): Boolean {
        val parts = spec.split(",")
        if (parts.size != 2) return false
        return finitePoint(parts[0], parts[1])
    }

    private fun fixtureTargetAllowed(spec: String): Boolean {
        val parts = spec.split(",")
        if (parts.size != 3) return false
        if (parts[0] !in FIXTURE_TARGETS) return false
        return finitePoint(parts[1], parts[2])
    }

    /**
     * The only test cookie value shapes: a 16..64 hex marker, or the host lane's random
     * `m<base36-case><base36-time>` token (at most 24 alphanumerics). No semicolon, space or cookie
     * name is ever part of the value.
     */
    fun isTestCookieValue(value: String): Boolean {
        if (value.length !in 3..64) return false
        if (Regex("^[0-9A-Fa-f]{16,64}$").matches(value)) return true
        return Regex("^m[A-Za-z0-9]{2,23}$").matches(value)
    }

    /** Focused fixture site marker is fixed 16..64 hex, never a host auth value. */
    fun isSiteStateMarker(value: String): Boolean = Regex("^[0-9A-Fa-f]{16,64}$").matches(value)

    private fun finitePoint(x: String?, y: String?): Boolean {
        val px = x?.toFloatOrNull() ?: return false
        val py = y?.toFloatOrNull() ?: return false
        return px.isFinite() && py.isFinite() &&
            kotlin.math.abs(px) <= COORD_LIMIT && kotlin.math.abs(py) <= COORD_LIMIT
    }

    /** Cases are names supplied by the parent as a bounded comma list; every one must be true. */
    fun missingCases(summary: JSONObject, requiredCases: List<String>): List<String> =
        requiredCases.filter { !summary.optBoolean(it, false) }

    /**
     * Accepts a non-empty subset of the frozen eight names. Unknown names, duplicates and an empty
     * list are rejected; the reported scope is lifecycle, retirement, all (strict eight) or custom.
     * A partial request is never a full-matrix claim.
     */
    fun parseCases(raw: String?): CasesParsed {
        val names = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return CasesParsed.Rejected("cases-empty")
        if (names.size != names.distinct().size) return CasesParsed.Rejected("cases-duplicate")
        val unknown = names.filter { it !in EXPECTED_CASES }
        if (unknown.isNotEmpty()) return CasesParsed.Rejected("cases-unknown:${unknown.joinToString(",")}")
        val set = names.toSet()
        val scope = when {
            set == LIFECYCLE_CASE_NAMES.toSet() -> "lifecycle"
            set == RETIREMENT_CASE_NAMES.toSet() -> "retirement"
            set == EXPECTED_CASES.toSet() -> "all"
            else -> "custom"
        }
        return CasesParsed.Ok(CaseRequest(names, scope, set != EXPECTED_CASES.toSet()))
    }

    fun result(runId: String, id: String, ok: Boolean, data: JSONObject?, errorCode: String?, errorMessage: String?): JSONObject {
        val body = JSONObject().put("runId", runId).put("id", id).put("ok", ok)
        if (data != null) body.put("data", data)
        if (!ok) {
            body.put("error", JSONObject()
                .put("code", errorCode ?: "failed")
                .put("message", (errorMessage ?: "failed").take(300)))
        }
        return body
    }
}

/**
 * Evidence model for the regression producer. Pure so the done/failed phase and counter fields are
 * tested without a device. It never writes geometry-gate counter claims: the geometry hold races are
 * host-only and explicitly reported as not tested here.
 */
internal object LocalProviderRegressionEvidence {
    const val HOLD_PROBE = "host-only-not-tested"

    fun phase(finished: Boolean): String = if (finished) "done" else "failed"

    /**
     * One source of case provenance for both `status.json` and `evidence.json`. The values are the
     * exact request (cases/scope/partial), never defaults: a partial lifecycle/retirement run stays
     * partial, and only the full eight-name request is non-partial.
     */
    fun caseMetadata(expectedCases: List<String>, caseScope: String, casePartial: Boolean): JSONObject =
        JSONObject()
            .put("expectedCases", JSONArray(expectedCases))
            .put("caseScope", caseScope)
            .put("casePartial", casePartial)

    fun applyCaseMetadata(
        body: JSONObject,
        expectedCases: List<String>,
        caseScope: String,
        casePartial: Boolean,
    ): JSONObject {
        val metadata = caseMetadata(expectedCases, caseScope, casePartial)
        val keys = metadata.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            body.put(key, metadata.get(key))
        }
        return body
    }

    fun build(
        runId: String,
        finished: Boolean,
        failure: String?,
        expectedCases: List<String>,
        caseScope: String,
        casePartial: Boolean,
        caseSummary: JSONObject,
        toolCommands: Int,
        deviceCommands: Int,
        receiptCount: Int,
        selectedSessionId: String?,
        sessions: Collection<String>,
        decoratedPages: Int,
    ): JSONObject {
        val sessionList = JSONArray()
        sessions.forEach { sessionList.put(it) }
        val body = JSONObject()
            .put("runId", runId)
            .put("phase", phase(finished))
            .put("failure", failure ?: JSONObject.NULL)
            .put("caseSummary", JSONObject(caseSummary.toString()))
            .put("toolCommands", toolCommands)
            .put("deviceCommands", deviceCommands)
            .put("receipts", receiptCount)
            .put("selectedSessionId", selectedSessionId ?: JSONObject.NULL)
            .put("sessions", sessionList)
            .put("decoratedPages", decoratedPages)
            .put("geometryHoldProbe", HOLD_PROBE)
        return applyCaseMetadata(body, expectedCases, caseScope, casePartial)
    }
}

/** Pure shape/bool checks for the profile-isolation command; actual CookieManager reads stay on device. */
internal object ProfileIsolationEvidence {
    fun check(
        writer: String,
        ownPresent: Boolean,
        otherWriters: List<String>,
        otherPresent: List<Boolean>,
    ): JSONObject = JSONObject()
        .put("writer", writer)
        .put("ownPresent", ownPresent)
        .put("otherWriters", JSONArray(otherWriters))
        .put("otherPresent", JSONArray(otherPresent))

    fun cleaned(anyStillPresent: Boolean): Boolean = !anyStillPresent
}
