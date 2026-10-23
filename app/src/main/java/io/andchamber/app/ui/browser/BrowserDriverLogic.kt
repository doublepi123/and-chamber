package io.andchamber.app.ui.browser

import io.andchamber.app.browser.BrowserError
import io.andchamber.app.browser.BrowserErrorCode
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import io.andchamber.app.browser.UrlDenyReason
import io.andchamber.app.data.canonicalWebOrigin
import io.andchamber.app.runtime.ServerTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom

/**
 * Pure rules for the independent browser driver. No WebView, no Compose, no credentials.
 *
 * A document ref is a nonce, not a path into the page. The page keeps the real node in a private
 * closure. Caller text is inserted only as a JSON string. There is no caller-script entry point.
 */
internal object BrowserDriverLogic {
    const val PROFILE_NAME = "andchamber_browser_v1"
    const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    fun desktopViewportScript(width: Int = BrowserDesktopViewport.DEFAULT_WIDTH): String {
        require(width in BrowserDesktopViewport.MIN_WIDTH..BrowserDesktopViewport.MAX_WIDTH) { "desktop width" }
        return """
        (function(){
          if (window.__acDesktopViewport) {
            if (window.__acDesktopViewport.setWidth) window.__acDesktopViewport.setWidth($width);
            return;
          }
          var currentWidth = $width;
          // Capture the real device ratio once, before the meta applies. Re-reading it after the
          // meta could feed the page scale back into the scale and oscillate.
          var initialScale = 1 / ((typeof window.devicePixelRatio === "number" && window.devicePixelRatio > 0) ? window.devicePixelRatio : 1);
          var owned = null;
          var applying = false;
          function content(){
            return "width=" + currentWidth + ", initial-scale=" + initialScale + ", minimum-scale=" + initialScale;
          }
          function pin(){
            if (applying) return;
            applying = true;
            try {
              var head = document.head || document.documentElement;
              if (!head) return;
              if (!owned || !owned.isConnected) {
                owned = document.createElement('meta');
                owned.name = 'viewport';
                owned.setAttribute('data-ac-viewport', 'desktop');
                head.appendChild(owned);
              }
              var wanted = content();
              var nodes = document.querySelectorAll('meta[name="viewport"]');
              for (var i = 0; i < nodes.length; i++) {
                if (nodes[i].getAttribute('content') !== wanted) {
                  nodes[i].setAttribute('content', wanted);
                }
              }
              if (owned.getAttribute('content') !== wanted) {
                owned.setAttribute('content', wanted);
              }
            } finally { applying = false; }
          }
          pin();
          var root = document.documentElement;
          if (root && window.MutationObserver) {
            var observer = new MutationObserver(function(){ pin(); });
            observer.observe(root, {childList:true, subtree:true, attributes:true, attributeFilter:['content','name']});
            window.__acDesktopViewport = {
              stop: function(){ observer.disconnect(); if (owned) owned.remove(); window.__acDesktopViewport = null; },
              setWidth: function(w){ if (typeof w === "number" && isFinite(w) && w > 0 && w !== currentWidth){ currentWidth = w; pin(); } }
            };
          } else {
            window.__acDesktopViewport = {
              stop: function(){ if (owned) owned.remove(); window.__acDesktopViewport = null; },
              setWidth: function(w){ if (typeof w === "number" && isFinite(w) && w > 0 && w !== currentWidth){ currentWidth = w; pin(); } }
            };
          }
        })()
    """.trimIndent()
    }

    /** Updates the current document's injected desktop meta without reloading it. */
    fun desktopViewportUpdateScript(width: Int): String {
        require(width in BrowserDesktopViewport.MIN_WIDTH..BrowserDesktopViewport.MAX_WIDTH) { "desktop width" }
        return """
        (function(){
          var w = $width;
          var d = window.__acDesktopViewport;
          if (d && d.setWidth) { d.setWidth(w); return "updated"; }
          var dpr = (typeof window.devicePixelRatio === "number" && window.devicePixelRatio > 0) ? window.devicePixelRatio : 1;
          var content = "width=" + w + ", initial-scale=" + (1 / dpr) + ", minimum-scale=" + (1 / dpr);
          var nodes = document.querySelectorAll('meta[name="viewport"]');
          for (var i = 0; i < nodes.length; i++) {
            nodes[i].setAttribute('content', content);
          }
          return "fallback";
        })()
    """.trimIndent()
    }
    const val PARAM_URL = "url"
    const val PARAM_REF = "ref"
    const val PARAM_TEXT = "text"
    const val PARAM_DIRECTION = "direction"
    const val PARAM_SUBMIT = "submit"
    const val PARAM_VIEWPORT = "viewport"
    const val PARAM_WIDTH = "width"
    const val PARAM_HEIGHT = "height"
    const val RESULT_ENTER_DISPATCHED = "enterDispatched"
    const val RESULT_SNAPSHOT = "snapshot"
    const val RESULT_PHASE = "phase"
    const val RESULT_URL = "url"
    const val RESULT_MIME = "mimeType"
    const val RESULT_DATA = "data"
    const val MIME_PNG = "image/png"

    const val MAX_SNAPSHOT_CHARS = 48 * 1024
    const val MAX_CAPTURE_CHARS = 1_400_000
    const val MAX_TEXT_CHARS = 8 * 1024
    const val MAX_ELEMENTS = 80
    const val MAX_TEXT_SAMPLE = 12_000

    val SCROLL_DIRECTIONS = setOf("up", "down", "top", "bottom")

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Origins the page must never be asked to open: this device's OpenChamber, and saved remotes. */
    fun forbiddenOrigins(
        localUrl: String?,
        remotes: List<ServerTarget.Remote>,
    ): Set<String> {
        val raw = buildList {
            if (!localUrl.isNullOrBlank()) add(localUrl)
            remotes.forEach { add(it.baseUrl) }
        }
        return raw.mapNotNull { originForPolicy(it) }.toSet()
    }

