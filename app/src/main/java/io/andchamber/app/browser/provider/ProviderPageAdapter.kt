package io.andchamber.app.browser.provider

import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserErrorCode
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.ui.browser.BrowserDesktopViewport
import io.andchamber.app.ui.browser.BrowserDriverLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Turns one claimed provider request into a page result.
 *
 * Open presents an allowed GET and is not a grant. Every other action returns
 * [ProviderCodes.AUTHORIZATION_REQUIRED] before any page read or screenshot when the grant does
 * not bind this connection, session and visible tab. Selectors are accepted only as a complete
 * `[data-andchamber-ref="<ref>"]` that names a ref from the current snapshot. The ref string is
 * passed to the driver; the selector is never evaluated as CSS.
 *
 * Manual mode does not grant the agent. Human navigation is decided by [io.andchamber.app.ui.browser.MainFrameGate].
 */
class OpenChamberPageAdapter(
    private val driver: BrowserDriver,
    private val present: suspend (ProviderTab) -> Boolean = { true },
    private val instanceKey: String = "local",
    private val clock: () -> Long = { 0L },
    private val viewportMode: () -> String = { "fill" },
    private val viewportSize: () -> Pair<Int, Int> = { 0 to 0 },
    private val documentFields: (suspend (String) -> Map<String, PageField>?)? = null,
    private val requireCurrentDom: Boolean = false,
    private val authority: (() -> AuthorityStamp)? = null,
    private val onOutcomeUnknown: () -> Unit = {},
    private val onDispatch: () -> Unit = {},
    private val onDispatchDone: (BrowserReply?) -> Unit = {},
) {
    var reads: Int = 0
        private set
    var captures: Int = 0
        private set

    private var snapshotRefs: Set<String> = emptySet()
    private var snapshotNames: Map<String, List<String>> = emptyMap()
    private var snapshotFields: Map<String, PageField> = emptyMap()
    private var documentWitness: String? = null
    private var opens: Int = 0

    suspend fun execute(
        request: ProviderRequest,
        registry: ProviderRegistry,
        visibleTabId: String?,
        mode: ProviderMode,
        deadlineElapsed: Long,
    ): ProviderResult {
        if (clock() >= deadlineElapsed) return ProviderResult(false, errorCode = ProviderCodes.DEADLINE)
        val session = request.sessionId?.takeIf { it.isNotBlank() }
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        if (request.action == "browser.open") {
            return openClaimed(request, registry, session).result
        }
        // Manual mode is a person using the page. It is never an agent grant, even if a stale
        // grant object is still present. Refuse before any read or screenshot.
        if (mode == ProviderMode.MANUAL || !registry.authorized(request, request.connectionId, visibleTabId)) {
            return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
        }
        if (request.action !in PROVIDER_SUPPORTED_ACTIONS) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_ACTION)
        }
        val refused = unsupportedParameter(request)
        if (refused != null) return refused
        return when (request.action) {
            "browser.snapshot" -> snapshot(registry, session, request.parameters["tabId"])
            "browser.click" -> click(request)
            "browser.type" -> type(request, deadlineElapsed)
            "browser.scroll" -> scroll(request)
            "browser.capture" -> capture()
            "browser.resize" -> resize(request)
            else -> ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_ACTION)
        }
    }

    /** Gate 1 payload. Success is the action object. Failure is `{ok:false, code}` and never a page read. */
    suspend fun handle(
        request: ProviderRequest,
        registry: ProviderRegistry,
        visibleTabId: String?,
        mode: ProviderMode,
        deadlineElapsed: Long,
    ): JsonObject {
        val result = execute(request, registry, visibleTabId, mode, deadlineElapsed)
        if (result.ok) return result.data
        return buildJsonObject {
            put("ok", false)
            put("code", result.errorCode ?: ProviderCodes.OUTCOME_UNKNOWN)
            result.message?.let { put("message", it) }
        }
    }

    /** The witness a later grant must name. Manual completion does not copy the previous one. */
    fun currentWitness(): String? = documentWitness

    fun noteWitness(id: String?) {
        if (id != documentWitness) clearRefs()
        documentWitness = id
    }

    /** Takeover and a manual document start drop every ref from this page. A later snapshot must be fresh. */
    fun clearRefs() {
        snapshotRefs = emptySet()
        snapshotNames = emptyMap()
        snapshotFields = emptyMap()
        documentWitness = null
    }

    /**
     * One claimed open: allocate or validate the registry tab, present it, then load once.
     * Presenting is not a second navigation. An explicit missing tab id is rejected, and another
     * owner's page is never replaced.
     */
    suspend fun openClaimed(
        request: ProviderRequest,
        registry: ProviderRegistry,
        session: String,
    ): OpenedPage {
        val url = request.parameters["url"].orEmpty()
        if (url.isBlank()) return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST))
        val viewport = request.parameters["viewport"]
        if (viewport != null && viewport != "fill" && viewport != "desktop" && viewport != "mobile") {
            return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER))
        }
        val width = request.parameters[BrowserDriverLogic.PARAM_WIDTH]?.takeIf { it.isNotBlank() }
        val height = request.parameters[BrowserDriverLogic.PARAM_HEIGHT]?.takeIf { it.isNotBlank() }
        if (width != null || height != null) {
            if (viewport != "desktop") {
                return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "width and height require desktop"))
            }
            when (BrowserDesktopViewport.parse(width, height)) {
                is BrowserDesktopViewport.Parse.Invalid ->
                    return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "invalid desktop size"))
                else -> Unit
            }
        }
        val named = request.parameters["tabId"]?.takeIf { it.isNotBlank() }
        if (named != null) {
            if (registry.ownedByOther(session, request.directory, named)) {
                return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "other session"))
            }
            if (registry.find(named) == null) {
                return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "unknown tab"))
            }
        }
        val tab = try {
            registry.open(session, request.directory, instanceKey, url, named)
        } catch (_: IllegalStateException) {
            return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "other session"))
        }
        if (registry.currentGrant()?.tabId == tab.tabId) registry.revoke()
        noteWitness(null)
        val presented = present(tab)
        if (!presented) return OpenedPage(ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "not presented"), tab)
        opens += 1
        registry.updateUrl(tab.tabId, url)
        return OpenedPage(ProviderResult(ok = true, data = openPayload(tab.tabId)), tab)
    }

    private suspend fun snapshot(registry: ProviderRegistry, session: String, tabId: String?): ProviderResult {
        reads += 1
        val stamp = authority?.invoke()
        val reply = driver.execute(BrowserRequest("snapshot", BrowserMethod.SNAPSHOT))
        if (stamp != null && !sameAuthority(stamp)) return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
        if (!reply.ok) return fromDriver(reply)
        val raw = reply.result[BrowserDriverLogic.RESULT_SNAPSHOT]
        val parsed = when {
            raw != null -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            reply.result.containsKey("url") || reply.result.containsKey("elements") -> snapshotFromFields(reply.result)
            else -> null
        } ?: return ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN)
        val elements = parsed["elements"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        val refs = linkedSetOf<String>()
        val names = linkedMapOf<String, MutableList<String>>()
        val fields = linkedMapOf<String, PageField>()
        val mapped = buildJsonArray {
            for (item in elements) {
                val obj = item as? JsonObject ?: continue
                val ref = obj["ref"]?.jsonPrimitive?.contentOrNull?.takeIf { BrowserDriverLogic.isRef(it) } ?: continue
                refs += ref
                val role = obj["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                names.getOrPut(name) { mutableListOf() }.add(ref)
                val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                fields[ref] = PageField(role = role, type = type, name = name)
                add(buildJsonObject {
                    put("role", role)
                    put("name", name)
                    obj["value"]?.jsonPrimitive?.contentOrNull?.let { put("value", it) }
                    put("selector", refSelector(ref))
                    if (type.isNotBlank()) put("type", type)
                })
            }
        }
        if (stamp != null && !sameAuthority(stamp)) return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
        snapshotRefs = refs
        snapshotNames = names
        snapshotFields = fields
        val tab = registry.tabFor(session, tabId)
        val url = parsed["url"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { tab?.url.orEmpty() }
        if (tab != null && url.isNotBlank()) registry.updateUrl(tab.tabId, url)
        return ProviderResult(
            ok = true,
            data = buildJsonObject {
                put("url", url)
                put("title", parsed["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("text", parsed["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("elements", mapped)
                put("tabs", tabsPayload(registry, session))
                parsed["layoutViewport"]?.let { put("layoutViewport", it) }
                parsed["visualViewport"]?.let { put("visualViewport", it) }
                put("drivable", true)
            },
        )
    }

    private suspend fun click(request: ProviderRequest): ProviderResult {
        val stamp = authority?.invoke() ?: AuthorityStamp(1L, "document", "tab", true)
        val ref = resolveClick(request) ?: return refRefusal(request.parameters["selector"])
        val field = currentField(ref)
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        if (field == null) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "field check failed")
        }
        if (isSubmissionControl(field)) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "form submission refused")
        }
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        val reply = dispatched {
            driver.execute(
            BrowserRequest(
                id = request.requestId,
                method = BrowserMethod.CLICK,
                params = mapOf(BrowserDriverLogic.PARAM_REF to ref),
            ),
        )
        }
        // No reply means the page may have acted without our seeing it: outcome unknown. A click
        // has an external effect, so the page-level uncertainty is reported to the presenter.
        if (reply == null) return outcomeUnknownAfterDispatch(mutation = true)
        // A reply arrived, so the outcome is known; the grant is gone, so the result is not handed out.
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        if (!reply.ok) return fromDriver(reply)
        return ProviderResult(ok = true, data = buildJsonObject { put("clicked", refSelector(ref)) })
    }

    private suspend fun type(request: ProviderRequest, deadlineElapsed: Long): ProviderResult {
        val selector = request.parameters["selector"]
        val ref = ProviderProtocol.selectorRef(selector)
        if (ref == null || ref !in snapshotRefs) return refRefusal(selector)
        val value = request.parameters["value"] ?: request.parameters["text"]
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        val submit = request.parameters["submit"] == "true"
        val stamp = authority?.invoke() ?: AuthorityStamp(1L, "document", "tab", true)
        val field = currentField(ref)
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        if (field == null) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "field check failed")
        }
        if (isSensitive(field) || !isEditable(field) || isSubmissionControl(field)) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "refused field")
        }
        // A submit receipt must live inside the caller's own budget, not a fixed short window.
        val remaining = (deadlineElapsed - clock()).coerceAtLeast(1L)
        val reply = dispatched {
            driver.execute(
            BrowserRequest(
                id = request.requestId,
                method = BrowserMethod.TYPE,
                params = buildMap {
                    put(BrowserDriverLogic.PARAM_REF, ref)
                    put(BrowserDriverLogic.PARAM_TEXT, value)
                    // Only an exact true is a submit; missing/false stays the original TYPE action.
                    if (submit) put(BrowserDriverLogic.PARAM_SUBMIT, "true")
                },
                timeoutMs = remaining,
            ),
        )
        }
        // No reply means the page may have acted without our seeing it: outcome unknown. A type
        // has an external effect, so the page-level uncertainty is reported to the presenter.
        if (reply == null) return outcomeUnknownAfterDispatch(mutation = true)
        // A reply arrived, so the outcome is known; the grant is gone, so the result is not handed out.
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        if (!reply.ok) return fromDriver(reply)
        if (submit) {
            // Success here is only "value set and Enter dispatched once". Navigation is the
            // presenter's separate, real-GET receipt and must not be claimed by the adapter.
            if (reply.result[BrowserDriverLogic.RESULT_ENTER_DISPATCHED] != "true") {
                return ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN, message = "enter not dispatched")
            }
            return ProviderResult(ok = true, data = buildJsonObject {
                put("typed", refSelector(ref))
                put("enterDispatched", true)
            })
        }
        return ProviderResult(ok = true, data = buildJsonObject { put("typed", refSelector(ref)) })
    }

    private suspend fun resize(request: ProviderRequest): ProviderResult {
        val viewport = request.parameters[BrowserDriverLogic.PARAM_VIEWPORT]
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "missing viewport")
        val width = request.parameters[BrowserDriverLogic.PARAM_WIDTH]?.takeIf { it.isNotBlank() }
        val height = request.parameters[BrowserDriverLogic.PARAM_HEIGHT]?.takeIf { it.isNotBlank() }
        val reply = try {
            driver.execute(
                BrowserRequest(
                    id = request.requestId,
                    method = BrowserMethod.RESIZE,
                    params = buildMap {
                        put(BrowserDriverLogic.PARAM_VIEWPORT, viewport)
                        if (width != null) put(BrowserDriverLogic.PARAM_WIDTH, width)
                        if (height != null) put(BrowserDriverLogic.PARAM_HEIGHT, height)
                    },
                    timeoutMs = request.timeoutMs,
                ),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProviderResult(false, errorCode = ProviderCodes.BROWSER_PAGE_UNAVAILABLE)
        }
        if (!reply.ok) return fromDriver(reply)
        // The pair is the configured request, not a measured DOM claim. Snapshot probe fields are
        // the measured truth.
        return ProviderResult(
            ok = true,
            data = buildJsonObject {
                put("accepted", true)
                put("viewport", reply.result[BrowserDriverLogic.PARAM_VIEWPORT] ?: viewport)
                put("mode", if (reply.result["desktop"] == "true") "desktop" else "mobile")
                reply.result[BrowserDriverLogic.PARAM_WIDTH]?.toIntOrNull()?.let { put("desktopWidth", it) }
                reply.result[BrowserDriverLogic.PARAM_HEIGHT]?.toIntOrNull()?.let { put("desktopHeight", it) }
                put("geometry", "pending")
            },
        )
    }

    private suspend fun scroll(request: ProviderRequest): ProviderResult {
        if (request.parameters.containsKey("selector")) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER)
        }
        val direction = request.parameters["direction"] ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        if (direction !in BrowserDriverLogic.SCROLL_DIRECTIONS) {
            return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        }
        val stamp = authority?.invoke() ?: AuthorityStamp(1L, "document", "tab", true)
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        val reply = dispatched {
            driver.execute(
                BrowserRequest(
                    id = request.requestId,
                    method = BrowserMethod.SCROLL,
                    params = mapOf(BrowserDriverLogic.PARAM_DIRECTION to direction),
                ),
            )
        }
        // A scroll that got no reply may or may not have moved the page. Its own readback was not
        // confirmed, so the increment is never replayed; that alone does not make the document
        // invalid. (It is not a claim that a JS scroll handler had no side effect.)
        if (reply == null) return outcomeUnknownAfterDispatch(mutation = false)
        if (!sameAuthority(stamp)) return revokedBeforeDispatch()
        if (!reply.ok) return fromDriver(reply)
        return ProviderResult(ok = true, data = buildJsonObject { put("direction", direction) })
    }

    private suspend fun capture(): ProviderResult {
        captures += 1
        val stamp = authority?.invoke()
        val reply = driver.execute(BrowserRequest("capture", BrowserMethod.CAPTURE))
        if (stamp != null && !sameAuthority(stamp)) return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
        if (!reply.ok) return fromDriver(reply)
        val (fallbackWidth, fallbackHeight) = viewportSize()
        val width = reply.result["width"]?.toIntOrNull() ?: fallbackWidth
        val height = reply.result["height"]?.toIntOrNull() ?: fallbackHeight
        val cssWidth = reply.result["cssWidth"]?.toIntOrNull()
        val cssHeight = reply.result["cssHeight"]?.toIntOrNull()
        val visualWidth = reply.result["visualWidth"]?.toFloatOrNull()
        val visualHeight = reply.result["visualHeight"]?.toFloatOrNull()
        val dpr = reply.result["dpr"]?.toFloatOrNull()
        val scale = reply.result["scale"]?.toFloatOrNull()
        if (cssWidth == null || cssHeight == null || visualWidth == null || visualHeight == null || dpr == null || scale == null) {
            return ProviderResult(false, errorCode = ProviderCodes.BROWSER_PAGE_UNAVAILABLE, message = "geometry not ready")
        }
        return ProviderResult(
            ok = true,
            data = buildJsonObject {
                put("base64", reply.result[BrowserDriverLogic.RESULT_DATA].orEmpty())
                put("mime", reply.result[BrowserDriverLogic.RESULT_MIME] ?: BrowserDriverLogic.MIME_PNG)
                put("url", reply.result[BrowserDriverLogic.RESULT_URL].orEmpty())
                put("title", reply.result["title"].orEmpty())
                put("width", width)
                put("height", height)
                put("cssWidth", cssWidth)
                put("cssHeight", cssHeight)
                put("dpr", dpr)
                put("viewport", reply.result["viewport"] ?: viewportMode())
                put("css", buildJsonObject {
                    put("width", cssWidth)
                    put("height", cssHeight)
                    put("visualWidth", visualWidth)
                    put("visualHeight", visualHeight)
                    put("offsetLeft", reply.result["offsetLeft"]?.toFloatOrNull() ?: 0f)
                    put("offsetTop", reply.result["offsetTop"]?.toFloatOrNull() ?: 0f)
                    put("dpr", dpr)
                    put("scale", scale)
                    put("mode", reply.result["viewport"] ?: viewportMode())
                })
            },
        )
    }

    /** A well-formed ref that is not in the current snapshot is expired; anything else is unsupported. */
    private fun refRefusal(selector: String?): ProviderResult =
        if (ProviderProtocol.selectorRef(selector) != null) {
            ProviderResult(false, errorCode = ProviderCodes.REF_EXPIRED, message = "take a new snapshot")
        } else {
            ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER)
        }

    private fun resolveClick(request: ProviderRequest): String? {
        val selector = request.parameters["selector"]
        val text = request.parameters["text"]
        if (selector != null) {
            val ref = ProviderProtocol.selectorRef(selector) ?: return null
            return ref.takeIf { it in snapshotRefs }
        }
        if (text.isNullOrBlank()) return null
        val matches = snapshotNames[text].orEmpty()
        return matches.singleOrNull()
    }

    private fun unsupportedParameter(request: ProviderRequest): ProviderResult? {
        if (request.action == "browser.resize") {
            val viewport = request.parameters["viewport"]
            if (viewport != "desktop" && viewport != "mobile" && viewport != "fill") {
                return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER)
            }
            val width = request.parameters[BrowserDriverLogic.PARAM_WIDTH]?.takeIf { it.isNotBlank() }
            val height = request.parameters[BrowserDriverLogic.PARAM_HEIGHT]?.takeIf { it.isNotBlank() }
            if (width != null || height != null) {
                if (viewport != "desktop") {
                    return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "width and height require desktop")
                }
                when (BrowserDesktopViewport.parse(width, height)) {
                    is BrowserDesktopViewport.Parse.Invalid ->
                        return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "invalid desktop size")
                    else -> Unit
                }
            }
            return null
        }
        if (request.action == "browser.snapshot" && request.parameters.containsKey("selector")) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER)
        }
        val submit = request.parameters["submit"]
        if (request.action == "browser.type") {
            if (submit != null && submit != "true" && submit != "false") {
                return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "submit must be true or false")
            }
        } else if (submit != null) {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "submit is only for type")
        }
        if (request.parameters["sensitive"] == "true") {
            return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER, message = "credential refused")
        }
        return null
    }

    private fun fromDriver(reply: BrowserReply): ProviderResult {
        val code = when (reply.error?.code) {
            BrowserErrorCode.INVALID_REQUEST -> ProviderCodes.INVALID_REQUEST
            BrowserErrorCode.UNSUPPORTED_PARAMETER -> ProviderCodes.UNSUPPORTED_PARAMETER
            BrowserErrorCode.TIMEOUT -> ProviderCodes.DEADLINE
            BrowserErrorCode.OUTCOME_UNKNOWN -> ProviderCodes.OUTCOME_UNKNOWN
            BrowserErrorCode.REVOKED, BrowserErrorCode.TAKEN_OVER, BrowserErrorCode.DISABLED -> ProviderCodes.AUTHORIZATION_REQUIRED
            BrowserErrorCode.NOT_VISIBLE, BrowserErrorCode.DRIVER -> ProviderCodes.BROWSER_PAGE_UNAVAILABLE
            else -> ProviderCodes.OUTCOME_UNKNOWN
        }
        android.util.Log.w("AndChamberProvider", "action=driver code=$code class=${reply.error?.code?.name ?: "none"}")
        return ProviderResult(false, errorCode = code)
    }

    private fun sameAuthority(stamp: AuthorityStamp): Boolean {
        val now = authority?.invoke() ?: stamp
        return now.grantEpoch == stamp.grantEpoch && now.grantEpoch != 0L &&
            now.documentId == stamp.documentId && now.tabId == stamp.tabId && now.granted &&
            now.geometryEpoch == stamp.geometryEpoch
    }

    private suspend fun dispatched(block: suspend () -> BrowserReply): BrowserReply? {
        onDispatch()
        return try {
            block().also { onDispatchDone(it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The page may already have acted: mark outcome unknown, then let cancellation propagate.
            onDispatchDone(null)
            throw cancelled
        } catch (_: Exception) {
            onDispatchDone(null)
            null
        }
    }

    /** Revoked before anything reached the page: nothing happened, so the page stays trustworthy. */
    private fun revokedBeforeDispatch(): ProviderResult =
        ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)

    /**
     * No reply after the action reached the page. Only a click/type may have changed something
     * outside the page, so only that reports a page-level uncertainty; a scroll keeps the page
     * trustworthy for this action, is never replayed and does not by itself prove the document
     * invalid.
     */
    private fun outcomeUnknownAfterDispatch(mutation: Boolean): ProviderResult {
        if (mutation) onOutcomeUnknown()
        android.util.Log.w(
            "AndChamberProvider",
            "action=${if (mutation) "mutate" else "scroll"} code=${ProviderCodes.OUTCOME_UNKNOWN} class=Revoked",
        )
        return ProviderResult(false, errorCode = ProviderCodes.OUTCOME_UNKNOWN)
    }

    private fun openPayload(tabId: String) = buildJsonObject {
        put("opened", true)
        put("tabId", tabId)
        put("drivable", false)
        put("reason", "authorization_required")
        put("supportedActions", buildJsonArray {
            PROVIDER_SUPPORTED_ACTIONS.forEach { add(JsonPrimitive(it)) }
        })
    }

    /** A driver that already split the snapshot into string fields, matching a direct page map. */
    private fun snapshotFromFields(fields: Map<String, String>): JsonObject = buildJsonObject {
        put("url", fields["url"].orEmpty())
        put("title", fields["title"].orEmpty())
        put("text", fields["text"].orEmpty())
        put("elements", runCatching { Json.parseToJsonElement(fields["elements"].orEmpty()).jsonArray }.getOrDefault(JsonArray(emptyList())))
    }

    private fun tabsPayload(registry: ProviderRegistry, session: String): JsonArray = buildJsonArray {
        val selected = registry.tabFor(session, null)?.tabId
        registry.tabs().forEach { tab ->
            add(buildJsonObject {
                put("id", tab.tabId)
                put("title", tab.title)
                put("url", tab.url)
                put("owner", tab.ownerSessionId)
                put("active", tab.tabId == selected && tab.ownerSessionId == session)
            })
        }
    }

    private suspend fun currentField(ref: String): PageField? {
        val live = documentFields?.invoke(ref)
        if (live != null) return live[ref]
        if (requireCurrentDom) return null
        return snapshotFields[ref]
    }

    private fun refSelector(ref: String): String = """[data-andchamber-ref="$ref"]"""

    private fun isSensitive(field: PageField): Boolean {
        val type = field.type.lowercase()
        val name = field.name.lowercase()
        return type == "password" || type == "file" || type == "hidden" ||
            type == "one-time-code" || type == "current-password" || type == "new-password" ||
            name.contains("password") || name.contains("secret") || name.contains("token")
    }

    private fun isEditable(field: PageField): Boolean =
        field.role == "input" || field.role == "textbox" || field.type == "text" || field.type == "email"

    private fun isSubmissionControl(field: PageField): Boolean {
        val type = field.type.lowercase()
        // type="button" never submits; the page script reports implicit form submit buttons.
        return field.submit || type == "submit" || type == "image"
    }

}

/** Current DOM description used before a click or type. A missing field is not authority. */
data class PageField(
    val role: String,
    val type: String,
    val name: String,
    val submit: Boolean = false,
)

/** Claimed open result plus the registry tab. Callers must not parse the wire payload for the id. */
data class OpenedPage(
    val result: ProviderResult,
    val tab: ProviderTab? = null,
)

/** The grant, document and tab an in-flight action must still name after every await. */
data class AuthorityStamp(
    val grantEpoch: Long,
    val documentId: String?,
    val tabId: String?,
    val granted: Boolean,
    val geometryEpoch: Long = 0L,
)

/**
 * Binds the page adapter to the existing executor slot. Does not change claim, transport, or result posting.
 * The client still decides when a request is claimed; this only turns that request into a page result.
 */
class PageProviderExecutor(
    private val pages: OpenChamberPageAdapter,
) : ProviderExecutor {
    override suspend fun execute(
        request: ProviderRequest,
        registry: ProviderRegistry,
        mode: ProviderMode,
        visibleTabId: String?,
        deadlineElapsed: Long,
        clock: () -> Long,
    ): ProviderResult = pages.execute(request, registry, visibleTabId, mode, deadlineElapsed)
}
