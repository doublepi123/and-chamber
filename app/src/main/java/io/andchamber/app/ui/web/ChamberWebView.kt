package io.andchamber.app.ui.web

import io.andchamber.app.R
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.andchamber.app.data.HttpDownloadRequest
import io.andchamber.app.data.HttpDownloadResult
import io.andchamber.app.data.HttpDownloadStarter
import io.andchamber.app.data.WebIsolationException
import io.andchamber.app.data.WebIsolationFailure
import io.andchamber.app.data.WebProfileBinding
import io.andchamber.app.data.canonicalWebOrigin
import io.andchamber.app.ui.browser.BrowserHostSurface
import io.andchamber.app.runtime.WebSessionCookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** What the visible web screen lends the WebView: things that need activity-result launchers. */
internal interface WebHost {
    /** Returns false when no picker could be shown; the caller then cancels the request. */
    fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean

    fun requestMicrophone(onResult: (Boolean) -> Unit)
}

/**
 * Canonical "scheme://host:port" of [url] (shared with the profile and auth identity), or "" when it
 * is not a plain http(s) address. Same-origin checks compare against the binding's origin.
 */
internal fun originOf(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    return canonicalWebOrigin(url) ?: ""
}

/**
 * Chat session carried by the console URL's `session` query parameter (`/?session=<id>`), or null
 * when [url] is not on [trustedOrigin] or has no non-blank value. The page writes it with
 * history.pushState/replaceState on every chat switch; a draft chat has no parameter. Pure, so it
 * is unit tested without a WebView.
 */
internal fun consoleSessionIdFromUrl(
    url: String?,
    trustedOrigin: String,
): String? {
    if (url.isNullOrEmpty() || trustedOrigin.isEmpty()) return null
    if (canonicalWebOrigin(url) != trustedOrigin) return null
    return url.toHttpUrlOrNull()?.queryParameter("session")?.takeIf { it.isNotBlank() }
}