    /**
     * Policy origins are `https://host:port` (port always present). A loopback OpenChamber URL is
     * already refused as non-public; it is still listed so a rewritten https form of the same host
     * cannot be treated as an ordinary site. Userinfo never becomes an origin.
     */
    fun originForPolicy(raw: String): String? {
        val canonical = canonicalWebOrigin(raw) ?: return null
        val https = if (canonical.startsWith("http://")) "https://" + canonical.removePrefix("http://") else canonical
        return BrowserUrlPolicy.canonicalOrigin(https)
    }

    fun requestError(code: BrowserErrorCode, message: String): BrowserReply =
        BrowserReply(ok = false, error = BrowserError(code, message))

    fun validate(request: BrowserRequest): BrowserReply? {
        if (request.id.isBlank()) return requestError(BrowserErrorCode.INVALID_REQUEST, "missing id")
        if (request.method != BrowserMethod.TYPE && request.params.containsKey(PARAM_SUBMIT)) {
            return requestError(BrowserErrorCode.UNSUPPORTED_PARAMETER, "submit is only for type")
        }
        return when (request.method) {
            BrowserMethod.NAVIGATE -> {
                val url = request.params[PARAM_URL]
                if (url.isNullOrBlank()) requestError(BrowserErrorCode.INVALID_REQUEST, "missing url") else null
            }
            BrowserMethod.SNAPSHOT, BrowserMethod.CAPTURE -> null
            BrowserMethod.CLICK -> requireRef(request)
            BrowserMethod.TYPE -> {
                val submit = request.params[PARAM_SUBMIT]
                when {
                    submit != null && submit != "true" && submit != "false" ->
                        requestError(BrowserErrorCode.UNSUPPORTED_PARAMETER, "submit must be true or false")
                    else -> requireRef(request) ?: requireText(request)
                }
            }
            BrowserMethod.RESIZE -> validateResize(request)
            BrowserMethod.SCROLL -> {
                val direction = request.params[PARAM_DIRECTION]
                if (direction !in SCROLL_DIRECTIONS) {
                    requestError(BrowserErrorCode.INVALID_REQUEST, "direction must be up, down, top or bottom")
                } else {
                    null
                }
            }
        }
    }

    private fun requireRef(request: BrowserRequest): BrowserReply? {
        val ref = request.params[PARAM_REF]
        return if (!isRef(ref)) requestError(BrowserErrorCode.INVALID_REQUEST, "missing ref") else null
    }

    fun isRef(ref: String?): Boolean {
        if (ref.isNullOrBlank() || ref.length > 80) return false
        return ref.all { it.isDigit() || it in 'a'..'f' || it == '-' }
    }

    private fun requireText(request: BrowserRequest): BrowserReply? {
        val text = request.params[PARAM_TEXT]
        return when {
            text == null -> requestError(BrowserErrorCode.INVALID_REQUEST, "missing text")
            text.length > MAX_TEXT_CHARS -> requestError(BrowserErrorCode.INVALID_REQUEST, "text too long")
            else -> null
        }
    }

    /** RESIZE carries only a viewport and an optional desktop width/height pair. Never clamps. */
    private fun validateResize(request: BrowserRequest): BrowserReply? {
        val viewport = request.params[PARAM_VIEWPORT]
        if (viewport !in setOf("desktop", "mobile", "fill")) {
            return requestError(BrowserErrorCode.INVALID_REQUEST, "viewport must be desktop, mobile or fill")
        }
        val width = request.params[PARAM_WIDTH]
        val height = request.params[PARAM_HEIGHT]
        if (width == null && height == null) return null
        if (viewport != "desktop") {
            return requestError(BrowserErrorCode.UNSUPPORTED_PARAMETER, "width and height require desktop")
        }
        return when (BrowserDesktopViewport.parse(width, height)) {
            is BrowserDesktopViewport.Parse.Invalid ->
                requestError(BrowserErrorCode.UNSUPPORTED_PARAMETER, "invalid desktop size")
            else -> null
        }
    }

    /**
     * Fixed templates. Each template has one marker, replaced once with a JSON value. A caller
     * string that happens to contain a marker cannot be substituted again, and cannot close the
     * script string.
     */
    fun installScript(documentNonce: String): String {
        require(documentNonce.isHex()) { "document nonce" }
        return fillOnce(INSTALL_TEMPLATE, jsonQuote(documentNonce))
    }

    fun readScript(documentNonce: String, snapshotNonce: String, documentId: String): String {
        require(documentNonce.isHex() && snapshotNonce.isHex() && documentId.isHex()) { "nonce" }
        val payload = buildJsonObject {
            put("doc", documentNonce)
            put("snap", snapshotNonce)
            put("documentId", documentId)
        }.toString()
        return fillOnce(READ_TEMPLATE, payload)
    }

    fun actionScript(
        documentNonce: String,
        documentId: String,
        action: String,
        ref: String,
        text: String,
        direction: String,
    ): String {
        require(documentNonce.isHex() && documentId.isHex()) { "nonce" }
        require(action == "click" || action == "type" || action == "scroll") { "action" }
        val payload = buildJsonObject {
            put("doc", documentNonce)
            put("documentId", documentId)
            put("action", action)
            put("ref", ref)
            put("text", text)
            put("direction", direction)
        }.toString()
        return fillOnce(ACTION_TEMPLATE, payload)
    }

    /**
     * Typing for `submit:true`. The same native input value setter is used, but only after the
     * install slot has re-checked that this is a visible, non-sensitive, editable single-line
     * `<input>` whose effective form would submit as a same-tab GET. It never submits in JS: the
     * driver follows with one native Enter. A refusal happens before the value is changed.
     */
    fun submitTypeScript(
        documentNonce: String,
        documentId: String,
        ref: String,
        text: String,
    ): String {
        require(documentNonce.isHex() && documentId.isHex()) { "nonce" }
        val payload = buildJsonObject {
            put("doc", documentNonce)
            put("documentId", documentId)
            put("ref", ref)
            put("text", text)
        }.toString()
        return fillOnce(SUBMIT_TYPE_TEMPLATE, payload)
    }

