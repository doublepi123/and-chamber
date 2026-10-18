package io.andchamber.app.ui.web

import android.annotation.SuppressLint
import android.app.DownloadManager
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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import io.andchamber.app.ui.Strings
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

/** "scheme://host:port" with default ports filled in, for same-origin checks. */
internal fun originOf(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return ""
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return ""
    val host = uri.host?.lowercase(Locale.ROOT) ?: return ""
    val port =
        when {
            uri.port != -1 -> uri.port
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> -1
        }
    return "$scheme://$host:$port"
}

/**
 * One WebView showing one server. Lives in [WebViewPool], outside any single screen, so going to
 * Home and back keeps the page as it was. Replaces its WebView if the renderer dies.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ChamberWebView(
    context: Context,
    val targetId: String,
    darkTheme: Boolean,
    private val backgroundColor: Int,
) {
    // WebView derives prefers-color-scheme from the theme's isLightTheme, so match the app theme.
    private val themedContext =
        ContextThemeWrapper(
            context,
            if (darkTheme) {
                android.R.style.Theme_Material_NoActionBar
            } else {
                android.R.style.Theme_Material_Light_NoActionBar
            },
        )
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val bridge = Bridge()

    // Rotated on every page start and on origin change; the helper script embeds the current one.
    @Volatile private var bridgeToken = UUID.randomUUID().toString()

    // True only while the main frame shows a document from [origin]. Gates the bridge and helper.
    @Volatile private var mainTrusted = false
    private val crashTimes = ArrayDeque<Long>()
    private var origin: String = ""
    private var targetUrl: String? = null
    private var lastTrustedUrl: String? = null
    private var foreignRecoveries = 0
    private var clearHistoryAfterLoad = false
    private var destroyed = false

    var host: WebHost? = null

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

    var view: WebView = createView()
        private set

    fun load(
        url: String,
        sessionKey: String,
    ) {
        if (destroyed) return
        this.sessionKey = sessionKey
        val newOrigin = originOf(url)
        if (newOrigin != origin) rotateToken()
        mainTrusted = false
        origin = newOrigin
        targetUrl = url
        lastTrustedUrl = null
        foreignRecoveries = 0
        currentUrl = url
        pageError = null
        committed = false
        progress = 0
        clearHistoryAfterLoad = true
        view.loadUrl(url)
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
        if (destroyed || sessionKey == null || origin.isEmpty() || originOf(url) != origin) return false
        if (currentUrl == url && pageError == null) return true // Already showing it.
        pageError = null
        foreignRecoveries = 0
        view.loadUrl(url)
        return true
    }

    fun reload() {
        if (destroyed) return
        pageError = null
        val shown = view.url
        if (shown.isNullOrEmpty() || shown == BLANK) {
            currentUrl?.let { view.loadUrl(it) }
        } else {
            view.reload()
        }
    }

    private fun rotateToken() {
        bridgeToken = UUID.randomUUID().toString()
    }

    fun canGoBack(): Boolean = !destroyed && view.canGoBack()

    fun goBack() {
        if (!destroyed) view.goBack()
    }

    fun openInBrowser() {
        val url = currentUrl ?: return
        openExternally(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** The WebView, detached from any previous holder so it can be attached again. */
    fun attachableView(): WebView {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun onResume() {
        if (!destroyed) view.onResume()
    }

    fun onPause() {
        if (!destroyed) view.onPause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        mainTrusted = false
        host = null
        val old = view
        (old.parent as? ViewGroup)?.removeView(old)
        old.stopLoading()
        old.destroy()
    }

    @SuppressLint("JavascriptInterface")
    private fun createView(): WebView {
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        val webView = WebView(themedContext)
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
        CookieManager.getInstance().let { cookies ->
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(webView, false)
        }
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

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            return routeOutside(request.url)
        }

        // pageError is cleared only by load()/reload(): some WebView versions start their own error
        // page right after a failure, which would otherwise hide the error again.

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            if (!isCurrent(view)) return
            // Every main-frame document starts with a fresh token and no bridge unless it is ours.
            rotateToken()
            mainTrusted = false
            if (url.isNullOrEmpty() || url == BLANK) {
                view.removeJavascriptInterface(BRIDGE_NAME)
                return
            }
            if (origin.isNotEmpty() && originOf(url) == origin) {
                mainTrusted = true
                view.addJavascriptInterface(bridge, BRIDGE_NAME)
                return
            }
            // A foreign main-frame document (e.g. a POST or redirect that bypassed shouldOverrideUrlLoading).
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.stopLoading()
            val back = targetUrl
            if (back != null && foreignRecoveries < MAX_FOREIGN_RECOVERIES) {
                foreignRecoveries++
                main.post { if (isCurrent(view)) view.loadUrl(back) }
            } else {
                progress = 100
                pageError = Strings.pageFailedTitle
            }
        }

        override fun onPageCommitVisible(
            view: WebView,
            url: String?,
        ) {
            if (isCurrent(view)) committed = true
        }

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) {
            if (!isCurrent(view)) return
            progress = 100
            committed = true
            val trusted = origin.isNotEmpty() && originOf(url) == origin
            mainTrusted = trusted
            if (!trusted) return
            if (!url.isNullOrEmpty() && url != BLANK) currentUrl = url
            if (clearHistoryAfterLoad) {
                // A new session must not let Back walk into pages of the previous one.
                clearHistoryAfterLoad = false
                view.clearHistory()
            }
            view.evaluateJavascript(pageHelperScript(), null)
        }

        override fun doUpdateVisitedHistory(
            view: WebView,
            url: String?,
            isReload: Boolean,
        ) {
            if (isCurrent(view) && !url.isNullOrEmpty() && url != BLANK) currentUrl = url
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (isCurrent(view) && request.isForMainFrame) {
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
            if (isCurrent(view) && originOf(error.url) == origin) pageError = Strings.certificateNotTrusted
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
            if (!quiet) toast(Strings.noAppForLink)
            false
        } catch (_: SecurityException) {
            if (!quiet) toast(Strings.noAppForLink)
            false
        }
    }

    private fun replaceAfterCrash() {
        val now = SystemClock.elapsedRealtime()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > CRASH_WINDOW_MS) crashTimes.removeFirst()

        val old = view
        (old.parent as? ViewGroup)?.removeView(old)
        old.destroy()
        view = createView()
        generation += 1

        val url = currentUrl
        if (url != null && crashTimes.size <= MAX_AUTO_RELOADS) {
            progress = 0
            committed = false
            view.loadUrl(url)
            toast(Strings.pageCrashed)
        } else {
            progress = 100
            pageError = Strings.pageCrashedRepeatedly
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
                view.evaluateJavascript(
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

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        mimeType: String?,
        name: String,
    ) {
        try {
            val request =
                DownloadManager.Request(Uri.parse(url)).apply {
                    mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    } else {
                        // Public Downloads needs a storage permission before Android 10.
                        setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, name)
                    }
                }
            appContext.getSystemService(DownloadManager::class.java).enqueue(request)
            toast(Strings.downloading(name))
        } catch (_: Exception) {
            openExternally(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
        main.post { toast(saved.fold({ Strings.savedToDownloads(it) }, { Strings.downloadFailed })) }
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

    /** JavaScript side channel. Calls need the per-WebView token, which other frames can't read. */
    private inner class Bridge {
        // Called on the JavaScript interface thread; both flags are volatile and re-checked on the
        // UI thread before any state changes.
        private fun trusted(token: String?): Boolean = mainTrusted && !destroyed && token == bridgeToken

        @JavascriptInterface
        fun saveFile(
            token: String?,
            dataUrl: String?,
            fileName: String?,
            mimeType: String?,
        ) {
            if (!trusted(token)) return
            if (dataUrl.isNullOrEmpty() || dataUrl.length > MAX_BRIDGE_CHARS) {
                main.post { toast(Strings.downloadFailed) }
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

/**
 * Keeps at most one live [ChamberWebView] (the last opened server), so coming back from Home is
 * instant and only one page uses memory.
 */
internal class WebViewPool {
    private var current: ChamberWebView? = null

    /** Vertical position (0..1) of the page menu handle; shared by all servers. */
    var handleFraction by mutableFloatStateOf(0.3f)

    fun peek(targetId: String): ChamberWebView? = current?.takeIf { it.targetId == targetId }

    fun obtain(
        context: Context,
        targetId: String,
        darkTheme: Boolean,
        backgroundColor: Int,
    ): ChamberWebView {
        peek(targetId)?.let { return it }
        destroyAll()
        return ChamberWebView(context, targetId, darkTheme, backgroundColor).also { current = it }
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
