package io.andchamber.app.ui.browser

import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserErrorCode
import io.andchamber.app.browser.BrowserLimits
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The only [BrowserDriver] for the independent page. [execute] is called on the main dispatcher by
 * the controller. It does not create a controller, and it does not read credentials.
 *
 * Navigation is checked again here (syntax, then a DNS preflight off the main thread) and refs are
 * dropped as soon as a navigation is asked for. A late script callback whose epoch no longer matches
 * is discarded. This is not a complete outbound firewall: a redirect or a load the WebView starts
 * on its own can still leave the allow-list. Subresource and frame loads that pass through
 * [BrowserSession] are checked for syntax only.
 */
internal class BrowserPageDriver(
    private val session: () -> BrowserSession?,
    private val policy: () -> BrowserUrlPolicy,
    private val generation: () -> Long = { 0L },
    private val navigateControlled: suspend (String) -> Boolean = { false },
    private val onChanged: () -> Unit = {},
) : BrowserDriver {
    override suspend fun execute(request: BrowserRequest): BrowserReply {
        session()?.syncControlGeneration(generation())
        BrowserDriverLogic.validate(request)?.let { return it }
        val page = session() ?: return BrowserDriverLogic.requestError(BrowserErrorCode.NOT_VISIBLE, "the page is not open")
        if (page.fatal) return BrowserDriverLogic.requestError(BrowserErrorCode.NOT_VISIBLE, "the page stopped")
        if (page.view.width <= 1 || page.view.height <= 1 || !page.view.isShown) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.NOT_VISIBLE, "the page is not visible")
        }
        return when (request.method) {
            BrowserMethod.NAVIGATE -> navigate(page, request)
            BrowserMethod.SNAPSHOT -> snapshot(page)
            BrowserMethod.CLICK -> act(page, "click", request)
            BrowserMethod.TYPE -> if (request.params[BrowserDriverLogic.PARAM_SUBMIT] == "true") {
                typeSubmit(page, request)
            } else {
                act(page, "type", request)
            }
            BrowserMethod.SCROLL -> act(page, "scroll", request)
            BrowserMethod.CAPTURE -> capture(page)
            BrowserMethod.RESIZE -> resize(page, request)
        }
    }

    private suspend fun navigate(page: BrowserSession, request: BrowserRequest): BrowserReply {
        val raw = request.params.getValue(BrowserDriverLogic.PARAM_URL)
        val syntax = policy().checkSyntax(raw)
        if (syntax is UrlDecision.Deny) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.POLICY_DENIED, syntax.reason.name)
        }
        val started = navigateControlled(raw)
        if (!started) return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "navigation was not started")
        onChanged()
        return BrowserReply(
            ok = true,
            result = mapOf(
                BrowserDriverLogic.RESULT_PHASE to "started",
                BrowserDriverLogic.RESULT_URL to raw,
            ),
        )
    }

    private suspend fun snapshot(page: BrowserSession): BrowserReply {
        val documentId = page.acceptedDocumentId
        if (!page.committed || documentId == null || page.unknownNavigation) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "the page is still loading")
        }
        val epoch = page.epoch()
        val nonce = page.documentNonce
        val viewId = page.viewId
        val ticket = page.ticket
        if (!ensureInstalled(page, epoch, nonce, documentId, viewId, ticket)) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "the page is not ready")
        }
        if (page.epoch() != epoch) return stale()
        val snap = BrowserDriverLogic.newNonce()
        val raw = page.eval(BrowserDriverLogic.readScript(nonce, snap, documentId), epoch) ?: return stale()
        if (!page.owns(viewId, ticket) || page.acceptedDocumentId != documentId) return stale()
        return when (val parsed = BrowserDriverLogic.parseScriptResult(raw)) {
            is ScriptResult.Failed -> BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, parsed.message)
            is ScriptResult.Ok -> {
                val doc = parsed.json["doc"]?.jsonPrimitive?.contentOrNull
                val witnessed = parsed.json["documentId"]?.jsonPrimitive?.contentOrNull
                if (doc != nonce || witnessed != documentId || page.epoch() != epoch) stale()
                else BrowserReply(ok = true, result = BrowserDriverLogic.snapshotMap(parsed.json))
            }
        }
    }

    private suspend fun act(page: BrowserSession, action: String, request: BrowserRequest): BrowserReply {
        val documentId = page.acceptedDocumentId
        if (!page.committed || documentId == null || page.unknownNavigation) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "the page is still loading")
        }
        val epoch = page.epoch()
        val nonce = page.documentNonce
        val viewId = page.viewId
        val ticket = page.ticket
        if (page.installedNonce != nonce || page.epoch() != epoch) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "refs expired")
        }
        val ref = request.params[BrowserDriverLogic.PARAM_REF].orEmpty()
        if (action != "scroll" && !ref.startsWith(nonce.take(8))) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "ref expired")
        }
        val script = BrowserDriverLogic.actionScript(
            documentNonce = nonce,
            documentId = documentId,
            action = action,
            ref = ref,
            text = request.params[BrowserDriverLogic.PARAM_TEXT].orEmpty(),
            direction = request.params[BrowserDriverLogic.PARAM_DIRECTION] ?: "down",
        )
        val raw = page.eval(script, epoch) ?: return stale()
        if (page.epoch() != epoch || !page.owns(viewId, ticket) || page.acceptedDocumentId != documentId) return stale()
        return when (val parsed = BrowserDriverLogic.parseScriptResult(raw)) {
            is ScriptResult.Failed -> BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, parsed.message)
            is ScriptResult.Ok -> {
                onChanged()
                BrowserReply(
                    ok = true,
                    result = mapOf(
                        "action" to action,
                        "synthetic" to (action == "click").toString(),
                    ),
                )
            }
        }
    }

    private suspend fun typeSubmit(page: BrowserSession, request: BrowserRequest): BrowserReply {
        val documentId = page.acceptedDocumentId
        if (!page.committed || documentId == null || page.unknownNavigation) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "the page is still loading")
        }
        val epoch = page.epoch()
        val nonce = page.documentNonce
        val viewId = page.viewId
        val ticket = page.ticket
        if (page.installedNonce != nonce || page.epoch() != epoch) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "refs expired")
        }
        val ref = request.params[BrowserDriverLogic.PARAM_REF].orEmpty()
        if (!ref.startsWith(nonce.take(8))) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "ref expired")
        }
        val text = request.params[BrowserDriverLogic.PARAM_TEXT].orEmpty()
        // The script refuses a known POST/override/foreign target/credential/textarea before it
        // changes the value, then uses the same native setter as the ordinary TYPE action.
        val raw = page.eval(BrowserDriverLogic.submitTypeScript(nonce, documentId, ref, text), epoch) ?: return stale()
        if (page.epoch() != epoch || !page.owns(viewId, ticket) || page.acceptedDocumentId != documentId) return stale()
        when (val parsed = BrowserDriverLogic.parseScriptResult(raw)) {
            is ScriptResult.Failed -> return submitFailure(parsed.message)
            is ScriptResult.Ok -> {
                if (parsed.json["documentId"]?.jsonPrimitive?.contentOrNull != documentId) return stale()
            }
        }
        onChanged()
        // Enter is native and exactly one DOWN/UP. A false return from the platform is not treated
        // as completion; the real navigation receipt is the presenter's separate confirmation.
        return when (val press = page.pressEnter(ref, epoch, request.timeoutMs)) {
            EnterPress.Dispatched -> BrowserReply(
                ok = true,
                result = mapOf(
                    "action" to "type",
                    BrowserDriverLogic.RESULT_ENTER_DISPATCHED to "true",
                ),
            )
            EnterPress.Stale -> stale()
            EnterPress.NotVisible -> BrowserDriverLogic.requestError(BrowserErrorCode.NOT_VISIBLE, "the page is not visible")
            EnterPress.Failed -> BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, "enter dispatch failed")
            is EnterPress.Refused -> submitFailure(press.reason)
        }
    }

    /** A fixed refusal reason from the submit/active script. Never page text. */
    private fun submitFailure(message: String): BrowserReply = when (message) {
        "ref expired", "document mismatch", "document not ready", "not ready" -> stale()
        "refused field", "not single-line input", "form method", "form method override",
        "foreign target", "form action", "form changed" ->
            BrowserDriverLogic.requestError(BrowserErrorCode.UNSUPPORTED_PARAMETER, message)
        "not focused", "focus lost", "not editable", "not actionable" ->
            BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, message)
        else -> BrowserDriverLogic.requestError(BrowserErrorCode.DRIVER, message)
    }

    /**
     * Applies a viewport mode/size to the live session without loading or reloading anything. The
     * result is the configured pair, not a claim that the DOM already measured it; snapshot probe
     * fields are the measured truth.
     */
    private fun resize(page: BrowserSession, request: BrowserRequest): BrowserReply {
        val viewport = request.params[BrowserDriverLogic.PARAM_VIEWPORT]
            ?: return BrowserDriverLogic.requestError(BrowserErrorCode.INVALID_REQUEST, "missing viewport")
        val enabled = when (viewport) {
            "desktop" -> true
            "mobile" -> false
            "fill" -> page.desktopConfigured
            else -> return BrowserDriverLogic.requestError(BrowserErrorCode.INVALID_REQUEST, "viewport must be desktop, mobile or fill")
        }
        val width = request.params[BrowserDriverLogic.PARAM_WIDTH]
        val height = request.params[BrowserDriverLogic.PARAM_HEIGHT]
        return when (val parsed = BrowserDesktopViewport.parse(width, height)) {
            is BrowserDesktopViewport.Parse.Invalid ->
                BrowserDriverLogic.requestError(BrowserErrorCode.UNSUPPORTED_PARAMETER, "invalid desktop size")
            is BrowserDesktopViewport.Parse.Valid -> {
                if (viewport != "desktop") {
                    return BrowserDriverLogic.requestError(
                        BrowserErrorCode.UNSUPPORTED_PARAMETER,
                        "width and height require desktop",
                    )
                }
                page.configureDesktop(true, parsed.size.width, parsed.size.height)
                BrowserReply(ok = true, result = resizeResult(page, viewport))
            }
            BrowserDesktopViewport.Parse.Unspecified -> {
                // Bool-only configure preserves a custom desktop pair.
                page.configureDesktop(enabled)
                BrowserReply(ok = true, result = resizeResult(page, viewport))
            }
        }
    }

    private fun resizeResult(page: BrowserSession, viewport: String): Map<String, String> = mapOf(
        BrowserDriverLogic.PARAM_VIEWPORT to viewport,
        "desktop" to page.desktopConfigured.toString(),
        BrowserDriverLogic.PARAM_WIDTH to page.desktopWidth.toString(),
        BrowserDriverLogic.PARAM_HEIGHT to page.desktopHeight.toString(),
    )

    private suspend fun capture(page: BrowserSession): BrowserReply {
        val image = withTimeoutOrNull(BrowserLimits.DEFAULT_TIMEOUT_MS) { page.captureViewport() }
        val probe = image?.probe
        val stamp = image?.stamp
        if (image == null || probe == null || stamp == null || stamp.viewId != page.viewId || stamp.documentId != page.acceptedDocumentId) {
            return BrowserDriverLogic.requestError(BrowserErrorCode.NOT_VISIBLE, "geometry not ready")
        }
        return BrowserReply(
            ok = true,
            result = mapOf(
                BrowserDriverLogic.RESULT_MIME to BrowserDriverLogic.MIME_PNG,
                BrowserDriverLogic.RESULT_DATA to image.base64,
                BrowserDriverLogic.RESULT_URL to page.displayUrl.orEmpty(),
                "title" to page.view.title.orEmpty(),
                "width" to image.width.toString(),
                "height" to image.height.toString(),
                "cssWidth" to probe.innerWidth.toString(),
                "cssHeight" to probe.innerHeight.toString(),
                "visualWidth" to probe.visualWidth.toString(),
                "visualHeight" to probe.visualHeight.toString(),
                "offsetLeft" to probe.offsetLeft.toString(),
                "offsetTop" to probe.offsetTop.toString(),
                "dpr" to probe.devicePixelRatio.toString(),
                "scale" to probe.visualScale.toString(),
                "viewport" to if (page.desktopConfigured) "desktop" else "mobile",
            ),
        )
    }

    private suspend fun ensureInstalled(
        page: BrowserSession,
        epoch: Long,
        nonce: String,
        documentId: String,
        viewId: String,
        ticket: Long,
    ): Boolean {
        if (!page.owns(viewId, ticket) || page.acceptedDocumentId != documentId) return false
        if (!page.committed || page.epoch() != epoch) return false
        if (page.installedNonce == nonce && page.epoch() == epoch) return true
        val raw = page.eval(BrowserDriverLogic.installScript(nonce), epoch) ?: return false
        if (page.epoch() != epoch) return false
        val text = BrowserDriverLogic.unwrapJsString(raw)
        if (text != "installed" && text != "same") return false
        page.markInstalled(nonce)
        return page.installedNonce == nonce && page.epoch() == epoch
    }

    private fun stale(): BrowserReply =
        BrowserDriverLogic.requestError(BrowserErrorCode.REVOKED, "the page changed")

    companion object {
        /**
         * Drops the page after an unknown side effect so a late script cannot run on a renewed view.
         * Must run on the main dispatcher. The caller attaches the replacement, which clears
         * [io.andchamber.app.browser.BrowserState.viewInvalidated].
         */
        suspend fun abandon(page: BrowserSession?) {
            if (page == null) return
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                page.invalidateRefs()
                page.destroy()
            }
        }
    }
}
