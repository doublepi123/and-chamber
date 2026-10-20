package io.andchamber.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.view.PixelCopy
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
import io.andchamber.app.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    fun owns(view: String, openTicket: Long): Boolean =
        !destroyed && !fatal && view == viewId && openTicket == ticket && !unknownNavigation

    fun markUnknownNavigation() {
        unknownNavigation = true
        committed = false
        installedNonce = null
    }

    fun noteMainStart(): Boolean {
        if (destroyed) return false
        mainStarts += 1
        if (mainStarts == 1 && ticket > 0) return true
        markUnknownNavigation()
        return false
    }

    fun acceptReady(openTicket: Long, witnessedId: String) {
        if (!owns(viewId, openTicket)) return
        if (acceptedDocumentId == null) acceptedDocumentId = witnessedId
        if (acceptedDocumentId != witnessedId) {
            markUnknownNavigation()
            return
        }
        committed = true
        progress = 100
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
        onProbe?.invoke(viewId, ticket)
    }

    var onProbe: ((String, Long) -> Unit)? = null

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
        epoch += 1
        installedNonce = null
        committed = false
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

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
     * Current WebView viewport only. Copies the WebView rectangle from the activity window, and
     * refuses a rectangle that is not fully inside that window, so the rest of the app is not in
     * the image. Refuses a view with no size, or one that is detached or not shown. A software draw
     * fallback can miss GPU layers.
     */
    suspend fun captureViewport(): CaptureImage? {
        if (destroyed) return null
        if (view.width <= 1 || view.height <= 1) return null
        if (!view.isAttachedToWindow || !view.isShown) return null
        val requestEpoch = epoch
        val bitmap = withContext(Dispatchers.Main.immediate) {
            if (requestEpoch != epoch) return@withContext null
            pixelCopy() ?: softwareDraw()
        } ?: return null
        if (requestEpoch != epoch) {
            bitmap.recycle()
            return null
        }
        return try {
            val bytes = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) return null
            val encoded = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
            if (encoded.length > BrowserDriverLogic.MAX_CAPTURE_CHARS) null else CaptureImage(encoded)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun pixelCopy(): Bitmap? {
        if (!view.isAttachedToWindow || view.width <= 1 || view.height <= 1) return null
        val handler = view.handler ?: return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { continuation ->
            val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }
            try {
                val window = view.context.findActivity()?.window
                val rect = webViewRectInWindow()
                if (window == null || rect == null) {
                    continuation.resume(false)
                } else {
                    PixelCopy.request(window, rect, bitmap, listener, handler)
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
        return if (ok) bitmap else {
            bitmap.recycle()
            null
        }
    }

    /** The page rectangle only. Null when it would include anything outside the WebView. */
    private fun webViewRectInWindow(): Rect? {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val window = view.rootView ?: return null
        if (rect.left < 0 || rect.top < 0 || rect.right > window.width || rect.bottom > window.height) return null
        if (rect.width() != view.width || rect.height() != view.height) return null
        return rect
    }

    /**
     * Software fallback. Hardware-accelerated WebView contents are often blank here. Callers must
     * not treat a successful encode as proof the pixels match the screen.
     */
    private fun softwareDraw(): Bitmap? {
        if (view.width <= 1 || view.height <= 1) return null
        return try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
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
            if (!MainFrameGate.allows(request.method, request.isRedirect, manualNavigation)) {
                onUnknown?.invoke()
                markUnknownNavigation()
                return true
            }
            // Manual mode is a person navigating. Do not rewrite the request or start a controlled GET.
            if (manualNavigation) return false
            onMainGet?.invoke(raw)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame && MainFrameGate.isUnsupported(request.method, request.isRedirect, manualNavigation)) {
                view.post { markUnknownNavigation(); onUnknown?.invoke() }
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
            if (manualNavigation) {
                // A person navigating: every new document is a fresh observation, never "unknown".
                acceptedDocumentId = null
                mainStarts = 0
                unknownNavigation = false
                beginDocument()
                onManualDocument?.invoke()
            }
            if (!noteMainStart()) {
                onUnknown?.invoke()
                return
            }
            noteMainFrame(url)
            applyProgress(5)
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (destroyed || unknownNavigation) return
            noteMainFrame(url)
            requestProbe()
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

        /**
         * Main thread. Returns null when the profile cannot be bound. The caller must not load anything.
         */
        fun tryCreate(context: Context, policyProvider: () -> BrowserUrlPolicy): BrowserSession? {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
            val webView = try {
                WebView(context)
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
                val session = BrowserSession(webView, policyProvider)
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
                domStorageEnabled = false
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
                setSupportZoom(false)
                builtInZoomControls = false
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

internal data class CaptureImage(val base64: String)