/**
 * One WebView showing one server. Lives in [WebViewPool], outside any single screen, so going to
 * Home and back keeps the page as it was. Replaces its WebView if the renderer dies.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ChamberWebView(
    context: Context,
    /** The profile this WebView is bound to; its origin is fixed for the life of this object. */
    val binding: WebProfileBinding,
    private var darkTheme: Boolean,
    private var backgroundColor: Int,
    private val downloads: HttpDownloadStarter,
) {
    val targetId: String = binding.identity.targetId

    /** The only origin this WebView may load. Never re-derived from a loaded URL. */
    private val origin: String = binding.identity.origin

    // WebView derives prefers-color-scheme from this context's isLightTheme, re-read on every
    // configuration change, so [applyTheme] restyles it in place when the app theme flips.
    private val themedContext = ContextThemeWrapper(context, webThemeRes(darkTheme))
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val bridge = Bridge()

    // Rotated on every page start and on origin change; the helper script embeds the current one.
    @Volatile private var bridgeToken = UUID.randomUUID().toString()

    // True only while the main frame shows a document from [origin]. Gates the bridge and helper.
    @Volatile private var mainTrusted = false
    private val crashTimes = ArrayDeque<Long>()
    private var targetUrl: String? = null
    private var lastTrustedUrl: String? = null
    private var lastReportedLocation: String? = null
    private var foreignRecoveries = 0
    private var clearHistoryAfterLoad = false
    private var destroyed = false
    @Volatile var gatewayLoginMode = false
        private set
    // Set once the gateway flow actually left [origin] (redirect or navigation to the Access/IdP page).
    // Gateway mode ends only on a return to [origin] after that, not on the initial request itself.
    private var gatewayLeftOrigin = false
    private val gatewayHandoff = GatewayHandoff()
    private val mainFrameErrors = MainFrameErrorGuard()
    internal val gatewayHandoffTicket: Long get() = gatewayHandoff.ticket
    var onGatewayCompleted: ((Long) -> Unit)? = null

    /**
     * The page's trusted main-frame location (page loads and history/pushState navigations), so the
     * app can remember where the user was. Never called for foreign pages or in gateway login mode.
     */
    var onPageLocation: ((String) -> Unit)? = null

    var host: WebHost? = null

    /** Local trusted chat only. Target-site pages never receive this. */
    var browserHost: BrowserHostSurface? = null

    /**
     * Chat session the trusted local console currently shows, from the page URL. Null means no
     * session (e.g. a new-chat draft) or that none was reported yet.
     */
    var consoleSessionId by mutableStateOf<String?>(null)
        private set

    /** Called on the UI thread when [consoleSessionId] changes, including the first report. */
    var onConsoleSessionChanged: ((String?) -> Unit)? = null

    @Volatile private var consoleSessionReported = false
    private var lastConsoleSession: String? = null
    @Volatile var browserConsoleGeneration: Long = 0L
    var composerMeasurement by mutableStateOf<ComposerMeasurement?>(null)
        private set
    var browserBindGeneration by androidx.compose.runtime.mutableLongStateOf(0L)
        private set

    fun acceptsBrowserHost(generation: Long): Boolean =
        generation == browserConsoleGeneration && mainTrusted && !gatewayLoginMode && !destroyed && browserHost != null

    /** Already-unbound calls do not move [browserBindGeneration], so a refuse path cannot loop. */
    fun unbindBrowserHost() {
        composerMeasurement = null
        if (browserHost == null && browserConsoleGeneration != browserBindGeneration) return
        browserHost = null
        if (browserConsoleGeneration == browserBindGeneration) browserConsoleGeneration += 1
    }

    /** Trusted local document committed. Only this moves the rebind key. */
    fun noteTrustedDocument() {
        if (!mainTrusted || gatewayLoginMode || destroyed) return
        if (origin.isEmpty()) return
        composerMeasurement = null
        browserConsoleGeneration += 1
        browserBindGeneration = browserConsoleGeneration
    }

    var onConsoleDocumentInvalidated: (() -> Unit)? = null

    /** A native layout change needs a fresh report even when CSS dimensions happened to stay equal. */
    fun requestComposerMeasurement() {
        composerMeasurement = null
        if (targetId != io.andchamber.app.runtime.ServerTarget.LOCAL_ID ||
            !acceptsBrowserHost(browserConsoleGeneration)) return
        view?.evaluateJavascript("window.dispatchEvent(new Event('andchamber-composer-measure'));", null)
    }

    /** Cookie capability of this WebView's profile. Set only after a successful bind; null otherwise. */
    var sessionCookies: WebSessionCookies? = null
        private set

    /** Why the page cannot be shown in its isolated profile. Non-null means [view] is null. */
    var isolationFailure by mutableStateOf<WebIsolationFailure?>(null)
        private set

    /** Identifies the server session currently loaded; null until [load] is called. */
    var sessionKey: String? = null
        private set

    /** Last known address of the page, including in-app (pushState) navigation. */
    var currentUrl: String? = null
        private set

    /** 0..100 page load progress. */
    var progress by mutableIntStateOf(100)
        private set

    /** True once the first page of the current session has painted. */
    var committed by mutableStateOf(false)
        private set

    /** Main-frame load error, shown over the page. */
    var pageError by mutableStateOf<String?>(null)
        private set

    /** Opaque background color of the page as 0xRRGGBB, reported by the page helper script. */
    var pageColor by mutableStateOf<Int?>(null)
        private set

    /** Bumped whenever [view] is replaced after a renderer crash. */
    var generation by mutableIntStateOf(0)
        private set

    /** Null only when the profile could not be bound (see [isolationFailure]); nothing is loaded then. */
    var view: WebView? = null
        private set

    init {
        // Bound before any other WebView operation, inside the constructor, so no caller sees an unbound view.
        view = createBoundView()
    }

    private fun handoffBlank(ticket: Long) = gatewayHandoffBlank(ticket)
    private fun isHandoffBlank(url: String?) = isGatewayHandoffBlank(url)

    internal fun isGatewayHandoffCurrent(ticket: Long): Boolean =
        view?.let { isCurrent(it) && gatewayHandoff.matches(ticket, it, generation) } == true

    /** Main thread: stop the old document before native authentication can read or commit cookies. */
    internal fun beginGatewayHandoff(): Long {
        val webView = view?.takeIf { !destroyed } ?: return -1
        val previous = gatewayHandoff.ticket
        val ticket = gatewayHandoff.begin(webView, generation)
        if (ticket == previous) return ticket
        val expectedGeneration = generation
        val completionOwner = onGatewayCompleted
        mainTrusted = false
        gatewayLoginMode = false
        gatewayLeftOrigin = false
        sessionKey = null
        committed = false
        progress = 0
        pageError = null
        lastReportedLocation = null
        rotateToken()
        try {
            webView.stopLoading()
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.loadUrl(handoffBlank(ticket))
        } catch (_: RuntimeException) {
            cancelGatewayHandoff(ticket)
        }
        main.post {
            if (isCurrent(webView) && generation == expectedGeneration && isGatewayHandoffCurrent(ticket) &&
                onGatewayCompleted === completionOwner) {
                completionOwner?.invoke(ticket)
            }
        }
        return ticket
    }

    /** Suspends, never blocks Main; a failed barrier cannot release the old document's quarantine. */
    internal suspend fun awaitGatewayHandoff(ticket: Long): Boolean = try {
        withContext(Dispatchers.Main.immediate) {
            val ready = gatewayHandoff.await(ticket)
            if (ready && isGatewayHandoffCurrent(ticket)) true else {
                cancelGatewayHandoff(ticket)
                false
            }
        }
    } catch (_: CancellationException) {
        withContext(NonCancellable + Dispatchers.Main.immediate) { cancelGatewayHandoff(ticket) }
        false
    }

    internal fun cancelGatewayHandoff(ticket: Long) {
        if (ticket <= 0 || ticket != gatewayHandoff.ticket) return
        gatewayHandoff.cancel(ticket)
        mainTrusted = false
        sessionKey = null
        progress = 100
        pageError = context().getString(R.string.gateway_handoff_failed)
    }

    fun load(
        url: String,
        sessionKey: String,
        gatewayLogin: Boolean = false,
    ) {
        if (destroyed) return
        val webView = view ?: return
        if (originOf(url) != origin) {
            // Only the binding's own origin may be loaded into this profile.
            progress = 100
            pageError = context().getString(R.string.page_failed_title)
            return
        }
        if (gatewayHandoff.blocksDocuments && !gatewayHandoff.finish(gatewayHandoff.ticket, webView, generation)) return
        this.sessionKey = sessionKey
        gatewayLoginMode = gatewayLogin
        gatewayLeftOrigin = false
        rotateToken()
        mainTrusted = false
        targetUrl = url
        lastTrustedUrl = null
        foreignRecoveries = 0
        currentUrl = url
        pageError = null
        committed = false
        progress = 0
        clearHistoryAfterLoad = true
        mainFrameErrors.expect(webView, generation, url)
        webView.loadUrl(url)
    }

    /** Forgets the loaded session so the next prepare() loads the page again. */
    fun invalidateSession() {
        sessionKey = null
    }

    /**
     * Opens [url] in the session that is already loaded: same WebView, cookies and sign-in, the
     * current page stays up until the new one paints. Only addresses on the session's own server
     * are accepted. Returns false when no session is loaded.
     */
    fun navigateWithinSession(url: String): Boolean {
        if (gatewayHandoff.blocksDocuments) return false
        if (destroyed || sessionKey == null || origin.isEmpty() || originOf(url) != origin) return false
        val webView = view ?: return false
        if (currentUrl == url && pageError == null) return true // Already showing it.
        pageError = null
        foreignRecoveries = 0
        mainFrameErrors.expect(webView, generation, url)
        webView.loadUrl(url)
        return true
    }

    fun reload() {
        if (gatewayHandoff.blocksDocuments) return
        if (destroyed) return
        val webView = view ?: return
        pageError = null
        val shown = webView.url
        if (shown.isNullOrEmpty() || shown == BLANK) {
            currentUrl?.let {
                mainFrameErrors.expect(webView, generation, it)
                webView.loadUrl(it)
            }
        } else {
            mainFrameErrors.expect(webView, generation, shown)
            webView.reload()
        }
    }

    private fun rotateToken() {
        composerMeasurement = null
        bridgeToken = UUID.randomUUID().toString()
    }

    fun canGoBack(): Boolean = !destroyed && !gatewayHandoff.blocksDocuments && view?.canGoBack() == true

    fun goBack() {
        if (!destroyed && !gatewayHandoff.blocksDocuments) view?.goBack()
    }

    fun openInBrowser() {
        val url = currentUrl ?: return
        openExternally(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** The WebView, detached from any previous holder so it can be attached again. Null when unbound. */
    fun attachableView(): WebView? {
        val webView = view ?: return null
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    /**
     * Follows the app theme without reloading the page: restyles the WebView's context and has it
     * re-read prefers-color-scheme. Without this a pooled WebView keeps the theme it was created with.
     */
    fun applyTheme(dark: Boolean, background: Int) {
        if (destroyed) return
        val themeChanged = dark != darkTheme
        darkTheme = dark
        backgroundColor = background
        if (themeChanged) themedContext.theme.applyStyle(webThemeRes(dark), true)
        val webView = view ?: return
        webView.setBackgroundColor(background)
        if (themeChanged) webView.dispatchConfigurationChanged(webView.resources.configuration)
    }

    fun onResume() {
        if (!destroyed) view?.onResume()
    }

    fun onPause() {
        if (!destroyed) view?.onPause()
    }

    fun destroy() {
        if (destroyed) return
        gatewayHandoff.cancel(gatewayHandoff.ticket)
        onGatewayCompleted = null
        onPageLocation = null
        onConsoleSessionChanged = null
        destroyed = true
        mainTrusted = false
        composerMeasurement = null
        host = null
        val old = view ?: return
        (old.parent as? ViewGroup)?.removeView(old)
        old.stopLoading()
        old.destroy()
    }

    /**
     * Creates a WebView and binds it to this profile before anything else touches it (settings, JS,
     * clients, navigation). On a bind failure the view is already destroyed by the binding: returns null
     * and sets [isolationFailure]. Never falls back to another profile.
     */
    @SuppressLint("JavascriptInterface")
    private fun createBoundView(): WebView? {
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        val webView = WebView(themedContext)
        val cookies =
            try {
                binding.bindFreshWebView(webView)
            } catch (e: WebIsolationException) {
                isolationFailure = e.failure
                return null
            }
        sessionCookies = cookies
        isolationFailure = null
        webView.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        webView.setBackgroundColor(backgroundColor)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // Compose already keeps the page clear of system bars, cutouts and the keyboard. Hiding the
        // insets from the WebView stops it from applying them a second time (safe-area/IME).
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, _ -> WindowInsetsCompat.CONSUMED }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            // target=_blank and window.open() navigate this WebView; shouldOverrideUrlLoading then
            // sends anything that isn't the server to the browser.
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(false)
        }
        // Cookie policy is set on this profile's own CookieManager by bindFreshWebView, never globally.
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(url, userAgent, contentDisposition, mimeType)
        }
        // Registered now, but only page starts on the target origin keep it (see onPageStarted).
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        return webView
    }

    private fun isCurrent(view: WebView?) = !destroyed && view != null && view === this.view

    /** Reports a trusted same-origin main-frame location; suppresses repeats and foreign pages. */
    private fun reportPageLocation(url: String?) {
        if (url.isNullOrEmpty() || url == BLANK) return
        if (!mainTrusted || gatewayLoginMode || gatewayHandoff.blocksDocuments) return
        if (originOf(url) != origin) return
        if (url == lastReportedLocation) return
        lastReportedLocation = url
        val expectedView = view
        val expectedGeneration = generation
        main.post {
            if (isCurrent(expectedView) && generation == expectedGeneration && mainTrusted &&
                !gatewayLoginMode && !gatewayHandoff.blocksDocuments) onPageLocation?.invoke(url)
        }
    }

    /**
     * Reports the chat session of a trusted local console location. Repeats are dropped, so a
     * history entry that only changes other parameters does not re-notify the presenter.
     */
    private fun reportConsoleSession(url: String?) {
        if (targetId != io.andchamber.app.runtime.ServerTarget.LOCAL_ID) return
        if (!mainTrusted || gatewayLoginMode || gatewayHandoff.blocksDocuments) return
        if (origin.isEmpty() || originOf(url) != origin) return
        val session = consoleSessionIdFromUrl(url, origin)
        if (consoleSessionReported && session == lastConsoleSession) return
        consoleSessionReported = true
        lastConsoleSession = session
        consoleSessionId = session
        onConsoleSessionChanged?.invoke(session)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            if (gatewayHandoff.blocksDocuments) return request.url.toString() != handoffBlank(gatewayHandoff.ticket)
            return routeOutside(request.url)
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (!LocalUpdateGate.interceptsLocalUpdateCheck(targetId, origin, request.method.orEmpty(), request.url.toString())) {
                return null
            }
            return WebResourceResponse(
                "application/json",
                "utf-8",
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                LocalUpdateGate.UPDATE_CHECK_BODY.byteInputStream(Charsets.UTF_8),
            )
        }

        // pageError is cleared only by load()/reload(): some WebView versions start their own error
        // page right after a failure, which would otherwise hide the error again.

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            if (!isCurrent(view)) return
            composerMeasurement = null
            if (gatewayHandoff.blocksDocuments) {
                mainTrusted = false
                view.removeJavascriptInterface(BRIDGE_NAME)
                if (url == handoffBlank(gatewayHandoff.ticket) && view.url == url) {
                    gatewayHandoff.started(gatewayHandoff.ticket, view, generation)
                }
                return
            }
            if (isHandoffBlank(url)) return
            mainFrameErrors.started(view, generation, url, view.url)
            // Every main-frame document starts with a fresh token and no bridge unless it is ours.
            rotateToken()
            mainTrusted = false
            onConsoleDocumentInvalidated?.invoke()
            unbindBrowserHost()
            if (url.isNullOrEmpty() || url == BLANK) {
                view.removeJavascriptInterface(BRIDGE_NAME)
                return
            }
            if (origin.isNotEmpty() && originOf(url) == origin) {
                if (gatewayLoginMode && gatewayLeftOrigin) {
                    // Access's same-origin intermediate pages must finish their own gateway work.
                    if (Uri.parse(url).path?.startsWith("/cdn-cgi/") != true) {
                        beginGatewayHandoff()
                        return
                    }
                }
                mainTrusted = true
                view.addJavascriptInterface(bridge, BRIDGE_NAME)
                return
            }
            // A foreign main-frame document (e.g. a POST or redirect that bypassed shouldOverrideUrlLoading).
            view.removeJavascriptInterface(BRIDGE_NAME)
            if (gatewayLoginMode && url.startsWith("https://", true)) {
                gatewayLeftOrigin = true
                return
            }
            view.stopLoading()
            val back = targetUrl
            if (back != null && foreignRecoveries < MAX_FOREIGN_RECOVERIES) {
                foreignRecoveries++
                main.post {
                    if (isCurrent(view) && !gatewayHandoff.blocksDocuments) {
                        mainFrameErrors.expect(view, generation, back)
                        view.loadUrl(back)
                    }
                }
            } else {
                progress = 100
                pageError = context().getString(R.string.page_failed_title)
            }
        }

        override fun onPageCommitVisible(
            view: WebView,
            url: String?,
        ) {
            if (gatewayHandoff.blocksDocuments || isHandoffBlank(url)) return
            if (isCurrent(view)) {
                committed = true
                val trusted = origin.isNotEmpty() && originOf(url) == origin && !gatewayLoginMode
                if (trusted) {
                    noteTrustedDocument()
                    // A newly committed document may already carry a session (reload, deep link).
                    reportConsoleSession(url)
                }
            }
        }

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) {
            if (!isCurrent(view)) return
            if (gatewayHandoff.blocksDocuments) {
                if (url == handoffBlank(gatewayHandoff.ticket) && view.url == url) {
                    gatewayHandoff.complete(gatewayHandoff.ticket, view, generation)
                }
                return
            }
            if (isHandoffBlank(url)) return
            progress = 100
            committed = true
            if (origin.isNotEmpty() && originOf(url) == origin && !gatewayLoginMode) noteTrustedDocument()
            val trusted = origin.isNotEmpty() && originOf(url) == origin
            mainTrusted = trusted
            if (!trusted) return
            reportConsoleSession(url)
            if (!url.isNullOrEmpty() && url != BLANK) currentUrl = url
            if (clearHistoryAfterLoad) {
                // A new session must not let Back walk into pages of the previous one.
                clearHistoryAfterLoad = false
                view.clearHistory()
            }
            view.evaluateJavascript(pageHelperScript(), null)
            reportPageLocation(url)
        }

        // Fires for history-API navigations too (OpenChamber switches sessions via pushState).
        override fun doUpdateVisitedHistory(
            view: WebView,
            url: String?,
            isReload: Boolean,
        ) {
            if (!isCurrent(view) || url.isNullOrEmpty() || url == BLANK) return
            if (gatewayHandoff.blocksDocuments || isHandoffBlank(url)) return
            mainFrameErrors.started(view, generation, url, view.url)
            currentUrl = url
            reportPageLocation(url)
            // Chat switches are pushState/replaceState navigations: the session rides in the query.
            // Some WebView versions also report subframe history; only the main frame's own URL is
            // allowed to change (or clear) the console session.
            if (url == view.url) reportConsoleSession(url)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (isCurrent(view) && request.isForMainFrame && mainFrameErrors.accepts(
                    view, generation, request.url.toString(), gatewayHandoff.blocksDocuments,
                )) {
                pageError = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "Error ${error.errorCode}"
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handler.cancel()
            if (isCurrent(view) && !gatewayHandoff.blocksDocuments && originOf(error.url) == origin) pageError = context().getString(R.string.certificate_not_trusted)
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (isCurrent(view)) {
                replaceAfterCrash()
            } else {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(
            view: WebView,
            newProgress: Int,
        ) {
            if (isCurrent(view)) progress = newProgress
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            val shown = host?.showFileChooser(filePathCallback, fileChooserParams) == true
            if (!shown) filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
            val fromServer = originOf(request.origin?.toString()) == origin
            val currentHost = host
            if (!wantsAudio || !fromServer || currentHost == null) {
                request.deny()
                return
            }
            currentHost.requestMicrophone { granted ->
                // Only the microphone is ever granted, even if the page asked for more.
                if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
            }
        }
    }

    /**
     * Top-level navigation policy. Returns true when the WebView must NOT load the URL itself.
     * Only the target origin loads in the WebView; blob:/data:/about:/javascript: top-level loads
     * (other than the initial about:blank) are dropped so they cannot run with the bridge in scope.
     */
    private fun routeOutside(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val text = uri.toString()
        when (scheme) {
            "http", "https" -> {
                if (origin.isNotEmpty() && originOf(text) == origin) return false
                if (gatewayLoginMode && scheme == "https") {
                    gatewayLeftOrigin = true
                    return false
                }
                openExternally(Intent(Intent.ACTION_VIEW, uri))
            }
            "about" -> return text != BLANK
            "blob", "data", "javascript", "null" -> return true
            "file", "content" -> Unit // Never navigate the app WebView to local files.
            "intent" -> openIntentUri(uri)
            else -> openExternally(Intent(Intent.ACTION_VIEW, uri))
        }
        return true
    }

    private fun openIntentUri(uri: Uri) {
        val intent = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        intent.component = null
        intent.selector = null
        if (!openExternally(intent, quiet = true)) {
            intent.getStringExtra("browser_fallback_url")?.let { fallback ->
                openExternally(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
            }
        }
    }

    private fun openExternally(
        intent: Intent,
        quiet: Boolean = false,
    ): Boolean {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            themedContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (!quiet) toast(context().getString(R.string.no_app_for_link))
            false
        } catch (_: SecurityException) {
            if (!quiet) toast(context().getString(R.string.no_app_for_link))
            false
        }
    }

    private fun replaceAfterCrash() {
        val interruptedHandoff = gatewayHandoff.blocksDocuments
        if (interruptedHandoff) cancelGatewayHandoff(gatewayHandoff.ticket)
        val now = SystemClock.elapsedRealtime()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > CRASH_WINDOW_MS) crashTimes.removeFirst()

        val old = view
        view = null
        if (old != null) {
            (old.parent as? ViewGroup)?.removeView(old)
            old.destroy()
        }
        // Same binding, so the replacement stays in this server's profile. If that fails, no view is
        // exposed; the UI shows [isolationFailure] instead.
        val fresh = createBoundView()
        view = fresh
        generation += 1
        if (interruptedHandoff) {
            mainTrusted = false
            progress = 100
            pageError = context().getString(R.string.gateway_handoff_failed)
            return
        }
        if (fresh == null) {
            progress = 100
            return
        }

        val url = currentUrl
        if (url != null && crashTimes.size <= MAX_AUTO_RELOADS) {
            progress = 0
            committed = false
            mainFrameErrors.expect(fresh, generation, url)
            fresh.loadUrl(url)
            toast(context().getString(R.string.page_crashed))
        } else {
            progress = 100
            pageError = context().getString(R.string.page_crashed_repeatedly)
        }
    }

    // --- Downloads ---

    private fun onDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        when {
            // blob:/data: downloads only come from the trusted server page; anything else is dropped.
            scheme == "blob" -> {
                if (!mainTrusted) return
                view?.evaluateJavascript(
                    "window.__andchamber && window.__andchamber.fetchAndSave(" +
                        "${JSONObject.quote(url)}, ${JSONObject.quote(name)});",
                    null,
                )
            }
            scheme == "data" -> {
                if (mainTrusted) Thread({ saveDataUrl(url, name, mimeType) }, "andchamber-save").start()
            }
            scheme == "http" || scheme == "https" -> enqueueDownload(url, userAgent, mimeType, name)
            else -> Unit
        }
    }

    /**
     * HTTP(S) downloads go through the app's own downloader with this profile's cookie capability; the
     * downloader only sends cookies to the binding origin and strips them once a redirect leaves it. A
     * failure is reported, never retried through another channel.
     */
    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        mimeType: String?,
        name: String,
    ) {
        val cookies = sessionCookies ?: return
        toast(context().getString(R.string.downloading, name))
        downloads.start(cookies, HttpDownloadRequest(url, name, mimeType, userAgent)) { result ->
            main.post {
                when (result) {
                    is HttpDownloadResult.Saved -> toast(context().getString(R.string.saved_to_downloads, result.fileName))
                    is HttpDownloadResult.Failed -> toast(context().getString(R.string.download_failed))
                }
            }
        }
    }

    /** Decodes a data: URL and writes it to Downloads. Runs off the main thread. */
    private fun saveDataUrl(
        dataUrl: String,
        rawName: String,
        mimeHint: String?,
    ) {
        val saved =
            runCatching {
                val comma = dataUrl.indexOf(',')
                require(dataUrl.startsWith("data:") && comma > 0)
                val meta = dataUrl.substring(5, comma)
                val payload = dataUrl.substring(comma + 1)
                val bytes =
                    if (meta.endsWith(";base64")) {
                        Base64.decode(payload, Base64.DEFAULT)
                    } else {
                        Uri.decode(payload).toByteArray()
                    }
                val mime =
                    mimeHint?.takeIf { it.isNotBlank() }
                        ?: meta.substringBefore(';').takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                val name = safeFileName(rawName)
                writeToDownloads(name, mime, bytes)
                name
            }
        main.post { toast(saved.fold({ context().getString(R.string.saved_to_downloads, it) }, { context().getString(R.string.download_failed) })) }
    }

    private fun writeToDownloads(
        name: String,
        mime: String,
        bytes: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            try {
                resolver.openOutputStream(item)?.use { it.write(bytes) } ?: error("no output stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } catch (error: Exception) {
                resolver.delete(item, null, null)
                throw error
            }
        } else {
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("no storage")
            dir.mkdirs()
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var file = File(dir, name)
            var counter = 1
            while (file.exists()) {
                file = File(dir, "$base ($counter)$ext")
                counter++
            }
            file.writeBytes(bytes)
        }
    }

    private fun safeFileName(raw: String): String =
        raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifEmpty { "download" }

    private fun toast(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    /** Localised text outside composition (WebView callbacks), from the app context. */
    private fun context(): Context = appContext

    /** JavaScript side channel. Calls need the per-WebView token, which other frames can't read. */
    private inner class Bridge {
        // Called on the JavaScript interface thread; both flags are volatile and re-checked on the
        // UI thread before any state changes.
        private fun trusted(token: String?): Boolean = mainTrusted && !destroyed && token == bridgeToken

        /** Read-only layout data; fixed arity prevents oversized text/JSON payloads. */
        @JavascriptInterface
        fun composerBounds(
            token: String?, top: Double, bottom: Double,
            viewportWidth: Double, viewportHeight: Double, visible: Boolean,
        ) {
            if (targetId != io.andchamber.app.runtime.ServerTarget.LOCAL_ID ||
                !trusted(token) || gatewayLoginMode) return
            val metrics = ComposerMetrics(top, bottom, viewportWidth, viewportHeight, visible)
            if (!metrics.valid()) return
            val console = browserConsoleGeneration
            val nativeGeneration = generation
            main.post {
                // Token belongs to the document, generation to the console binding. Recheck both
                // after the UI hop; do not query WebView APIs on the bridge thread.
                val current = view ?: return@post
                if (!trusted(token) || !acceptsBrowserHost(console) || gatewayHandoff.blocksDocuments ||
                    !matchesComposerDocument(console, browserConsoleGeneration, nativeGeneration, generation) ||
                    originOf(current.url) != origin) return@post
                composerMeasurement = if (visible) ComposerMeasurement(
                    metrics, console, nativeGeneration, current.width, current.height,
                ) else null
            }
        }

        @JavascriptInterface
        fun saveFile(
            token: String?,
            dataUrl: String?,
            fileName: String?,
            mimeType: String?,
        ) {
            if (!trusted(token)) return
            if (dataUrl.isNullOrEmpty() || dataUrl.length > MAX_BRIDGE_CHARS) {
                main.post { toast(context().getString(R.string.download_failed)) }
                return
            }
            saveDataUrl(dataUrl, fileName.orEmpty(), mimeType)
        }

        @JavascriptInterface
        fun themeColor(
            token: String?,
            rgb: Int,
        ) {
            if (!trusted(token)) return
            main.post { if (!destroyed && mainTrusted) pageColor = rgb and 0xFFFFFF }
        }
    }

    /**
     * Installed after each page load:
     *  - OpenChamber saves files by clicking a blob: link and revoking it at once, which a WebView
     *    can't download; this reads the blob in the page and hands it to [Bridge] instead.
     *  - Reports the page background so the system bar areas can match it.
     */
    private fun pageHelperScript(): String =
        """
        (function () {
          if (window.__andchamber) return;
          var bridge = window.$BRIDGE_NAME;
          if (!bridge) return;
          var token = ${JSONObject.quote(bridgeToken)};
          ${if (targetId == io.andchamber.app.runtime.ServerTarget.LOCAL_ID && !gatewayLoginMode) composerBoundsObserverScript else ""}
          function save(blob, name) {
            var reader = new FileReader();
            reader.onloadend = function () {
              bridge.saveFile(token, String(reader.result || ''), name || '', blob.type || '');
            };
            reader.readAsDataURL(blob);
          }
          function fetchAndSave(url, name) {
            fetch(url)
              .then(function (r) { return r.blob(); })
              .then(function (b) { save(b, name); })
              .catch(function () { bridge.saveFile(token, '', name || '', ''); });
          }
          Object.defineProperty(window, '__andchamber', { value: { fetchAndSave: fetchAndSave } });
          var revoke = URL.revokeObjectURL.bind(URL);
          URL.revokeObjectURL = function (u) { setTimeout(function () { revoke(u); }, 60000); };
          var click = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function () {
            var href = this.href || '';
            if (this.hasAttribute('download') && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {
              fetchAndSave(href, this.getAttribute('download') || '');
              return;
            }
            return click.apply(this, arguments);
          };

          var canvas = document.createElement('canvas');
          canvas.width = 1; canvas.height = 1;
          var ctx = canvas.getContext('2d', { willReadFrequently: true });
          function opaque(el) {
            if (!el || !ctx) return null;
            var css = getComputedStyle(el).backgroundColor;
            if (!css) return null;
            ctx.clearRect(0, 0, 1, 1);
            ctx.fillStyle = '#010203';
            ctx.fillStyle = css;
            // An unsupported color syntax leaves the sentinel in place; don't report it.
            if (ctx.fillStyle === '#010203') return null;
            ctx.fillRect(0, 0, 1, 1);
            var d = ctx.getImageData(0, 0, 1, 1).data;
            return d[3] < 250 ? null : (d[0] << 16) | (d[1] << 8) | d[2];
          }
          var last = -1, queued = false;
          function report() {
            queued = false;
            var root = document.getElementById('root');
            var candidates = [document.body, document.documentElement, root, root && root.firstElementChild];
            for (var i = 0; i < candidates.length; i++) {
              var c = opaque(candidates[i]);
              if (c !== null) {
                if (c !== last) { last = c; bridge.themeColor(token, c); }
                return;
              }
            }
          }
          function schedule() {
            if (queued) return;
            queued = true;
            requestAnimationFrame(report);
          }
          var watch = { attributes: true };
          new MutationObserver(schedule).observe(document.documentElement, watch);
          if (document.body) new MutationObserver(schedule).observe(document.body, watch);
          if (document.head) new MutationObserver(schedule).observe(document.head, { childList: true, subtree: true, attributes: true });
          try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', schedule); } catch (e) {}
          schedule();
          setTimeout(schedule, 800);
          setTimeout(schedule, 2500);
        })();
        """.trimIndent()

    private companion object {
        const val BLANK = "about:blank"
        const val BRIDGE_NAME = "AndChamberBridge"
        const val CRASH_WINDOW_MS = 60_000L
        const val MAX_AUTO_RELOADS = 2
        const val MAX_FOREIGN_RECOVERIES = 2
        const val MAX_BRIDGE_CHARS = 96 * 1024 * 1024
    }
}