    /** Re-reads the same ref's DOM focus and submit safety immediately before one native Enter. */
    fun enterReadyScript(
        documentNonce: String,
        documentId: String,
        ref: String,
    ): String {
        require(documentNonce.isHex() && documentId.isHex()) { "nonce" }
        val payload = buildJsonObject {
            put("doc", documentNonce)
            put("documentId", documentId)
            put("ref", ref)
        }.toString()
        return fillOnce(ENTER_READY_TEMPLATE, payload)
    }

    /** Replaces the single marker. The inserted JSON is never scanned for further markers. */
    fun fillOnce(template: String, json: String): String {
        val at = template.indexOf(ARG_TOKEN)
        require(at >= 0 && template.indexOf(ARG_TOKEN, at + ARG_TOKEN.length) < 0) { "template marker" }
        return template.substring(0, at) + json + template.substring(at + ARG_TOKEN.length)
    }

    fun jsonString(vararg values: String): String = values.joinToString(",") { jsonQuote(it) }

    fun jsonQuote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    fun parseScriptResult(raw: String?): ScriptResult {
        if (raw.isNullOrBlank() || raw == "null") return ScriptResult.Failed("empty script result")
        val text = unwrapJsString(raw)
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return ScriptResult.Failed("script result was not json")
        }
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            val message = obj["error"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "script refused"
            return ScriptResult.Failed(message)
        }
        return ScriptResult.Ok(obj)
    }

    fun snapshotMap(obj: JsonObject): Map<String, String> =
        mapOf(RESULT_SNAPSHOT to boundSnapshot(obj).toString())

    fun boundSnapshot(obj: JsonObject): JsonObject {
        val elements = obj["elements"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        val kept = buildJsonArray {
            for (index in 0 until minOf(elements.size, MAX_ELEMENTS)) {
                val item = elements[index] as? JsonObject ?: continue
                add(boundElement(item))
            }
        }
        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_TEXT_SAMPLE)
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true || elements.size > MAX_ELEMENTS
        // Actual DOM readback only: no DPR or screen value is invented here.
        val viewports = buildJsonObject {
            viewportValue(obj, "layoutWidth", "layoutHeight", integer = true)?.let { put("layoutViewport", it) }
            viewportValue(obj, "visualWidth", "visualHeight", integer = false)?.let { put("visualViewport", it) }
        }
        var copy = buildJsonObject {
            put("url", obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2_000))
            put("title", obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(300))
            put("text", text)
            put("elements", kept)
            put("truncated", truncated)
            for ((key, value) in viewports) put(key, value)
        }
        if (copy.toString().length > MAX_SNAPSHOT_CHARS) {
            copy = buildJsonObject {
                put("url", copy["url"]!!)
                put("title", copy["title"]!!)
                put("text", text.take(1_500))
                put("elements", kept)
                put("truncated", true)
                for ((key, value) in viewports) put(key, value)
            }
        }
        if (copy.toString().length > MAX_SNAPSHOT_CHARS) {
            copy = buildJsonObject {
                put("url", "")
                put("title", "")
                put("text", "")
                put("elements", JsonArray(emptyList()))
                put("truncated", true)
                for ((key, value) in viewports) put(key, value)
            }
        }
        return copy
    }

    /** One measured viewport size, or null when the page did not report a real positive value. */
    private fun viewportValue(
        obj: JsonObject,
        widthKey: String,
        heightKey: String,
        integer: Boolean,
    ): JsonObject? {
        val width: Number = if (integer) {
            obj[widthKey]?.jsonPrimitive?.intOrNull ?: return null
        } else {
            obj[widthKey]?.jsonPrimitive?.floatOrNull ?: return null
        }
        val height: Number = if (integer) {
            obj[heightKey]?.jsonPrimitive?.intOrNull ?: return null
        } else {
            obj[heightKey]?.jsonPrimitive?.floatOrNull ?: return null
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f || w > 100_000f || h > 100_000f) return null
        return buildJsonObject {
            if (integer) {
                put("width", width.toInt())
                put("height", height.toInt())
            } else {
                put("width", w)
                put("height", h)
            }
        }
    }

    private fun boundElement(item: JsonObject): JsonObject = buildJsonObject {
        put("ref", item["ref"]?.jsonPrimitive?.contentOrNull.orEmpty().take(80))
        put("role", item["role"]?.jsonPrimitive?.contentOrNull.orEmpty().take(24))
        put("name", item["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120))
        val value = item["value"]?.jsonPrimitive?.contentOrNull
        if (value != null) put("value", value.take(120))
    }

    /** A subresource or frame URL the page is about to load. Main-frame loads are decided elsewhere. */
    fun observeSubresource(policy: BrowserUrlPolicy, raw: String?): Observe {
        if (raw.isNullOrBlank()) return Observe.Allow
        val scheme = schemeOf(raw) ?: return Observe.Block
        if (scheme == "data" || scheme == "blob" || scheme == "about") return Observe.Allow
        if (scheme != "https") return Observe.Block
        return when (policy.checkSyntax(raw)) {
            is UrlDecision.Allow -> Observe.Allow
            is UrlDecision.Deny -> Observe.Block
        }
    }

    fun schemeOf(raw: String): String? =
        try {
            URI(raw).scheme?.lowercase()
        } catch (_: Exception) {
            raw.substringBefore(':', "").lowercase().ifBlank { null }
        }

    fun phaseLabel(progress: Int, error: String?, url: String?): String =
        when {
            !error.isNullOrBlank() -> "error"
            url.isNullOrBlank() -> "idle"
            progress in 0..99 -> "loading"
            else -> "ready"
        }

    fun denyMessage(reason: UrlDenyReason): String =
        when (reason) {
            UrlDenyReason.NOT_PUBLIC_HTTPS -> "Only public https addresses can be opened."
            UrlDenyReason.USERINFO -> "An address cannot include a name or password."
            UrlDenyReason.HOST -> "This host cannot be opened."
            UrlDenyReason.NON_PUBLIC_ADDRESS -> "Private and local addresses are blocked."
            UrlDenyReason.FORBIDDEN_ORIGIN -> "OpenChamber addresses are not opened here."
            UrlDenyReason.DNS -> "The address could not be checked."
        }

    /** evaluateJavascript wraps a string result in JSON quotes. Objects arrive as raw JSON text. */
    fun unwrapJsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return try {
                json.parseToJsonElement(trimmed).jsonPrimitive.content
            } catch (_: Exception) {
                trimmed
            }
        }
        return trimmed
    }

    private fun String.isHex(): Boolean = length == 32 && all { it in '0'..'9' || it in 'a'..'f' }

    private const val ARG_TOKEN = "__AC_ARG__"

    /**
     * Installed once per document nonce. Nodes live in this closure. Password and file fields are
     * never stored. iframe documents are not read. A new install replaces the closure, so old refs die.
     */
    private val INSTALL_TEMPLATE = """
        (function(){
          var doc = __AC_ARG__;
          var key = "__acB_" + doc;
          if (window[key] && window[key].doc === doc) return "same";
          var nodes = new Map();
          function box(el){
            if (!el || !el.isConnected) return false;
            var r = el.getBoundingClientRect();
            var s = getComputedStyle(el);
            return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none" && r.bottom > 0 && r.right > 0 && r.top < (innerHeight || 0) && r.left < (innerWidth || 0);
          }
          function roleOf(el){
            var tag = (el.tagName || "").toLowerCase();
            if (tag === "a") return "link";
            if (tag === "button") return "button";
            if (tag === "textarea") return "textbox";
            if (tag === "select") return "listbox";
            if (tag === "input") return "input";
            if (tag === "summary") return "summary";
            return "control";
          }
          function nameOf(el){
            return ((el.getAttribute("aria-label") || el.innerText || el.textContent || el.getAttribute("placeholder") || el.getAttribute("title") || el.getAttribute("alt") || "") + "").replace(/\s+/g, " ").trim().slice(0, 120);
          }
          function autocompleteOf(el){
            return ((el.getAttribute("autocomplete") || "") + "").toLowerCase();
          }
          function sensitiveField(el){
            var type = ((el.getAttribute("type") || "text") + "").toLowerCase();
            var name = ((el.getAttribute("name") || el.getAttribute("aria-label") || "") + "").toLowerCase();
            var auto = autocompleteOf(el);
            return type === "password" || type === "file" || type === "hidden" ||
              type === "one-time-code" || auto === "one-time-code" ||
              auto.indexOf("current-password") >= 0 || auto.indexOf("new-password") >= 0 ||
              name.indexOf("password") >= 0 || name.indexOf("secret") >= 0 || name.indexOf("token") >= 0;
          }
          // The Enter submit serializes every control of the target form. Refuse a form that would
          // send a credential or file field, even when the typed field itself is ordinary text.
          // This is a bounded scan of form.elements only: username and general text are not sensitive.
          function formSensitive(form){
            if (!form || !form.elements) return false;
            var list = form.elements;
            for (var i = 0; i < list.length; i++){
              var el = list[i];
              if (!el || !el.tagName) continue;
              var tag = (el.tagName || "").toLowerCase();
              if (tag !== "input" && tag !== "textarea" && tag !== "select") continue;
              var type = ((el.getAttribute("type") || "") + "").toLowerCase();
              var auto = autocompleteOf(el);
              if (type === "password" || type === "file") return true;
              if (auto === "one-time-code" || auto.indexOf("current-password") >= 0 ||
                  auto.indexOf("new-password") >= 0) return true;
              var name = ((el.getAttribute("name") || "") + "").toLowerCase();
              if (name.indexOf("password") >= 0) return true;
            }
            return false;
          }
          function selfTarget(value){
            var t = ((value || "") + "").toLowerCase();
            return t === "" || t === "_self" || t === "_top" || t === "_parent";
          }
          // The first submit button in tree order that belongs to this form, as implicit Enter uses.
          function defaultSubmitter(form){
            var list = document.querySelectorAll("button,input");
            for (var i = 0; i < list.length; i++){
              var el = list[i];
              if (el.form !== form) continue;
              var type = ((el.getAttribute("type") || "") + "").toLowerCase();
              var tag = (el.tagName || "").toLowerCase();
              if (type !== "submit" && !(tag === "button" && type === "")) continue;
              if (el.disabled) continue;
              return el;
            }
            return null;
          }
          // A single-line, non-credential input whose implicit Enter would be a same-tab GET.
          function submitProblem(field){
            var tag = (field.tagName || "").toLowerCase();
            if (tag !== "input") return "not single-line input";
            var type = ((field.getAttribute("type") || "text") + "").toLowerCase();
            if (type !== "text" && type !== "search" && type !== "email" &&
                type !== "url" && type !== "tel" && type !== "number") return "not single-line input";
            if (field.disabled || field.readOnly) return "not actionable";
            if (sensitiveField(field)) return "refused field";
            var form = field.form;
            if (!form) return null;
            var method = ((form.method || "get") + "").toLowerCase();
            if (method !== "get") return "form method";
            if (!selfTarget(form.getAttribute("target"))) return "foreign target";
            var submitter = defaultSubmitter(form);
            if (submitter){
              var override = ((submitter.getAttribute("formmethod") || "") + "").toLowerCase();
              if (override && override !== "get") return "form method override";
              if (!selfTarget(submitter.getAttribute("formtarget"))) return "foreign target";
              if (!httpsAction(submitter.getAttribute("formaction"))) return "form action";
            }
            if (!httpsAction(form.getAttribute("action"))) return "form action";
            // Enter would serialize the whole form, so other credential/file controls are refused.
            if (formSensitive(form)) return "refused field";
            return null;
          }
          function httpsAction(value){
            var raw = ((value || "") + "").trim();
            if (!raw) return true;
            try {
              var resolved = new URL(raw, document.baseURI);
              return resolved.protocol === "https:";
            } catch (e) {
              return false;
            }
          }
          window[key] = {
            doc: doc,
            inspect: function(ref){
              var found = nodes.get(ref);
              if (!found || !found.isConnected) return {ok:false, error:"missing field"};
              var type = ((found.getAttribute("type") || "") + "").toLowerCase();
              var tag = (found.tagName || "").toLowerCase();
              var name = ((found.getAttribute("name") || found.getAttribute("aria-label") || "") + "").replace(/\s+/g, " ").trim().slice(0, 120);
              // A <button> inside a form without an explicit type submits it.
              var submit = type === "submit" || type === "image" || (tag === "button" && !!found.form && type === "");
              var auto = ((found.getAttribute("autocomplete") || "") + "").toLowerCase();
              if (auto === "one-time-code" || auto === "current-password" || auto === "new-password") type = auto;
              return {ok:true, role: roleOf(found), type: type, name: name, submit: submit};
            },
            take: function(nonce){
              nodes = new Map();
              var out = [];
              var list = document.querySelectorAll("a[href],button,input,textarea,select,summary,[role='button'],[role='link'],[role='textbox']");
              var n = 0;
              for (var i = 0; i < list.length && n < $MAX_ELEMENTS; i++){
                var el = list[i];
                var type = ((el.getAttribute("type") || "") + "").toLowerCase();
                if (type === "password" || type === "file" || type === "hidden") continue;
                if (el.disabled || el.getAttribute("aria-disabled") === "true") continue;
                if (!box(el)) continue;
                n++;
                var ref = doc.slice(0, 8) + "-" + nonce.slice(0, 8) + "-" + n;
                nodes.set(ref, el);
                var item = {ref: ref, role: roleOf(el), name: nameOf(el)};
                if (el.value != null && type !== "password" && (el.tagName || "").toLowerCase() !== "a") item.value = ("" + el.value).slice(0, 120);
                out.push(item);
              }
              var text = "";
              try { text = (document.body && (document.body.innerText || "") || "").replace(/\s+/g, " ").trim().slice(0, $MAX_TEXT_SAMPLE); } catch (e) { text = ""; }
              return {ok:true, doc:doc, nonce:nonce, url: String(location.href || ""), title: document.title || "", text: text, elements: out, truncated: list.length > out.length,
                layoutWidth: (typeof innerWidth === "number" ? innerWidth : 0),
                layoutHeight: (typeof innerHeight === "number" ? innerHeight : 0),
                visualWidth: (window.visualViewport && typeof visualViewport.width === "number" ? visualViewport.width : 0),
                visualHeight: (window.visualViewport && typeof visualViewport.height === "number" ? visualViewport.height : 0)};
            },
            act: function(action, ref, text, direction){
              function refused(el){
                var type = ((el.getAttribute("type") || "") + "").toLowerCase();
                var tag = (el.tagName || "").toLowerCase();
                var auto = ((el.getAttribute("autocomplete") || "") + "").toLowerCase();
                var implicit = tag === "button" && el.form && type === "";
                return type === "password" || type === "file" || type === "hidden" || type === "submit" || type === "image" ||
                  implicit || auto === "one-time-code" || auto === "current-password" || auto === "new-password";
              }
              if (action !== "scroll"){
                if (!ref) return {ok:false, error:"missing ref"};
                var el = nodes.get(ref);
                if (!el || !el.isConnected) return {ok:false, error:"ref expired"};
                if (!box(el) || el.disabled) return {ok:false, error:"not actionable"};
                if (refused(el)) return {ok:false, error:"refused field"};
              }
              if (action === "click"){
                nodes.get(ref).click();
                return {ok:true, action:"click", synthetic:true};
              }
              if (action === "type"){
                var field = nodes.get(ref);
                var tag = (field.tagName || "").toLowerCase();
                if (tag !== "input" && tag !== "textarea") return {ok:false, error:"not editable"};
                if (refused(field)) return {ok:false, error:"refused field"};
                var proto = tag === "textarea" ? window.HTMLTextAreaElement : window.HTMLInputElement;
                var desc = proto && Object.getOwnPropertyDescriptor(proto.prototype, "value");
                if (!desc || !desc.set) return {ok:false, error:"not editable"};
                field.focus();
                desc.set.call(field, text);
                field.dispatchEvent(new Event("input", {bubbles:true}));
                field.dispatchEvent(new Event("change", {bubbles:true}));
                return {ok:true, action:"type"};
              }
              if (action === "scroll"){
                var target = (ref && nodes.get(ref)) || document.scrollingElement || document.documentElement;
                if (ref && !target) return {ok:false, error:"ref expired"};
                var h = (innerHeight || 600) * 0.8;
                if (direction === "top") target.scrollTo(0, 0);
                else if (direction === "bottom") target.scrollTo(0, target.scrollHeight || 0);
                else if (direction === "up") target.scrollBy(0, -h);
                else target.scrollBy(0, h);
                return {ok:true, action:"scroll"};
              }
              return {ok:false, error:"unknown action"};
            },
            submit: function(ref, text){
              var field = nodes.get(ref);
              if (!field || !field.isConnected) return {ok:false, error:"ref expired"};
              if (!box(field)) return {ok:false, error:"not actionable"};
              var problem = submitProblem(field);
              if (problem) return {ok:false, error:problem};
              field.focus();
              if (document.activeElement !== field) return {ok:false, error:"not focused"};
              var proto = window.HTMLInputElement;
              var desc = proto && Object.getOwnPropertyDescriptor(proto.prototype, "value");
              if (!desc || !desc.set) return {ok:false, error:"not editable"};
              desc.set.call(field, text);
              field.dispatchEvent(new Event("input", {bubbles:true}));
              field.dispatchEvent(new Event("change", {bubbles:true}));
              if (!field.isConnected || document.activeElement !== field) return {ok:false, error:"focus lost"};
              if (submitProblem(field)) return {ok:false, error:"form changed"};
              return {ok:true};
            },
            active: function(ref){
              var field = nodes.get(ref);
              if (!field || !field.isConnected) return {ok:false, error:"ref expired"};
              if (!box(field)) return {ok:false, error:"not actionable"};
              if (field.disabled || field.readOnly) return {ok:false, error:"not actionable"};
              if (document.activeElement !== field) return {ok:false, error:"not focused"};
              var problem = submitProblem(field);
              if (problem) return {ok:false, error:problem};
              return {ok:true};
            }
          };
          return "installed";
        })()
    """.trimIndent()

    fun witnessScript(): String = WITNESS_TEMPLATE

    fun probeScript(): String = PROBE_TEMPLATE

    fun geometryScript(): String = GEOMETRY_TEMPLATE

    /**
     * Restore script. Re-checks the witness and the restore-only CSS projection in the same evaluate, before the
     * single instant scrollTo. A mismatch returns without any scroll (zero scrollTo). A match performs one instant
     * scrollTo and returns the real readback. Numeric fields only. The returned documentId is returned to the Session
     * for internal verification only; it is never sent to the agent.
     */
    fun scrollRestoreScript(documentId: String, x: Double, y: Double, targetHeightPx: Int, nativeWidthPx: Int): String =
        """(function(){var witness=window.__acWitness&&window.__acWitness.documentId;if(witness!==${jsonQuote(documentId)})return {ok:false,reason:"witness"};var v=window.visualViewport;if(!v||!(v.width>0)||!(v.height>0))return {ok:false,reason:"no-visual"};var s=$nativeWidthPx/v.width;if(!isFinite(s)||!(s>0)||Math.abs(s*v.height-$targetHeightPx)>${WindowRestoreTolerance.PROJECTION_PX})return {ok:false,reason:"projection"};scrollTo({left:$x,top:$y,behavior:"instant"});return {ok:true,documentId:witness,scrollX:scrollX,scrollY:scrollY};})()"""

    /**
     * Reads one node already stored by the current document install. The ref is one JSON argument.
     * Password, file and submit controls are reported so the caller can refuse them before acting.
     */
    fun fieldScript(documentNonce: String, ref: String): String =
        FIELD_TEMPLATE.replace(ARG_TOKEN, "{doc:${jsonQuote(documentNonce)},ref:${jsonQuote(ref)}}")

    /**
     * [acceptedDocumentId] is null until the first ready probe. Loading is pending, not unknown.
     * A later probe must keep the same page-generated id. A changed id is rejected for good.
     * [allowSameDocument] is only for a re-probe of the document that is already accepted.
     */
    fun acceptProbe(
        acceptedDocumentId: String?,
        raw: String?,
        policyAllowsUrl: Boolean,
        allowSameDocument: Boolean = false,
    ): ProbeAcceptance {
        val parsed = parseScriptResult(raw)
        if (parsed is ScriptResult.Failed) {
            return if (parsed.message == "loading") ProbeAcceptance.Pending else ProbeAcceptance.Reject(parsed.message)
        }
        val json = (parsed as ScriptResult.Ok).json
        val id = json["documentId"]?.jsonPrimitive?.contentOrNull
        val state = json["readyState"]?.jsonPrimitive?.contentOrNull
        if (id.isNullOrBlank() || !id.isHex()) return ProbeAcceptance.Reject("no witness")
        if (acceptedDocumentId != null && id != acceptedDocumentId) return ProbeAcceptance.Reject("document changed")
        if (acceptedDocumentId != null && id == acceptedDocumentId && !allowSameDocument) {
            return ProbeAcceptance.Reject("witness already used")
        }
        if (state == "loading") return ProbeAcceptance.Pending
        if (state != "interactive" && state != "complete") return ProbeAcceptance.Reject("not ready")
        if (!policyAllowsUrl) return ProbeAcceptance.Reject("url refused")
        return ProbeAcceptance.Accept(id)
    }

    private val WITNESS_TEMPLATE = """
        (function(){
          if (location.protocol !== "https:") return;
          if (window.__acWitness) return;
          var bytes = new Uint8Array(16);
          crypto.getRandomValues(bytes);
          var id = Array.prototype.map.call(bytes, function(b){ return ("0" + b.toString(16)).slice(-2); }).join("");
          var box = {};
          Object.defineProperty(box, "documentId", {value: id, writable: false, configurable: false});
          Object.defineProperty(window, "__acWitness", {value: box, writable: false, configurable: false});
        })()
    """.trimIndent()

    private val PROBE_TEMPLATE = """
        (function(){
          if (location.protocol !== "https:") return {ok:false, error:"no witness"};
          var witness = window.__acWitness;
          if (!witness || !witness.documentId) return {ok:false, error:"no witness"};
          var desc = Object.getOwnPropertyDescriptor(witness, "documentId");
          if (!desc || desc.writable || desc.configurable) return {ok:false, error:"witness refused"};
          var state = document.readyState;
          if (state === "loading") return {ok:false, error:"loading"};
          if (state !== "interactive" && state !== "complete") return {ok:false, error:"not ready"};
          return {ok:true, documentId: witness.documentId, readyState: state};
        })()
    """.trimIndent()

    private val FIELD_TEMPLATE = """
        (function(){
          var arg = __AC_ARG__;
          var slot = window["__acB_" + arg.doc];
          if (!slot || slot.doc !== arg.doc || !slot.inspect) return {ok:false, error:"missing field"};
          return slot.inspect(arg.ref);
        })()
    """.trimIndent()

    private val GEOMETRY_TEMPLATE = """
        (function(){
          var witness = window.__acWitness;
          var visual = window.visualViewport || {};
          function finite(value){
            return typeof value === "number" && isFinite(value) && value >= 0;
          }
          return {
            ok: true,
            documentId: witness && witness.documentId,
            innerWidth: innerWidth,
            innerHeight: innerHeight,
            clientWidth: document.documentElement ? document.documentElement.clientWidth : 0,
            clientHeight: document.documentElement ? document.documentElement.clientHeight : 0,
            scrollX: scrollX,
            scrollY: scrollY,
            devicePixelRatio: devicePixelRatio,
            visualWidth: visual.width,
            visualHeight: visual.height,
            offsetLeft: visual.offsetLeft,
            offsetTop: visual.offsetTop,
            pageLeft: visual.pageLeft,
            pageTop: visual.pageTop,
            visualScale: visual.scale
          };
        })()
    """.trimIndent()

    fun parseGeometry(raw: String?): GeometryProbe? {
        val parsed = parseScriptResult(raw)
        val json = (parsed as? ScriptResult.Ok)?.json ?: return null
        fun positive(name: String): Float? = json[name]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        fun nonNegative(name: String): Float? = json[name]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
        val innerWidth = positive("innerWidth") ?: return null
        val innerHeight = positive("innerHeight") ?: return null
        val visualWidth = positive("visualWidth") ?: return null
        val visualHeight = positive("visualHeight") ?: return null
        val dpr = positive("devicePixelRatio") ?: return null
        val scale = positive("visualScale") ?: return null
        return GeometryProbe(
            documentId = json["documentId"]?.jsonPrimitive?.contentOrNull,
            innerWidth = innerWidth.toInt(),
            innerHeight = innerHeight.toInt(),
            clientWidth = (positive("clientWidth") ?: return null).toInt(),
            clientHeight = (positive("clientHeight") ?: return null).toInt(),
            scrollX = nonNegative("scrollX") ?: return null,
            scrollY = nonNegative("scrollY") ?: return null,
            devicePixelRatio = dpr,
            visualWidth = visualWidth,
            visualHeight = visualHeight,
            offsetLeft = nonNegative("offsetLeft") ?: return null,
            offsetTop = nonNegative("offsetTop") ?: return null,
            pageLeft = nonNegative("pageLeft") ?: return null,
            pageTop = nonNegative("pageTop") ?: return null,
            visualScale = scale,
        )
    }

    private val READ_TEMPLATE = """
        (function(){
          var arg = __AC_ARG__;
          var witness = window.__acWitness;
          if (!witness || witness.documentId !== arg.documentId) return {ok:false, error:"document mismatch"};
          var desc = Object.getOwnPropertyDescriptor(witness, "documentId");
          if (!desc || desc.writable || desc.configurable) return {ok:false, error:"document mismatch"};
          if (document.readyState !== "interactive" && document.readyState !== "complete") return {ok:false, error:"not ready"};
          var slot = window["__acB_" + arg.doc];
          if (!slot || slot.doc !== arg.doc) return {ok:false, error:"document not ready"};
          var taken = slot.take(arg.snap);
          taken.documentId = witness.documentId;
          return taken;
        })()
    """.trimIndent()

    private val ACTION_TEMPLATE = """
        (function(){
          var arg = __AC_ARG__;
          var witness = window.__acWitness;
          if (!witness || witness.documentId !== arg.documentId) return {ok:false, error:"document mismatch"};
          if (document.readyState !== "interactive" && document.readyState !== "complete") return {ok:false, error:"not ready"};
          var slot = window["__acB_" + arg.doc];
          if (!slot || slot.doc !== arg.doc) return {ok:false, error:"document not ready"};
          var acted = slot.act(arg.action, arg.ref, arg.text, arg.direction);
          acted.documentId = witness.documentId;
          return acted;
        })()
    """.trimIndent()

    private val SUBMIT_TYPE_TEMPLATE = """
        (function(){
          var arg = __AC_ARG__;
          var witness = window.__acWitness;
          if (!witness || witness.documentId !== arg.documentId) return {ok:false, error:"document mismatch"};
          if (document.readyState !== "interactive" && document.readyState !== "complete") return {ok:false, error:"not ready"};
          var slot = window["__acB_" + arg.doc];
          if (!slot || slot.doc !== arg.doc || !slot.submit) return {ok:false, error:"document not ready"};
          var acted = slot.submit(arg.ref, arg.text);
          acted.documentId = witness.documentId;
          return acted;
        })()
    """.trimIndent()

    private val ENTER_READY_TEMPLATE = """
        (function(){
          var arg = __AC_ARG__;
          var witness = window.__acWitness;
          if (!witness || witness.documentId !== arg.documentId) return {ok:false, error:"document mismatch"};
          if (document.readyState !== "interactive" && document.readyState !== "complete") return {ok:false, error:"not ready"};
          var slot = window["__acB_" + arg.doc];
          if (!slot || slot.doc !== arg.doc || !slot.active) return {ok:false, error:"document not ready"};
          var ready = slot.active(arg.ref);
          ready.documentId = witness.documentId;
          return ready;
        })()
    """.trimIndent()
}

