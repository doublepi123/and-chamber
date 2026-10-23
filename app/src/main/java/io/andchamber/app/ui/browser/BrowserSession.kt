package io.andchamber.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.andchamber.app.browser.BrowserUrlPolicy
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.andchamber.app.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * One independent browser page. Created only after [WebViewFeature.MULTI_PROFILE] is known to be
 * present. The fresh WebView is bound to [BrowserDriverLogic.PROFILE_NAME] before settings, clients
 * or any load. A bind failure destroys the view and returns null. There is no fallback profile.
 *
 * This is not the OpenChamber page. It never registers a JavascriptInterface, never enables
 * debugging, and never touches the global CookieManager, proxy or pauseTimers.
 */
internal class BrowserSession private constructor(
    val view: WebView,
    private val policyProvider: () -> BrowserUrlPolicy,
    private val copyBridge: PixelCopyBridge = PixelCopyBridge(),
) {
    var progress by mutableIntStateOf(0)
        private set
    var pageError by mutableStateOf<String?>(null)
        private set
    var displayUrl by mutableStateOf<String?>(null)
        private set
    var documentNonce by mutableStateOf(BrowserDriverLogic.newNonce())
        private set
    var installedNonce by mutableStateOf<String?>(null)
        private set
    var epoch by mutableLongStateOf(0L)
        private set

    /**
     * True only after the main document that owns [documentNonce] has committed. A navigation, a
     * reload, and every [onPageStarted] clear this. Snapshot and actions wait for it, so a helper
     * installed on the previous document cannot be reused for the next one.
     */
    var committed by mutableStateOf(false)
        private set
    var loadSeq by mutableLongStateOf(0L)
        private set
    val viewId: String = BrowserDriverLogic.newNonce()
    var ticket by mutableLongStateOf(0L)
        private set
    var acceptedDocumentId by mutableStateOf<String?>(null)
        internal set
    var mainStarts by mutableIntStateOf(0)
        private set
    var unknownNavigation by mutableStateOf(false)
        private set
    var automationBlocked by mutableStateOf(false)
        private set
    var fatal by mutableStateOf(false)
        private set
    var lastControlGeneration by mutableLongStateOf(0L)
        private set

    private var destroyed = false
    var onFatal: (() -> Unit)? = null
    var onMainGet: ((String) -> Unit)? = null
    var onManualDocument: (() -> Unit)? = null
    var onUnknown: (() -> Unit)? = null
    var onCancelNavigation: (() -> Unit)? = null

    /**
     * Human interaction mode. When true, a person may start a normal top-level navigation, submit
     * a form, or follow a redirect, still under the public-https and TLS policy. Agent mode keeps
     * only controlled GETs. This flag is not an agent grant.
     */
    @Volatile
    var manualNavigation: Boolean = false

    fun epoch(): Long = epoch

    /**
     * Drops document refs only. Enabling, takeover and a generation change use this. They do not
     * start a navigation, so the committed page stays committed.
     */
    fun invalidateRefs() {
        epoch += 1
        documentNonce = BrowserDriverLogic.newNonce()
        installedNonce = null
    }

    /** A real navigation or a new main document. Old refs die and the page is loading again. */
    fun beginDocument(clearUrl: Boolean = false) {
        invalidateRefs()
        committed = false
        pageError = null
        progress = 0
        if (clearUrl) displayUrl = null
    }

    fun beginControlledGet(): Long {
        loadSeq += 1
        ticket = loadSeq
        acceptedDocumentId = null
        mainStarts = 0
        unknownNavigation = false
        beginDocument()
        return ticket
    }

    fun beginLoad(): Long = beginControlledGet()

    fun beginManualDocument(): Long {
        loadSeq += 1
        ticket = loadSeq
        acceptedDocumentId = null
        committed = false
        // The onPageStarted caller records this document's first start through noteMainStart().
        mainStarts = 0
        unknownNavigation = false
        clearControlledGet()
        beginDocument()
        return ticket
    }

    fun readyIdentity(): ReadyProbeIdentity = ReadyProbeIdentity(viewId, ticket, epoch)

    fun matchesReady(identity: ReadyProbeIdentity): Boolean =
        !destroyed && !fatal && !unknownNavigation && !automationBlocked &&
            identity.viewId == viewId && identity.ticket == ticket && identity.sessionEpoch == epoch

    fun owns(view: String, openTicket: Long): Boolean =
        !destroyed && !fatal && view == viewId && openTicket == ticket && !unknownNavigation

    fun markUnknownNavigation(source: String = "unspecified") {
        android.util.Log.i(
            "AndChamberProvider",
            "unknown source=$source ticket=$ticket epoch=$epoch starts=$mainStarts thread=${if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) "Main" else "IO"}",
        )
        clearControlledGet()
        unknownNavigation = true
        committed = false
        installedNonce = null
    }

    fun noteMainStart(): Boolean {
        if (destroyed) return false
        mainStarts += 1
        if (mainStarts == 1 && ticket > 0) return true
        markUnknownNavigation("page-started-extra")
        return false
    }

    fun acceptReady(openTicket: Long, witnessedId: String): Boolean {
        if (!owns(viewId, openTicket) || unknownNavigation || fatal || automationBlocked) return false
        if (acceptedDocumentId == null) acceptedDocumentId = witnessedId
        if (acceptedDocumentId != witnessedId) {
            markUnknownNavigation("ready-witness-mismatch")
            return false
        }
        committed = true
        progress = 100
        return true
    }

    /**
     * The driver is the only writer. A changed generation drops refs and is then current, so the
     * request that observed the change can still snapshot or navigate. It cannot use the old refs.
     */
    fun syncControlGeneration(current: Long): Boolean {
        if (current == lastControlGeneration) return false
        lastControlGeneration = current
        invalidateRefs()
        return true
    }

    fun matchesLoad(seq: Long): Boolean = !destroyed && seq == loadSeq && !fatal

    fun markInstalled(nonce: String) {
        if (nonce == documentNonce) installedNonce = nonce
    }

    fun noteMainFrame(url: String?) {
        if (destroyed || url.isNullOrBlank() || url == ABOUT_BLANK) return
        displayUrl = url
        pageError = null
    }

    /** Call before the first load. The script creates a fresh id inside each https document. */
    fun installWitness(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            automationBlocked = true
            return false
        }
        return try {
            WebViewCompat.addDocumentStartJavaScript(view, BrowserDriverLogic.witnessScript(), setOf("*"))
            true
        } catch (_: Exception) {
            automationBlocked = true
            false
        }
    }

    fun requestProbe() {
        if (destroyed || fatal || unknownNavigation) return
        val identity = readyIdentity()
        if (onReadyProbe != null) onReadyProbe?.invoke(identity) else onProbe?.invoke(viewId, ticket)
    }

    fun requestGeometry(identity: ProbeIdentity) {
        if (destroyed || fatal || unknownNavigation || acceptedDocumentId == null) return
        val serial = identity.probeSerial
        val sessionEpoch = epoch
        geometrySerial = serial
        val before = nativeFrame()
        view.evaluateJavascript(BrowserDriverLogic.geometryScript()) { raw ->
            val after = nativeFrame()
            if (serial != geometrySerial || epoch != sessionEpoch || before != after || acceptedDocumentId == null) return@evaluateJavascript
            val probe = BrowserDriverLogic.parseGeometry(raw) ?: return@evaluateJavascript
            if (probe.documentId != acceptedDocumentId) return@evaluateJavascript
            val sample = GeometrySample(identity.viewId, identity.tabId, identity.ticket, identity.documentId, identity.generation, serial, before, probe)
            latestGeometry = sample
            onGeometry?.invoke(sample)
        }
    }

    fun currentFrame(): NativeFrame = nativeFrame()

    private fun nativeFrame(): NativeFrame {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val visible = android.graphics.Rect()
        view.getGlobalVisibleRect(visible)
        return NativeFrame(view.width, view.height, location[0], location[1], visible.left, visible.top, visible.right, visible.bottom)
    }

    var onProbe: ((String, Long) -> Unit)? = null
    var onReadyProbe: ((ReadyProbeIdentity) -> Unit)? = null
    var onGeometry: ((GeometrySample) -> Unit)? = null
    var onGeometryRequested: (() -> Unit)? = null
    var latestGeometry: GeometrySample? = null
        private set

    fun clearGeometryCache() { latestGeometry = null }

    suspend fun readScroll(): ScrollSample? {
        val epochBefore = epoch
        val witnessBefore = acceptedDocumentId
        if (witnessBefore.isNullOrBlank()) return ScrollSample.Unavailable(ScrollNotSavedReason.NO_WITNESS)
        val raw = eval(BrowserDriverLogic.geometryScript(), epochBefore) ?: return ScrollSample.Unavailable(ScrollNotSavedReason.TIMEOUT)
        if (epoch != epochBefore || acceptedDocumentId != witnessBefore) return ScrollSample.Unavailable(ScrollNotSavedReason.CAPTURE_EPOCH_CHANGED)
        val probe = BrowserDriverLogic.parseGeometry(raw) ?: return ScrollSample.Unavailable(ScrollNotSavedReason.NO_WITNESS)
        if (probe.documentId != witnessBefore) return ScrollSample.Unavailable(ScrollNotSavedReason.NO_WITNESS)
        return ScrollSample.Captured(ScrollAnchor(probe.scrollX.toDouble(), probe.scrollY.toDouble()))
    }

    /**
     * One restore dispatch. [targetHeightPx] and [nativeWidthPx] are the restore target's projection inputs, checked
     * inside the same evaluate before the single scrollTo. A refusal returns null and scrolls nothing.
     */
    suspend fun restoreScroll(x: Double, y: Double, targetHeightPx: Int, nativeWidthPx: Int): ScrollAnchor? {
        val witness = acceptedDocumentId ?: return null
        val epochBefore = epoch
        val raw = eval(BrowserDriverLogic.scrollRestoreScript(witness, x, y, targetHeightPx, nativeWidthPx), epochBefore) ?: return null
        if (epoch != epochBefore || acceptedDocumentId != witness) return null
        val parsed = BrowserDriverLogic.parseScriptResult(raw) as? ScriptResult.Ok ?: return null
        val returned = parsed.json["documentId"]?.jsonPrimitive?.contentOrNull
        if (returned != witness) return null
        val actualX = parsed.json["scrollX"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        val actualY = parsed.json["scrollY"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        return ScrollAnchor(actualX, actualY)
    }
    private var geometrySerial = 0L

    /** Commit gate for the unwrapped harness, where no [BrowserViewportHost] exists. */
    private var unwrappedCommitGate: NativeFrameCommitGate<BrowserPresentationGeometry>? = null
    private var unwrappedCommitted: BrowserPresentationGeometry? = null

    fun fail(message: String) {
        if (destroyed) return
        pageError = message
        progress = 100
        committed = false
    }

    internal fun applyProgress(value: Int) {
        if (!destroyed && pageError == null && !committed) progress = value
    }

    internal fun denyNavigation(message: String) {
        if (!destroyed) {
            pageError = message
            committed = false
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        unwrappedCommitGate?.cancel()
        unwrappedCommitGate = null
        unwrappedCommitted = null
        // Destroy always closes native input admission, even when no press was active.
        inputGateClosed = true
        (view as? FencedWebView)?.gate?.close()
        epoch += 1
        installedNonce = null
        committed = false
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    /**
     * Desktop layout is a real CSS viewport, not only a UA string. The document-start script sets
     * a 1024 CSS px viewport and the WebView scale is reset, so `innerWidth` can match that width.
     * A POST page is not reloaded by this call.
     */
    fun configureDesktop(enabled: Boolean) {
        desktopConfigured = enabled
        view.settings.userAgentString = if (enabled) BrowserDriverLogic.DESKTOP_UA else null
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.setInitialScale(0)
        if (enabled) installDesktopViewport() else removeDesktopViewport()
    }

    fun setDesktopMode(enabled: Boolean) = configureDesktop(enabled)

    private var desktopScript: androidx.webkit.ScriptHandler? = null

    private fun installDesktopViewport() {
        removeDesktopViewport()
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return
        desktopScript = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, BrowserDriverLogic.desktopViewportScript(), setOf("*"))
        }.getOrNull()
    }

    private fun removeDesktopViewport() {
        runCatching { desktopScript?.remove() }
        desktopScript = null
    }


    /**
     * True while this session dispatches its own mouse events. The page's touch listener must let those
     * through to the WebView instead of reading them as a new finger gesture.
     */
    var injectingMouse = false
        private set
    var controlledGet = false
        private set
    var controlledGetUrl: String? = null
        private set
    var controlledGetTicket: Long = 0L
        private set
    var controlledGetDocument: String? = null
        private set
    var desktopConfigured = false
        private set

    /**
     * Delivers one planned mouse event the way a physical mouse reaches a WebView. Buttons are
     * ACTION_BUTTON_PRESS/RELEASE through the generic-motion path with the primary action button set
     * (Chromium ignores a mouse ACTION_DOWN without them); wheel is ACTION_SCROLL with AXIS_VSCROLL.
     *
     * Moves are split by whether a button is held. A plain hover stays an ACTION_HOVER_MOVE on the
     * generic-motion path. A drag (button held) is an ACTION_MOVE with SOURCE_MOUSE/TOOL_TYPE_MOUSE
     * and the held buttonState, sent through dispatchTouchEvent: Chromium's EventForwarder then
     * treats it as a mouse move with a button and routes it to native onMouseEvent, not as a touch.
     * The generic hover path cannot carry a drag under accessibility because Chromium hands every
     * HOVER_* event to accessibility, which consumes it, so the page never sees pointermove. A pure
     * hover is left on that path on purpose: a buttonless ACTION_MOVE would be read as a touchpad
     * scroll or a touch. Verified on the emulator WebView: the page receives
     * pointerdown/mousedown/pointerup/mouseup/click with pointerType "mouse".
     */
    private var pressState: NativeMousePressState? = null
    private var inputGateClosed = false

    fun mousePressActive(): Boolean = pressState?.activePress == true

    var onMouseRetirement: ((String) -> Unit)? = null

    /**
     * Idempotent: the first unavoidable interruption closes input and calls back once. Repeats (the
     * presenter's own retire, the callback's re-entry) return the recorded outcome without a callback.
     */
    fun retireMouse(reason: String, unavoidable: Boolean): MousePressOutcome {
        val state = pressState ?: return MousePressOutcome.NoActivePress
        if (state.retired) return state.outcome
        val outcome = state.interrupt(reason, unavoidable)
        if (outcome is MousePressOutcome.RetireRequired) closeInput(outcome)
        return outcome
    }

    fun admitNativeInput(): Boolean = !destroyed && !inputGateClosed && pressState?.retired != true

    fun injectMouse(plan: MouseEventPlan): Boolean {
        if (!admitNativeInput()) return false
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        fun coords(scroll: Float = 0f) = arrayOf(MotionEvent.PointerCoords().apply {
            x = plan.viewX
            y = plan.viewY
            pressure = if (mouseButtons != 0) 1f else 0f
            size = 1f
            if (scroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, -scroll)
        })
        fun obtain(action: Int, buttons: Int, scroll: Float = 0f): MotionEvent =
            MotionEvent.obtain(now, now, action, 1, props, coords(scroll), 0, buttons, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        val primary = MotionEvent.BUTTON_PRIMARY
        val route = mouseRoute(plan.action, mouseButtons)
        val events = when (plan.action) {
            MouseAction.HOVER -> listOf(obtain(MotionEvent.ACTION_HOVER_MOVE, mouseButtons))
            MouseAction.DRAG -> listOf(
                if (route == MouseRoute.TOUCH_MOVE_WITH_BUTTON) obtain(MotionEvent.ACTION_MOVE, mouseButtons)
                else obtain(MotionEvent.ACTION_HOVER_MOVE, mouseButtons),
            )
            MouseAction.PRESS -> {
                val identity = pressIdentity() ?: return false
                val current = pressState
                if (current?.retired == true) return false
                if (current == null || (!current.activePress && current.identity != identity)) {
                    pressState = NativeMousePressState(identity)
                }
                listOf(obtain(MotionEvent.ACTION_BUTTON_PRESS, primary))
            }
            MouseAction.RELEASE -> listOf(obtain(MotionEvent.ACTION_BUTTON_RELEASE, 0))
            MouseAction.SCROLL -> listOf(obtain(MotionEvent.ACTION_SCROLL, mouseButtons, plan.scrollDelta))
            MouseAction.CANCEL -> {
                val outcome = retireMouse("cancel", unavoidable = pressState?.activePress == true)
                if (outcome is MousePressOutcome.RetireRequired) return false
                emptyList()
            }
        }
        injectingMouse = true
        try {
            for (event in events) {
                if (plan.action == MouseAction.PRESS || plan.action == MouseAction.RELEASE) {
                    val applied = mouseSupport.applyActionButton(event, primary)
                    val constructed = event.action == if (plan.action == MouseAction.PRESS) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE
                    if (applied !is MouseSupport.Supported || event.actionButton != primary || !constructed) {
                        event.recycle()
                        if (pressState?.activePress == true) retireMouse("apply-failed", unavoidable = true)
                        return false
                    }
                    if (plan.action == MouseAction.PRESS && pressState?.beforePressDispatch(true, true) != true) {
                        event.recycle()
                        return false
                    }
                    mouseButtons = if (plan.action == MouseAction.PRESS) primary else 0
                }
                if (!admitNativeInput()) {
                    event.recycle()
                    return false
                }
                val dispatched = try {
                    if (route == MouseRoute.TOUCH_MOVE_WITH_BUTTON) view.dispatchTouchEvent(event)
                    else view.dispatchGenericMotionEvent(event)
                } catch (_: RuntimeException) {
                    event.recycle()
                    retireMouse("dispatch-failed", unavoidable = true)
                    return false
                }
                dispatched.hashCode()
                if (plan.action == MouseAction.RELEASE) pressState?.releaseDelivered()
                event.recycle()
            }
        } finally {
            injectingMouse = false
        }
        return true
    }

    fun lastMouseRetirement(): String? = pendingRetirement?.reason

    private var pendingRetirement: MouseDelivery.RetireRequired? = null

    private fun closeInput(outcome: MousePressOutcome.RetireRequired) {
        inputGateClosed = true
        (view as? FencedWebView)?.gate?.close()
        pendingRetirement = MouseDelivery.RetireRequired(outcome.originalViewIdentity, outcome.reason)
        onMouseRetirement?.invoke(outcome.reason)
    }

    private fun retireDelivery(reason: String): MouseDelivery.RetireRequired {
        val outcome = retireMouse(reason, unavoidable = true)
        val required = outcome as? MousePressOutcome.RetireRequired
            ?: MousePressOutcome.RetireRequired(pressState?.identity ?: MouseDocumentIdentity(viewId, ticket, acceptedDocumentId.orEmpty()), reason, false)
        return MouseDelivery.RetireRequired(required.originalViewIdentity, required.reason)
    }

    private fun pressIdentity(): MouseDocumentIdentity? {
        val witness = acceptedDocumentId?.takeIf { it.isNotBlank() } ?: return null
        if (ticket <= 0L || viewId.isBlank()) return null
        return MouseDocumentIdentity(viewId, ticket, witness)
    }

    private val mouseSupport = NativeMouseSupport()

    /** Buttons held by the injected mouse, carried on every move so a held move is a drag. */
    private var mouseButtons = 0

    fun clearMouseButtons() {
        if (pressState?.activePress == true) retireMouse("clear-buttons", unavoidable = true)
    }

    fun markControlledGet(url: String? = displayUrl) {
        controlledGet = url != null
        controlledGetUrl = url
        controlledGetTicket = ticket
        controlledGetDocument = null
    }

    fun noteControlledDocument(documentId: String?) {
        if (controlledGet && documentId != null && controlledGetDocument == null) controlledGetDocument = documentId
    }

    fun clearControlledGet() {
        controlledGet = false
        controlledGetUrl = null
        controlledGetTicket = 0L
        controlledGetDocument = null
    }

    fun applyActionButton(event: MotionEvent, button: Int): MouseSupport =
        NativeMouseSupport().applyActionButton(event, button)

    fun load(url: String, requestEpoch: Long) {
        if (destroyed || requestEpoch != epoch) return
        view.loadUrl(url)
    }

    fun stop() {
        if (!destroyed) view.stopLoading()
    }

    suspend fun eval(script: String, requestEpoch: Long): String? {
        if (destroyed || requestEpoch != epoch) return null
        val raw = suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
        if (requestEpoch != epoch) return null
        return raw
    }

    /**
     * Current page only, as drawn by the native viewport wrapper. The wrapper keeps the WebView at a
     * fixed unscaled size and only scales the display, so the copied rectangle is the drawn page in
     * window pixels. It is refused unless the complete page is inside the window and unclipped, so
     * the rest of the app is never in the image. There is no software-draw fallback: hardware layers
     * are not readable that way, and a blank image must surface as a failure.
     *
     * Only a presentation the platform actually committed to the compositor is captured. A new
     * layout, translation or pre-draw revision is not proof that the window buffer changed; copying
     * a new rectangle over an old buffer could include the chat behind the page.
     *
     * A presentation move during the copy discards only this attempt. Capture has a small bounded
     * retry and never touches refs, grants or other agent work.
     */
    suspend fun captureViewport(): CaptureImage? {
        if (!capturable()) return null
        var attempts = 0
        while (true) {
            val outcome = guardedCapture(
                sample = { freshCaptureStamp() },
                current = { captureObservation() },
                copy = { stamp -> copyForStamp(stamp) },
            )
            attempts += 1
            val captured = outcome as? CaptureOutcome.Captured
            if (captured != null) {
                val image = captured.attempt.image
                val encoded = android.util.Base64.encodeToString(image.payload, android.util.Base64.NO_WRAP)
                if (encoded.length > BrowserDriverLogic.MAX_CAPTURE_CHARS) return null
                return CaptureImage(encoded, image.width, image.height, captured.attempt.stamp.probe, captured.attempt.stamp)
            }
            if (!CaptureRetry.shouldRetry(outcome, attempts)) return null
        }
    }

    private fun capturable(): Boolean =
        !destroyed && view.isAttachedToWindow && view.isShown && view.width > 1 && view.height > 1

    /** One commit-confirmed observation, or why it is unavailable. */
    private sealed interface CommittedSample {
        class Ready(val observation: CaptureObservation) : CommittedSample
        data object Unavailable : CommittedSample
        data object NotCommitted : CommittedSample
    }

    private suspend fun freshCaptureStamp(): CaptureStampResult {
        if (!capturable()) return CaptureStampResult.Unavailable
        val before = when (val sample = committedCaptureObservation()) {
            is CommittedSample.Ready -> sample.observation
            CommittedSample.Unavailable -> return CaptureStampResult.Unavailable
            CommittedSample.NotCommitted -> return CaptureStampResult.Moved
        }
        val raw = eval(BrowserDriverLogic.geometryScript(), before.refEpoch) ?: return CaptureStampResult.Unavailable
        val after = when (val sample = committedCaptureObservation()) {
            is CommittedSample.Ready -> sample.observation
            CommittedSample.Unavailable -> return CaptureStampResult.Unavailable
            CommittedSample.NotCommitted -> return CaptureStampResult.Moved
        }
        if (before != after) return CaptureStampResult.Moved
        val probe = BrowserDriverLogic.parseGeometry(raw) ?: return CaptureStampResult.Unavailable
        if (probe.documentId == null || probe.documentId != acceptedDocumentId) return CaptureStampResult.Unavailable
        return CaptureStampResult.Ready(
            CaptureStamp(viewId, acceptedDocumentId, ticket, epoch, before.presentation, probe),
        )
    }

    /**
     * Live presentation only if it equals a frame-committed one. A host tracks its own committed
     * presentation; an unwrapped harness uses this session's native gate. Each wait asks the
     * platform for a fresh commit and schedules a draw; there is no fixed delay.
     */
    private suspend fun committedCaptureObservation(): CommittedSample {
        if (!capturable()) return CommittedSample.Unavailable
        val root = view.rootView ?: return CommittedSample.Unavailable
        val host = view.parent as? BrowserViewportHost
        var attempts = 0
        while (true) {
            val live = presentationGeometry() ?: return CommittedSample.Unavailable
            val committed = if (host != null) {
                host.committedPresentationSnapshot()?.toPresentationGeometry()
            } else {
                unwrappedCommitted
            }
            val frame = BrowserCaptureGeometry.captureCommittedFrame(committed, live, root.width, root.height)
            if (frame != null) {
                return CommittedSample.Ready(CaptureObservation(viewId, acceptedDocumentId, ticket, epoch, frame))
            }
            // A live page that is itself uncopyable (clipped, off-window) will not be repaired by
            // waiting for another commit.
            if (BrowserCaptureGeometry.captureFrame(live, root.width, root.height) == null) {
                return CommittedSample.Unavailable
            }
            attempts += 1
            if (attempts > COMMIT_ATTEMPTS) return CommittedSample.NotCommitted
            val next = if (host != null) {
                awaitHostCommit(host)?.toPresentationGeometry()
            } else {
                awaitUnwrappedCommit()
            } ?: return CommittedSample.NotCommitted
            if (host == null) unwrappedCommitted = next
        }
    }

    private suspend fun awaitHostCommit(host: BrowserViewportHost): BrowserPresentationSnapshot? =
        withTimeoutOrNull(COMMIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val accepted = try {
                    host.requestPresentationCommit { snapshot ->
                        if (continuation.isActive) continuation.resume(snapshot)
                    }
                } catch (_: RuntimeException) {
                    false
                }
                if (!accepted && continuation.isActive) continuation.resume(null)
            }
        }

    private suspend fun awaitUnwrappedCommit(): BrowserPresentationGeometry? =
        withTimeoutOrNull(COMMIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val gate = unwrappedCommitGate ?: NativeFrameCommitGate<BrowserPresentationGeometry>(view) {
                    presentationGeometry()
                }.also { unwrappedCommitGate = it }
                val accepted = gate.request { geometry ->
                    if (continuation.isActive) continuation.resume(geometry)
                }
                if (!accepted && continuation.isActive) continuation.resume(null)
            }
        }

    private fun captureObservation(): CaptureObservation? {
        val frame = captureFrame() ?: return null
        return CaptureObservation(viewId, acceptedDocumentId, ticket, epoch, frame)
    }

    /** The presentation sampled from the WebView's native wrapper, resolved to a copyable rect. */
    private fun captureFrame(): CaptureFrame? {
        if (!capturable()) return null
        val root = view.rootView ?: return null
        val presentation = presentationGeometry() ?: return null
        return BrowserCaptureGeometry.captureFrame(presentation, root.width, root.height)
    }

    private fun presentationGeometry(): BrowserPresentationGeometry? {
        val host = view.parent as? BrowserViewportHost
        if (host != null) {
            // The snapshot describes the wrapper's first child; it must be this WebView.
            if (host is ViewGroup && host.childCount > 0 && host.getChildAt(0) !== view) return null
            val snapshot = host.presentationSnapshot() ?: return null
            // A stale snapshot for another child or an unmeasured view is not this page.
            if (snapshot.viewportWidthPx != view.width || snapshot.viewportHeightPx != view.height) return null
            return snapshot.toPresentationGeometry()
        }
        return unwrappedPresentationGeometry()
    }

    /**
     * Unwrapped smoke only: no native wrapper means no scale is allowed. A transformed WebView is
     * refused rather than copied at the wrong rectangle.
     */
    private fun unwrappedPresentationGeometry(): BrowserPresentationGeometry? {
        if (view.scaleX != 1f || view.scaleY != 1f || view.rotation != 0f) return null
        if (view.translationX != 0f || view.translationY != 0f) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return null
        // A partly clipped view reports a smaller visible rect than the view itself.
        if (visible.width() < view.width || visible.height() < view.height) return null
        val page = CaptureRect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        return BrowserPresentationGeometry(
            revision = 0L,
            viewportWidthPx = view.width,
            viewportHeightPx = view.height,
            scale = 1f,
            pageLeft = location[0].toFloat(),
            pageTop = location[1].toFloat(),
            pageWidth = view.width.toFloat(),
            pageHeight = view.height.toFloat(),
            visible = page,
        )
    }

    private suspend fun copyForStamp(stamp: CaptureStamp): CaptureBytes? {
        if (captureObservation() != observationOf(stamp)) return null
        val bitmap = pixelCopy(stamp) ?: return null
        return try {
            if (captureObservation() != observationOf(stamp)) null else encode(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): CaptureBytes? {
        val bytes = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) return null
        return CaptureBytes(bytes.toByteArray(), bitmap.width, bitmap.height)
    }

    private suspend fun pixelCopy(stamp: CaptureStamp): Bitmap? {
        if (!capturable()) return null
        val handler = view.handler ?: return null
        val window = view.context.findActivity()?.window ?: return null
        val root = view.rootView ?: return null
        if (root !== window.decorView.rootView) return null
        val frame = stamp.presentation
        // The sampled copy rectangle is in these window's coordinates; a resized window invalidates it.
        if (root.width != frame.windowWidth || root.height != frame.windowHeight) return null
        val rect = Rect(frame.rect.left, frame.rect.top, frame.rect.right, frame.rect.bottom)
        return copyBridge.copy(window, rect, frame.rect.width, frame.rect.height, handler) {
            view.isAttachedToWindow && view.isShown &&
                captureObservation() == observationOf(stamp) &&
                view.rootView === window.decorView.rootView
        }
    }

    private fun logNav(source: String, method: String?, redirect: Boolean, manual: Boolean, captured: Long = ticket) {
        val normalized = when {
            method.equals("GET", true) -> "GET"
            method.equals("POST", true) -> "POST"
            else -> "OTHER"
        }
        android.util.Log.i(
            "AndChamberProvider",
            "nav source=$source method=$normalized redirect=$redirect manual=$manual captured=$captured current=$ticket epoch=$epoch starts=$mainStarts thread=${if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) "Main" else "IO"}",
        )
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val raw = request.url?.toString().orEmpty()
            val decision = policyProvider().checkSyntax(raw)
            if (decision !is io.andchamber.app.browser.UrlDecision.Allow) {
                denyNavigation(
                    BrowserDriverLogic.denyMessage(
                        (decision as? io.andchamber.app.browser.UrlDecision.Deny)?.reason
                            ?: io.andchamber.app.browser.UrlDenyReason.NOT_PUBLIC_HTTPS,
                    ),
                )
                return true
            }
            val manualAtDecision = manualNavigation
            if (!MainFrameGate.allows(request.method, request.isRedirect, manualAtDecision)) {
                logNav("override-unsupported", request.method, request.isRedirect, manualAtDecision)
                onUnknown?.invoke()
                markUnknownNavigation("override-unsupported")
                return true
            }
            // Manual mode is a person navigating. Do not rewrite the request or start a controlled GET.
            if (manualNavigation) return false
            onMainGet?.invoke(raw)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val manualAtDecision = manualNavigation
            if (request.isForMainFrame && MainFrameGate.isUnsupported(request.method, request.isRedirect, manualAtDecision)) {
                val captured = ticket
                logNav("intercept-unsupported", request.method, request.isRedirect, manualAtDecision)
                view.post {
                    logNav("intercept-posted", request.method, request.isRedirect, manualNavigation, captured)
                    markUnknownNavigation("intercept-unsupported")
                    onUnknown?.invoke()
                }
                return emptyBlocked()
            }
            if (request.isForMainFrame) return null
            val raw = request.url?.toString()
            return if (BrowserDriverLogic.observeSubresource(policyProvider(), raw) is Observe.Block) {
                emptyBlocked()
            } else {
                null
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (destroyed) return
            // A new document under a pressed mouse is an unavoidable interruption. Retire before the
            // ticket or witness moves, so the old press cannot be completed on the new document.
            if (pressState?.activePress == true) {
                retireMouse("document-start", unavoidable = true)
                return
            }
            if (manualNavigation) {
                beginManualDocument()
                onManualDocument?.invoke()
            }
            if (!noteMainStart()) {
                onUnknown?.invoke()
                return
            }
            noteMainFrame(url)
            applyProgress(5)
        }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            if (oldScale != newScale) onGeometryRequested?.invoke()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (destroyed || unknownNavigation) return
            noteMainFrame(url)
            requestProbe()
            onGeometryRequested?.invoke()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (destroyed || unknownNavigation) return
            noteMainFrame(url)
            requestProbe()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (destroyed || !request.isForMainFrame) return
            val message = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "The page didn't load"
            fail(message)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (!destroyed) fail("The certificate isn't trusted")
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            fatal = true
            fail("The page stopped")
            val notify = onFatal
            destroy()
            notify?.invoke()
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            (view.tag as? BrowserSession)?.let { session ->
                session.applyProgress(newProgress)
            }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean = false

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
            result.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult,
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
            request.deny()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
    }

    companion object {
        private const val ABOUT_BLANK = "about:blank"

        /** Bounded wait for a real frame commit; never a fixed delay used as evidence. */
        private const val COMMIT_TIMEOUT_MS = 200L

        /** Bounded commit requests per observation before this capture attempt is refused. */
        private const val COMMIT_ATTEMPTS = 2

        /**
         * Main thread. Returns null when the profile cannot be bound. The caller must not load anything.
         */
        internal fun tryCreate(context: Context, policyProvider: () -> BrowserUrlPolicy, bridge: PixelCopyBridge): BrowserSession? =
            create(context, policyProvider, bridge)

        fun tryCreate(context: Context, policyProvider: () -> BrowserUrlPolicy): BrowserSession? =
            create(context, policyProvider, PixelCopyBridge())

        private fun create(context: Context, policyProvider: () -> BrowserUrlPolicy, bridge: PixelCopyBridge): BrowserSession? {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
            val webView = try {
                FencedWebView(context)
            } catch (_: RuntimeException) {
                return null
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                webView.destroy()
                return null
            }
            return try {
                WebViewCompat.setProfile(webView, BrowserDriverLogic.PROFILE_NAME)
                val bound = WebViewCompat.getProfile(webView)
                check(bound.name == BrowserDriverLogic.PROFILE_NAME) { "unexpected profile" }
                bound.cookieManager.setAcceptCookie(true)
                bound.cookieManager.setAcceptThirdPartyCookies(webView, false)
                harden(webView)
                val session = BrowserSession(webView, policyProvider, bridge)
                webView.tag = session
                webView.webViewClient = session.Client()
                webView.webChromeClient = Chrome()
                webView.setDownloadListener { _, _, _, _, _ -> }
                session
            } catch (_: Exception) {
                webView.destroy()
                null
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun harden(webView: WebView) {
            webView.settings.apply {
                javaScriptEnabled = true
                // Site storage (localStorage/sessionStorage) persists per instance like Cookies, as
                // the approved plan requires. It lives in this WebView's own named profile, so the
                // console and other instances stay isolated. Legacy WebSQL stays off.
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                safeBrowsingEnabled = true
            }
            webView.setDownloadListener { _, _, _, _, _ -> }
            // Do not call CookieManager.getInstance(), setWebContentsDebuggingEnabled, pauseTimers,
            // or addJavascriptInterface. Those are process-global or a host bridge.
        }

        private fun emptyBlocked(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
    }
}

internal class NativeInputGate {
    var closed: Boolean = false
        private set
    fun close() { closed = true }
    fun admit(): Boolean = !closed
}

internal class FencedWebView(context: Context) : WebView(context) {
    val gate = NativeInputGate()
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (gate.admit()) super.dispatchTouchEvent(event) else true
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = if (gate.admit()) super.dispatchGenericMotionEvent(event) else true
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = if (gate.admit()) super.dispatchKeyEvent(event) else true
}

/** How [BrowserSession.injectMouse] carries one planned mouse action to the WebView. */
internal enum class MouseRoute {
    /** SOURCE_MOUSE ACTION_HOVER_MOVE through dispatchGenericMotionEvent. */
    GENERIC_HOVER,

    /** SOURCE_MOUSE/TOOL_TYPE_MOUSE ACTION_MOVE with the held button through dispatchTouchEvent. */
    TOUCH_MOVE_WITH_BUTTON,

    GENERIC_BUTTON_PRESS,
    GENERIC_BUTTON_RELEASE,
    GENERIC_SCROLL,
    CANCEL,
}

/**
 * A drag must not stay on the hover path: when accessibility is enabled, Chromium hands every
 * HOVER_* event to accessibility, which consumes it, so a held-button move never reaches the page.
 * A button move on the touch path is routed by Chromium to a native mouse move with that button.
 * A DRAG without a held button falls back to hover, because a buttonless ACTION_MOVE would be read
 * as a touchpad scroll or a touch.
 */
internal fun mouseRoute(action: MouseAction, buttons: Int): MouseRoute = when (action) {
    MouseAction.HOVER -> MouseRoute.GENERIC_HOVER
    MouseAction.DRAG -> if (buttons != 0) MouseRoute.TOUCH_MOVE_WITH_BUTTON else MouseRoute.GENERIC_HOVER
    MouseAction.PRESS -> MouseRoute.GENERIC_BUTTON_PRESS
    MouseAction.RELEASE -> MouseRoute.GENERIC_BUTTON_RELEASE
    MouseAction.SCROLL -> MouseRoute.GENERIC_SCROLL
    MouseAction.CANCEL -> MouseRoute.CANCEL
}

internal sealed interface MouseDelivery {
    data object Delivered : MouseDelivery
    data object Rejected : MouseDelivery
    data class RetireRequired(val identity: MouseDocumentIdentity, val reason: String) : MouseDelivery
}

internal data class CaptureImage(
    val base64: String,
    val width: Int = 0,
    val height: Int = 0,
    val probe: GeometryProbe? = null,
    val stamp: CaptureStamp? = null,
)

data class NativeFrame(
    val width: Int,
    val height: Int,
    val windowLeft: Int,
    val windowTop: Int,
    val visibleLeft: Int,
    val visibleTop: Int,
    val visibleRight: Int,
    val visibleBottom: Int,
)

data class GeometrySample(
    val viewId: String,
    val tabId: String? = null,
    val ticket: Long,
    val documentId: String?,
    val epoch: Long,
    val serial: Long,
    val frame: NativeFrame,
    val probe: GeometryProbe,
)