private fun webThemeRes(dark: Boolean): Int =
    if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar

/**
 * Keeps at most one live [ChamberWebView] (the last opened server), so coming back from Home is
 * instant and only one page uses memory.
 */
internal class WebViewPool(private val downloads: HttpDownloadStarter) {
    private var current: ChamberWebView? = null

    /** Vertical position (0..1) of the page menu handle; shared by all servers. */
    var handleFraction by mutableFloatStateOf(0.3f)

    fun peek(targetId: String): ChamberWebView? = current?.takeIf { it.targetId == targetId }

    /**
     * Reuses the pooled WebView only for the same identity (target + canonical origin). A different
     * identity, including the same target at a new origin, gets a new WebView in its own profile.
     * May return a [ChamberWebView] whose [ChamberWebView.view] is null (isolation failure).
     */
    fun obtain(
        context: Context,
        binding: WebProfileBinding,
        darkTheme: Boolean,
        backgroundColor: Int,
    ): ChamberWebView {
        current?.takeIf { it.binding.identity == binding.identity }?.let {
            it.applyTheme(darkTheme, backgroundColor)
            return it
        }
        destroyAll()
        return ChamberWebView(context, binding, darkTheme, backgroundColor, downloads).also { current = it }
    }

    fun discard(targetId: String) {
        val pooled = peek(targetId) ?: return
        pooled.destroy()
        current = null
    }

    fun destroyAll() {
        current?.destroy()
        current = null
    }
}