internal sealed class ScriptResult {
    data class Ok(val json: JsonObject) : ScriptResult()
    data class Failed(val message: String) : ScriptResult()
}

data class ReadyProbeIdentity(val viewId: String, val ticket: Long, val sessionEpoch: Long)

sealed class ProbeAcceptance {
    data class Accept(val documentId: String) : ProbeAcceptance()
    data object Pending : ProbeAcceptance()
    data class Reject(val message: String) : ProbeAcceptance()
}

/**
 * Agent mode accepts only a controlled top-level GET. Manual mode lets a person navigate, submit
 * a form, or follow a redirect. Both still go through the public-https and TLS checks elsewhere.
 * A redirect or a non-GET in agent mode is unsupported and must not be saved by a later finish.
 */
internal object MainFrameGate {
    fun isPlainGet(method: String?, isRedirect: Boolean): Boolean =
        !isRedirect && method.equals("GET", ignoreCase = true)

    fun isUnsupported(method: String?, isRedirect: Boolean, manual: Boolean = false): Boolean =
        if (manual) false else !isPlainGet(method, isRedirect)

    /** True when this main-frame request may proceed under the current interaction mode. */
    fun allows(method: String?, isRedirect: Boolean, manual: Boolean): Boolean =
        !isUnsupported(method, isRedirect, manual)
}

/**
 * Manual navigation must not be rewritten into a controlled GET. Agent mode still intercepts a
 * plain GET so the presenter can load it once through the controlled session.
 */
internal data class MainFrameDecision(
    val overrideLoading: Boolean,
    val startControlledGet: Boolean,
) {
    companion object {
        fun forRequest(method: String?, redirect: Boolean, manual: Boolean, allowed: Boolean): MainFrameDecision {
            if (!allowed || !MainFrameGate.allows(method, redirect, manual)) {
                return MainFrameDecision(overrideLoading = true, startControlledGet = false)
            }
            return if (manual) {
                MainFrameDecision(overrideLoading = false, startControlledGet = false)
            } else {
                MainFrameDecision(overrideLoading = true, startControlledGet = true)
            }
        }
    }
}

internal sealed class Observe {
    data object Allow : Observe()
    data object Block : Observe()
}

/**
 * Document readiness without a WebView. Enabling or taking over drops refs and keeps a committed
 * page committed. Only a real navigation returns the page to loading.
 */
/**
 * One controlled top-level GET. A later ticket cannot be made ready by a callback that still names
 * an older view. about:blank has no witness. A POST or an unknown navigation cannot be saved by a
 * later finished event. Enabling drops refs and leaves a ready document ready.
 */
internal class ControlledNavigation(
    val viewId: String,
    val ticket: Long,
    var acceptedDocumentId: String? = null,
    var ready: Boolean = false,
    var unknown: Boolean = false,
    var starts: Int = 0,
    var generation: Long = 0,
) {
    fun noteFirstStart(): Boolean {
        starts += 1
        return starts == 1
    }

    fun secondStartIsUnknown(): Boolean = starts > 1

    fun acceptDocument(id: String, readyState: String?, policyAllowsUrl: Boolean, callbackViewId: String, callbackTicket: Long): ProbeAcceptance {
        if (unknown) return ProbeAcceptance.Reject("unknown")
        if (callbackViewId != viewId || callbackTicket != ticket) return ProbeAcceptance.Reject("stale view")
        if (readyState == "loading") return ProbeAcceptance.Pending
        if (!policyAllowsUrl) return ProbeAcceptance.Reject("url refused")
        if (id.isBlank()) return ProbeAcceptance.Reject("no witness")
        if (acceptedDocumentId == null) {
            if (readyState != "interactive" && readyState != "complete") return ProbeAcceptance.Pending
            acceptedDocumentId = id
            ready = true
            return ProbeAcceptance.Accept(id)
        }
        if (id != acceptedDocumentId) {
            markUnknown()
            return ProbeAcceptance.Reject("document changed")
        }
        if (readyState != "interactive" && readyState != "complete") return ProbeAcceptance.Pending
        ready = true
        return ProbeAcceptance.Accept(id)
    }

    fun markUnknown() {
        unknown = true
        ready = false
    }

    fun finishedCannotRecover(): Boolean = unknown && !ready

    fun stillCurrent(currentTicket: Long, currentGeneration: Long): Boolean =
        ticket == currentTicket && generation == currentGeneration && !unknown
}

internal class PageReadiness(
    var committed: Boolean = false,
    var nonce: String = "none",
    var controlGeneration: Long = 0,
) {
    fun invalidateRefs(nextNonce: String) {
        nonce = nextNonce
    }

    fun beginNavigation(nextNonce: String) {
        nonce = nextNonce
        committed = false
    }

    fun commit() {
        committed = true
    }

    /** Returns true when this call observed a new generation and dropped refs. */
    fun syncGeneration(current: Long, nextNonce: String): Boolean {
        if (current == controlGeneration) return false
        controlGeneration = current
        invalidateRefs(nextNonce)
        return true
    }

    fun snapshotReady(): Boolean = committed
}

/** Which page a renderer-gone callback is allowed to clear. */
internal class FatalBinding<T> {
    var current: T? = null
        private set

    fun bind(page: T) {
        current = page
    }

    fun fatalFrom(page: T): Boolean {
        if (current !== page) return false
        current = null
        return true
    }
}

/** What the screen may do with the lease. Visibility is not part of this decision. */
internal object BrowserControlRules {
    fun agentCanEnable(running: Boolean, launchId: Long?, pagePresent: Boolean, reportedVisible: Boolean): Boolean =
        running && launchId != null && pagePresent && reportedVisible

    /** A dead runtime or a new launch drops the lease. It must not hide a page that is still shown. */
    fun revokeOnRuntime(previousLaunchId: Long?, launchId: Long?, running: Boolean): Boolean {
        val identityChanged = previousLaunchId != null && previousLaunchId != launchId
        val readyDropped = previousLaunchId != null && launchId == null
        return identityChanged || readyDropped || !running
    }

    fun snapshotAllowed(committed: Boolean, epoch: Long, expectedEpoch: Long): Boolean =
        committed && epoch == expectedEpoch

    fun takeOverEnabledWhileBusy(): Boolean = true
}

/**
 * Manual login is a person using the page, not an agent grant.
 *
 * Takeover clears the grant before any input is accepted. Leaving manual mode does not restore
 * that grant. A later grant is valid only against a document witness read after the human finished.
 */
internal class ManualTakeover {
    var manual: Boolean = false
        private set
    var grant: String? = null
        private set
    var witness: String? = null
        private set

    fun grantAgent(sessionTab: String, documentWitness: String) {
        manual = false
        grant = sessionTab
        witness = documentWitness
    }

    /** Revokes first. Returns false when there was nothing to revoke. */
    /** Clears the grant before the method returns, so the following touch cannot ride an agent lease. */
    fun beginHumanInput(): Boolean {
        val had = grant != null
        grant = null
        witness = null
        manual = true
        return had
    }

    fun humanNavigating(): Boolean = manual && grant == null

    /** Completing manual login does not regrant. The caller must read a new witness first. */
    fun finishManual() {
        manual = false
        grant = null
        witness = null
    }

    fun regrant(sessionTab: String, documentWitness: String): Boolean {
        if (manual) return false
        if (documentWitness.isBlank() || documentWitness == witness) return false
        grant = sessionTab
        witness = documentWitness
        return true
    }
}
