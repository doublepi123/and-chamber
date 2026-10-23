package io.andchamber.app.browser

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.andchamber.app.MainActivity
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import io.andchamber.app.ui.browser.BrowserDesktopViewport
import io.andchamber.app.ui.browser.BrowserSiteDataClearer
import io.andchamber.app.ui.browser.FixedViewportContainer
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import io.andchamber.app.ui.browser.NativeBrowserState
import io.andchamber.app.ui.browser.PresenterSiteDataOps
import io.andchamber.app.ui.browser.SiteDataClearResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Deterministic instrumentation regressions for the agent-first browser baseline:
 *
 *  - an explicit open gets the one-shot default grant once its real page is ready (the production
 *    still runs the presenter's allow verification; the test only observes the granted state);
 *  - a page that is not ready, or a URL the policy refuses, is never granted;
 *  - a person's scroll and a window resize/restore keep the same view, document, scroll and grant,
 *    and snapshot/click keep working through the production FixedViewportContainer;
 *  - a temporary presentation block keeps the same grant and refs while tools refuse, and the grant
 *    returns automatically when the block clears;
 *  - Take over and hiding the window revoke the grant; an agent open wakes a hidden window, app
 *    background does not, and showing/resuming never restores authority by itself;
 *  - [BrowserSiteDataClearer] removes only the browser profile's cookies and JavaScript storage
 *    while another WebView profile keeps its own.
 *
 * No runtime, server, credentials or external network is used: the only page is the in-memory
 * example.com fixture already used by the local-provider tests ([OpenChamberProviderFixtures]).
 * The tests never skip. A missing MULTI_PROFILE/DOCUMENT_START_SCRIPT support, a failed profile
 * bind, an unready fixture page or a failed assertion is a test failure.
 *
 * The harness uses its own presenter instance and a debug-only page decorator; it does not replace
 * the app's Koin presenter or any production behaviour. It wraps the original page's production
 * WebViewClient with the same in-memory fixture the E2E test uses, before that page's first load.
 */
private const val SITE_COOKIE = "ac_site_data_fixture"
private const val SITE_STORAGE_KEY = "ac_site_data_storage"
private const val OTHER_PROFILE = "andchamber_browser_site_data_other"
private const val BROWSER_VALUE = "browser-site-data"
private const val OTHER_VALUE = "other-site-data"
private const val DEFAULT_ALLOW_WAIT_MS = 30_000

private fun JsonObject.dataBoolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun jsString(value: String): String = "\"" + value + "\""

/** Same fixed public example.com resolver as the smoke test; no fixture test does real DNS. */
private fun fixturePolicy(): BrowserUrlPolicy = BrowserUrlPolicy(
    resolver = DnsResolver { host ->
        check(host == "example.com") { "unexpected fixture host" }
        listOf(InetAddress.getByName("93.184.216.34"))
    },
)

/** The exact selector a current snapshot returned for a named element. */
private fun selectorFor(data: JsonObject, name: String): String? {
    val elements = data["elements"]?.jsonArray ?: return null
    for (element in elements) {
        val item = element.jsonObject
        if (item["name"]?.jsonPrimitive?.contentOrNull == name) {
            return item["selector"]?.jsonPrimitive?.contentOrNull
        }
    }
    return null
}

private fun cookieValue(header: String?, name: String): String? =
    header?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

/**
 * Owns one test presenter, one host FrameLayout and one background scope for a single test. It
 * reuses a real RESUMED MainActivity but never an old host, grant or source. It delegates every
 * authority decision to the production presenter; the only test hooks are the debug-only page
 * decorator and held ready callbacks used to prove the pre-ready refusal. Neither hook grants
 * anything.
 */
internal class BrowserAuthorityHarness(
    private val instrumentation: Instrumentation,
    private val instanceKey: String,
    private val policy: BrowserUrlPolicy = fixturePolicy(),
) {
    val activity: MainActivity
    val presenter: NativeBrowserPresenter
    val host: FrameLayout
    val sessionId: String = "session-$instanceKey"
    private val connectionId = "connection-$instanceKey"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auxiliary = mutableListOf<WebView>()
    private val heldReady = mutableListOf<() -> Unit>()
    private var pageContainer: FixedViewportContainer? = null
    private var pendingDesktopSize: BrowserDesktopViewport.Size? = null

    @Volatile
    private var holdReady = false

    init {
        assertTrue(
            "MULTI_PROFILE is required; profile isolation must fail closed rather than skip",
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
        )
        assertTrue(
            "DOCUMENT_START_SCRIPT is required for a controlled browser page",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        )
        // Reuse a real RESUMED MainActivity left by a prior harness; only launch when none exists.
        // A singleTop Compose activity keeps animating, so startActivitySync would wait for global
        // idle instead of Activity readiness.
        activity = resumedMainActivity() ?: launchMainActivity()
        // Never call waitForIdleSync here: a reused Compose/WebView activity keeps animating, and
        // global idle is not the readiness signal. RESUMED + attached decor is.
        assertTrue(
            "MainActivity must be genuinely RESUMED with an attached decor; state=${activity.lifecycle.currentState}",
            awaitMainActivityResumed(activity),
        )
        presenter = NativeBrowserPresenter(policy = { policy })
        host = mainValue {
            FrameLayout(activity).also { frame ->
                val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                    ?: error("activity content view missing")
                content.addView(
                    frame,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
        onMain {
            presenter.attachHost(activity, instanceKey, 1L)
            presenter.setConnection(connectionId, 1L)
            // Same production page object; this only attaches the fixture response, an optional
            // ready-callback hold and the host before the first load. It cannot replace the page,
            // grant it or create a tab.
            presenter.installPageDecorator { page ->
                val view = page.view as? WebView ?: error("browser page view is not a WebView")
                OpenChamberProviderFixtures.attach(view, view.webViewClient)
                val fixture = view.webViewClient
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(target: WebView, request: WebResourceRequest): Boolean =
                        fixture.shouldOverrideUrlLoading(target, request)

                    override fun shouldInterceptRequest(target: WebView, request: WebResourceRequest): WebResourceResponse? =
                        fixture.shouldInterceptRequest(target, request)

                    override fun onPageStarted(target: WebView, url: String?, favicon: Bitmap?) =
                        fixture.onPageStarted(target, url, favicon)

                    override fun onPageCommitVisible(target: WebView, url: String?) {
                        if (holdReady) heldReady += { fixture.onPageCommitVisible(target, url) }
                        else fixture.onPageCommitVisible(target, url)
                    }

                    override fun onPageFinished(target: WebView, url: String?) {
                        if (holdReady) heldReady += { fixture.onPageFinished(target, url) }
                        else fixture.onPageFinished(target, url)
                    }

                    override fun onScaleChanged(target: WebView, oldScale: Float, newScale: Float) =
                        fixture.onScaleChanged(target, oldScale, newScale)

                    override fun onReceivedError(target: WebView, request: WebResourceRequest, error: WebResourceError) =
                        fixture.onReceivedError(target, request, error)

                    override fun onReceivedSslError(target: WebView, handler: SslErrorHandler, error: SslError) =
                        fixture.onReceivedSslError(target, handler, error)

                    override fun onRenderProcessGone(target: WebView, detail: RenderProcessGoneDetail): Boolean =
                        fixture.onRenderProcessGone(target, detail)
                }
                // The real production wrapper: the WebView keeps one fixed viewport and the
                // container only scales it when the window is moved, resized or made full screen.
                val container = FixedViewportContainer(activity)
                val current = presenter.state.value
                val desktopSize = pendingDesktopSize ?: if (current.desktopMode) {
                    BrowserDesktopViewport.Size(current.desktopWidth, current.desktopHeight)
                } else {
                    null
                }
                val viewportWidth = desktopSize?.width ?: host.width.takeIf { it > 1 }
                    ?: contentWidth()
                val viewportHeight = desktopSize?.height ?: host.height.takeIf { it > 1 }
                    ?: contentHeight()
                container.setViewport(viewportWidth, viewportHeight)
                container.onPresentationChanged = { presenter.onGeometrySettled() }
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                host.addView(
                    container,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                pageContainer = container
            }
        }
        // The host is attached; the first page takes host/content dimensions, falling back to the
        // real content view rather than waiting for a global idle that may never arrive.
    }

    val state: NativeBrowserState
        get() = presenter.state.value

    fun openRequest(
        url: String,
        requestId: String,
        allowWaitMs: Int = 0,
        viewport: String = "mobile",
        width: Int? = null,
        height: Int? = null,
    ): ProviderRequest =
        ProviderRequest(
            serverEpoch = "instrumentation-test",
            connectionId = connectionId,
            requestId = requestId,
            action = "browser.open",
            parameters = buildMap {
                put("url", url)
                put("viewport", viewport)
                if (width != null) put("width", width.toString())
                if (height != null) put("height", height.toString())
            },
            directory = "/",
            sessionId = sessionId,
            timeoutMs = 90_000L,
            allowWaitMs = allowWaitMs,
        )

    /**
     * The production tool path: one explicit open with the same wait budget the plugin uses. The
     * presenter performs the one-shot ready grant and this returns its answer.
     */
    fun openDefault(url: String, requestId: String): ProviderResult = runBlocking {
        presenter.execute(
            openRequest(url, requestId, DEFAULT_ALLOW_WAIT_MS),
            presenter.registry,
            ProviderMode.MANUAL,
            null,
            SystemClock.elapsedRealtime() + 90_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    /** Production open with an explicit viewport; desktop may carry a valid width/height pair. */
    fun openViewport(
        url: String,
        requestId: String,
        viewport: String,
        width: Int? = null,
        height: Int? = null,
    ): ProviderResult {
        pendingDesktopSize = when {
            viewport != "desktop" -> null
            width != null && height != null ->
                runCatching { BrowserDesktopViewport.Size(width, height) }.getOrNull()
            else -> BrowserDesktopViewport.default
        }
        try {
            return runBlocking {
                presenter.execute(
                    openRequest(url, requestId, DEFAULT_ALLOW_WAIT_MS, viewport, width, height),
                    presenter.registry,
                    ProviderMode.MANUAL,
                    null,
                    SystemClock.elapsedRealtime() + 90_000L,
                ) { SystemClock.elapsedRealtime() }
            }
        } finally {
            pendingDesktopSize = null
            syncContainerViewport()
        }
    }

    /** Agent resize RPC; the test container follows the real state pair, never a fake innerWidth. */
    fun resizeViewport(viewport: String, width: Int?, height: Int?, requestId: String): ProviderResult {
        val result = runBlocking {
            presenter.execute(
                ProviderRequest(
                    serverEpoch = "instrumentation-test",
                    connectionId = connectionId,
                    requestId = requestId,
                    action = "browser.resize",
                    parameters = buildMap {
                        put("viewport", viewport)
                        if (width != null) put("width", width.toString())
                        if (height != null) put("height", height.toString())
                    },
                    directory = "/",
                    sessionId = sessionId,
                    timeoutMs = 10_000L,
                ),
                presenter.registry,
                state.mode,
                state.selectedTabId,
                SystemClock.elapsedRealtime() + 10_000L,
            ) { SystemClock.elapsedRealtime() }
        }
        syncContainerViewport()
        return result
    }

    fun resizeDesktop(width: Int, height: Int, requestId: String): ProviderResult =
        resizeViewport("desktop", width, height, requestId)

    /** Mirrors the production UI: desktop uses state.desktopWidth/Height, mobile uses the window. */
    fun syncContainerViewport() = onMain {
        val container = pageContainer ?: return@onMain
        val current = presenter.state.value
        if (current.desktopMode) {
            container.setViewport(current.desktopWidth, current.desktopHeight)
        } else {
            val width = host.width.takeIf { it > 1 } ?: contentWidth()
            val height = host.height.takeIf { it > 1 } ?: contentHeight()
            container.setViewport(width, height)
        }
    }

    /**
     * Failure-only evidence: raw WebView size, real CSS inner/visual viewport, DPR and the current
     * meta viewport. It is never used as a pass predicate or production log.
     */
    fun desktopDiagnostics(): String {
        val current = state
        val view = currentWebView() ?: return "state(desktopMode=${current.desktopMode}," +
            "desktopWidth=${current.desktopWidth},desktopHeight=${current.desktopHeight}," +
            "agentOn=${current.agentOn},pageReady=${current.pageReady}); no-view"
        val rawSize = mainValue { view.width to view.height }
        val script = """(function(){
            var v = window.visualViewport || {};
            var m = document.querySelector('meta[name="viewport"]');
            return JSON.stringify({
              innerWidth: innerWidth, innerHeight: innerHeight,
              visualWidth: v.width || 0, visualHeight: v.height || 0, visualScale: v.scale || 0,
              dpr: devicePixelRatio, meta: m ? m.getAttribute('content') : null,
              ready: document.readyState, url: String(location.href)
            });
        })()"""
        val rawCss = runCatching { eval(view, script) }.getOrNull()
        val css = rawCss?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            ?: "eval-null"
        val (containerWidth, containerHeight) = containerSize()
        return "state(desktopMode=${current.desktopMode},desktopWidth=${current.desktopWidth}," +
            "desktopHeight=${current.desktopHeight},agentOn=${current.agentOn}," +
            "pageReady=${current.pageReady},viewHandle=${current.viewHandle},selected=${current.selectedTabId}); " +
            "rawView=${rawSize.first}x${rawSize.second}; " +
            "container=${containerWidth}x$containerHeight scale=${containerScale()}; css=$css"
    }

    /** Real JS layout size, not a reported constant. Strict 2 CSS px target. */
    fun awaitDesktopViewport(width: Int, height: Int, timeoutMs: Long = 20_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (state.desktopMode) {
                val view = currentWebView()
                if (view != null) {
                    val actualWidth = runCatching { eval(view, "String(innerWidth)") }.getOrNull()
                        ?.trim('"')?.toDoubleOrNull()
                    val actualHeight = runCatching { eval(view, "String(innerHeight)") }.getOrNull()
                        ?.trim('"')?.toDoubleOrNull()
                    if (actualWidth != null && actualHeight != null &&
                        abs(actualWidth - width) <= 2.0 && abs(actualHeight - height) <= 2.0
                    ) {
                        return
                    }
                }
            }
            SystemClock.sleep(50L)
        }
        throw AssertionError("timed out waiting for desktop viewport ${width}x$height; ${desktopDiagnostics()}")
    }

    /** Starts an open without the executor so the test can inspect the pre-ready state. */
    fun startOpen(url: String, requestId: String, allowWaitMs: Int): Deferred<ProviderResult> =
        scope.async {
            presenter.openClaimed(
                openRequest(url, requestId, allowWaitMs),
                SystemClock.elapsedRealtime() + 90_000L,
            )
        }

    fun await(what: String, timeoutMs: Long = 20_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("timed out waiting for $what; state=$state")
    }

    /** Real ready page plus a laid-out WebView: the driver refuses an invisible page. */
    fun awaitPageReady() {
        await("page ready") { state.pageReady && state.shown && state.eligible }
        await("browser view laid out") {
            val view = currentWebView() ?: return@await false
            mainValue { view.width > 1 && view.height > 1 && view.isShown }
        }
    }

    /** The real default grant: a presenter grant bound to the selected tab, not a test flag. */
    fun awaitDefaultGrant() {
        await("default grant") {
            state.agentOn && state.mode == ProviderMode.AGENT &&
                presenter.registry.currentGrant() != null
        }
    }

    fun snapshot(requestId: String): ProviderResult = runBlocking {
        presenter.execute(
            ProviderRequest(
                serverEpoch = "instrumentation-test",
                connectionId = connectionId,
                requestId = requestId,
                action = "browser.snapshot",
                parameters = emptyMap(),
                directory = "/",
                sessionId = sessionId,
                timeoutMs = 5_000L,
            ),
            presenter.registry,
            state.mode,
            state.selectedTabId,
            SystemClock.elapsedRealtime() + 5_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    fun click(selector: String, requestId: String): ProviderResult = runBlocking {
        presenter.execute(
            ProviderRequest(
                serverEpoch = "instrumentation-test",
                connectionId = connectionId,
                requestId = requestId,
                action = "browser.click",
                parameters = mapOf("selector" to selector),
                directory = "/",
                sessionId = sessionId,
                timeoutMs = 5_000L,
            ),
            presenter.registry,
            state.mode,
            state.selectedTabId,
            SystemClock.elapsedRealtime() + 5_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    private fun typeRequest(
        selector: String,
        value: String,
        submit: Boolean,
        requestId: String,
    ): ProviderRequest = ProviderRequest(
        serverEpoch = "instrumentation-test",
        connectionId = connectionId,
        requestId = requestId,
        action = "browser.type",
        parameters = buildMap {
            put("selector", selector)
            put("value", value)
            if (submit) put("submit", "true")
        },
        directory = "/",
        sessionId = sessionId,
        timeoutMs = 10_000L,
    )

    /** Production browser.type; submit=true is the target form-submit contract. */
    fun type(
        selector: String,
        value: String,
        submit: Boolean,
        requestId: String,
    ): ProviderResult = runBlocking {
        presenter.execute(
            typeRequest(selector, value, submit, requestId),
            presenter.registry,
            state.mode,
            state.selectedTabId,
            SystemClock.elapsedRealtime() + 10_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    /** Same production request, started without blocking so a race can hide while it is live. */
    fun startType(
        selector: String,
        value: String,
        submit: Boolean,
        requestId: String,
    ): Deferred<ProviderResult> = scope.async {
        presenter.execute(
            typeRequest(selector, value, submit, requestId),
            presenter.registry,
            state.mode,
            state.selectedTabId,
            SystemClock.elapsedRealtime() + 10_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    fun capture(requestId: String): ProviderResult = runBlocking {
        presenter.execute(
            ProviderRequest(
                serverEpoch = "instrumentation-test",
                connectionId = connectionId,
                requestId = requestId,
                action = "browser.capture",
                parameters = emptyMap(),
                directory = "/",
                sessionId = sessionId,
                timeoutMs = 5_000L,
            ),
            presenter.registry,
            state.mode,
            state.selectedTabId,
            SystemClock.elapsedRealtime() + 5_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    /**
     * Retries the real production capture until its frame commit is admitted. The success is the
     * evidence that the resized fixed viewport has been drawn; no fixed delay or preDraw is used.
     */
    fun awaitCapture(label: String): ProviderResult {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var last: ProviderResult? = null
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt += 1
            val attemptResult = capture("capture-$label-$attempt")
            last = attemptResult
            if (attemptResult.ok) return attemptResult
            SystemClock.sleep(100L)
        }
        throw AssertionError("capture did not commit for $label: ${last?.errorCode}")
    }

    fun setPresentationBlocked(blocked: Boolean) = onMain { presenter.setPresentationBlocked(blocked) }

    fun takeover() = onMain { presenter.takeover() }

    /** The exact production user action behind the UI "Resume agent" control. */
    fun resumeAgent() = runBlocking { presenter.resumeAgent() }

    fun hide() = onMain { presenter.hide() }

    fun background() = onMain { presenter.background() }

    fun show(): Boolean = mainValue { presenter.show() }

    fun pauseHost() = onMain { presenter.onHostPaused() }

    fun resumeHost() = onMain { presenter.onHostResumed() }

    /** A person's page interaction signal, without the old all-touch takeover assumption. */
    fun notePageInteraction() = onMain { presenter.notePageInteraction() }

    /** Resizes only the test host, which holds the real fixed-viewport wrapper. */
    fun resizeHost(widthPx: Int, heightPx: Int) = onMain {
        host.layoutParams = host.layoutParams.apply {
            width = widthPx
            height = heightPx
        }
        host.requestLayout()
    }

    fun currentWebView(): WebView? = presenter.currentView() as? WebView

    fun viewSize(): Pair<Int, Int> {
        val view = currentWebView() ?: return 0 to 0
        return mainValue { view.width to view.height }
    }

    /** Real content size before the host's first layout pass; not a guessed constant. */
    private fun contentWidth(): Int =
        activity.window?.decorView
            ?.findViewById<ViewGroup>(android.R.id.content)
            ?.width?.takeIf { it > 1 }
            ?: activity.resources.displayMetrics.widthPixels

    private fun contentHeight(): Int =
        activity.window?.decorView
            ?.findViewById<ViewGroup>(android.R.id.content)
            ?.height?.takeIf { it > 1 }
            ?: activity.resources.displayMetrics.heightPixels

    fun containerSize(): Pair<Int, Int> =
        mainValue { pageContainer?.let { it.width to it.height } ?: (0 to 0) }

    /** The production wrapper's scale: 1 at the fixed viewport's own size, below 1 when shrunk. */
    fun containerScale(): Float =
        mainValue { pageContainer?.transform?.scale ?: 1f }

    /**
     * The page rectangle actually drawn by the production wrapper, in window pixels. This is the
     * capture oracle: it is the scaled display area, not the WebView's unscaled view width.
     */
    fun displayedPageSize(): Pair<Int, Int>? = mainValue {
        val snapshot = pageContainer?.presentationSnapshot() ?: return@mainValue null
        (snapshot.pageWidth + 0.5f).toInt() to (snapshot.pageHeight + 0.5f).toInt()
    }

    /** Holds only the readiness callbacks; the document still loads and starts normally. */
    fun holdReadyCallbacks() = onMain {
        holdReady = true
        heldReady.clear()
    }

    fun releaseReadyCallbacks() = onMain {
        holdReady = false
        val pending = heldReady.toList()
        heldReady.clear()
        pending.forEach { it() }
    }

    fun eval(view: WebView, script: String): String? = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<String?> { continuation ->
                view.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }
    }

    fun profileCookies(view: WebView): CookieManager =
        mainValue { WebViewCompat.getProfile(view).cookieManager }

    /** A second named profile, loaded from memory on the same origin. */
    fun createBoundProfileView(profile: String): WebView {
        val view = mainValue {
            val container = FrameLayout(activity)
            host.addView(container, FrameLayout.LayoutParams(240, 240))
            WebView(activity).also { web ->
                WebViewCompat.setProfile(web, profile)
                check(WebViewCompat.getProfile(web).name == profile) { "profile bind failed" }
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                container.addView(
                    web,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                web.loadDataWithBaseURL(
                    OpenChamberProviderFixtures.ORIGIN,
                    """<!doctype html><html><body><span id="marker">other-profile</span></body></html>""",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }
        auxiliary += view
        return view
    }

    fun dispose() {
        scope.cancel()
        onMain {
            auxiliary.forEach { runCatching { it.destroy() } }
            auxiliary.clear()
            heldReady.clear()
            try {
                presenter.detachHost(instanceKey, 1L)
            } finally {
                // The Activity is intentionally kept RESUMED for reuse; this harness's host and
                // all of its pages must still leave the window completely.
                (host.parent as? ViewGroup)?.removeView(host)
                host.removeAllViews()
            }
        }
    }

    /** Main thread only; the lifecycle registry rejects calls off Main. */
    private fun resumedMainActivity(): MainActivity? {
        var found: MainActivity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>()
                .firstOrNull {
                    !it.isFinishing && !it.isDestroyed &&
                        it.window?.decorView?.isAttachedToWindow == true
                }
        }
        return found
    }

    /**
     * Launch only when no RESUMED MainActivity exists. ActivityMonitor observes the target
     * Activity directly; startActivitySync would wait for global idle while Compose/WebView keeps
     * animating.
     */
    private fun launchMainActivity(): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        return try {
            instrumentation.targetContext.startActivity(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            instrumentation.waitForMonitorWithTimeout(monitor, 30_000L) as? MainActivity
                ?: throw AssertionError("MainActivity launch monitor timed out")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** Lifecycle observation, not a blind delay: RESUMED plus a real attached decor. */
    private fun awaitMainActivityResumed(activity: MainActivity, timeoutMs: Long = 30_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = mainValue {
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    activity.window?.decorView?.isAttachedToWindow == true &&
                    !activity.isFinishing && !activity.isDestroyed
            }
            if (ready) return true
            SystemClock.sleep(50L)
        }
        return false
    }

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun <T> mainValue(block: () -> T): T {
        var value: Any? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                value = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

/**
 * The agent-first defaults: one explicit open is enough for the real presenter grant, and every
 * person's revocation path still wins. No case taps "Allow agent" or fabricates a grant.
 */
@RunWith(AndroidJUnit4::class)
class NativeBrowserDefaultGrantInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun openDefaultsToGrantAndTakeoverRevokes() {
        val harness = BrowserAuthorityHarness(instrumentation, "default-grant")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-default")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            assertEquals(
                "the open answer must report the default grant as drivable",
                true,
                opened.data.dataBoolean("drivable"),
            )
            assertFalse("the default grant must clear awaitingAllow", harness.state.awaitingAllow)

            val allowed = harness.snapshot("snapshot-after-open")
            assertTrue("snapshot after the default grant failed: ${allowed.errorCode}", allowed.ok)
            assertEquals(true, allowed.data.dataBoolean("drivable"))
            val view = harness.currentWebView() ?: throw AssertionError("browser view missing")
            val document = harness.eval(view, "String(performance.timeOrigin)")

            harness.takeover()
            assertFalse("Take over must revoke the default grant", harness.state.agentOn)
            assertEquals(ProviderMode.MANUAL, harness.state.mode)
            assertNull("Take over must leave no grant", harness.presenter.registry.currentGrant())
            val denied = harness.snapshot("snapshot-after-takeover")
            assertFalse("snapshot after Take over must be refused", denied.ok)
            assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, denied.errorCode)
            // An ordinary snapshot never takes authority back from the person's takeover.
            val deniedAgain = harness.snapshot("snapshot-after-takeover-again")
            assertFalse("a second snapshot must not reclaim the page", deniedAgain.ok)
            assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, deniedAgain.errorCode)
            assertFalse("snapshot must not turn the agent back on", harness.state.agentOn)
            assertEquals(ProviderMode.MANUAL, harness.state.mode)
            assertNull("snapshot must not create a grant", harness.presenter.registry.currentGrant())

            // Hide/show after an explicit takeover is presentation only and an ordinary agent
            // action cannot wake the hidden window past that explicit takeover.
            harness.hide()
            assertTrue("hide after takeover must be recorded", harness.state.hidden)
            val hiddenDenied = harness.snapshot("snapshot-takeover-hidden")
            assertFalse("an ordinary action must not wake a takeover-hidden page", hiddenDenied.ok)
            assertEquals(ProviderCodes.BROWSER_PAGE_UNAVAILABLE, hiddenDenied.errorCode)
            assertTrue("the takeover-hidden page must stay hidden", harness.state.hidden)
            assertNull("the takeover-hidden page must stay unauthorized", harness.presenter.registry.currentGrant())
            assertTrue("user show must present the kept page", harness.show())
            assertFalse("show must not override the explicit takeover", harness.state.agentOn)
            assertNull("show must not create a grant after takeover", harness.presenter.registry.currentGrant())

            // The person's explicit handback is the production Resume agent user action, not a
            // test-side state change. It re-grants the same view/document.
            harness.resumeAgent()
            harness.awaitDefaultGrant()
            assertTrue("Resume agent must turn the agent back on", harness.state.agentOn)
            assertNotNull("Resume agent must create a real grant", harness.presenter.registry.currentGrant())
            assertSame("Resume agent must keep the same view", view, harness.currentWebView())
            assertEquals("Resume agent must keep the same document", document, harness.eval(view, "String(performance.timeOrigin)"))
            val afterResume = harness.snapshot("snapshot-after-resume-agent")
            assertTrue("snapshot after Resume agent failed: ${afterResume.errorCode}", afterResume.ok)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun temporaryPresentationBlockKeepsTheSameGrantAndRefs() {
        val harness = BrowserAuthorityHarness(instrumentation, "presentation-block")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-before-block")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val view = harness.currentWebView() ?: throw AssertionError("browser view missing")
            val before = harness.snapshot("snapshot-before-block")
            assertTrue("snapshot before block failed: ${before.errorCode}", before.ok)
            val apply = selectorFor(before.data, "Apply")
                ?: throw AssertionError("Apply selector missing before block")
            val document = harness.eval(view, "String(performance.timeOrigin)")
            val viewHandle = harness.state.viewHandle
            val grantBefore = harness.presenter.registry.currentGrant()
            assertNotNull("the default grant must exist before the block", grantBefore)

            harness.setPresentationBlocked(true)
            assertTrue("the presentation gate must be recorded", harness.state.presentationBlocked)
            assertFalse("a blocked presentation must not report agent on", harness.state.agentOn)
            assertEquals("the block must keep the same view", viewHandle, harness.state.viewHandle)
            assertTrue("the block must keep the same WebView", harness.currentWebView() === view)
            assertEquals(
                "the block must keep the same real grant",
                grantBefore,
                harness.presenter.registry.currentGrant(),
            )
            val denied = harness.snapshot("snapshot-while-blocked")
            assertFalse("a blocked page must refuse tools", denied.ok)
            assertEquals(ProviderCodes.BROWSER_PAGE_UNAVAILABLE, denied.errorCode)

            harness.setPresentationBlocked(false)
            harness.await("grant restored after the block") {
                harness.state.agentOn && harness.presenter.registry.currentGrant() != null
            }
            assertEquals("the same grant must return after the block", grantBefore, harness.presenter.registry.currentGrant())
            assertEquals("the block must keep the same document", document, harness.eval(view, "String(performance.timeOrigin)"))
            // The ref taken before the block is still current: the block kept refs, not just authority.
            val click = harness.click(apply, "click-after-block")
            assertTrue("the pre-block ref must still click after the block: ${click.errorCode}", click.ok)
            assertEquals(
                jsString("click-count:1"),
                harness.eval(view, "document.getElementById('count').textContent"),
            )
            val after = harness.snapshot("snapshot-after-block")
            assertTrue("snapshot after the block failed: ${after.errorCode}", after.ok)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun automaticControlSurvivesScrollResizeAndRestore() {
        val harness = BrowserAuthorityHarness(instrumentation, "scroll-resize")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-scroll-resize")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val view = harness.currentWebView() ?: throw AssertionError("browser view missing")
            val (fixedWidth, fixedHeight) = harness.viewSize()
            val (initialWidth, initialHeight) = harness.containerSize()
            assertTrue("browser view has no fixed viewport", fixedWidth > 1 && fixedHeight > 1)
            assertTrue("browser container has no size", initialWidth > 1 && initialHeight > 1)
            val innerWidthBefore = harness.eval(view, "String(innerWidth)")

            // A person's page scroll is not a takeover.
            val beforeScroll = harness.eval(view, "String(Math.round(window.scrollY))")
            harness.notePageInteraction()
            // Keep Apply on screen: snapshots intentionally exclude off-viewport elements.
            harness.eval(view, "window.scrollBy(0, 24); String(Math.round(window.scrollY))")
            harness.await("fixture page scrolled") {
                harness.eval(view, "String(Math.round(window.scrollY))") != beforeScroll
            }
            assertTrue("a manual page scroll must not pause agent control", harness.state.agentOn)
            assertNotNull("a manual page scroll must keep its grant", harness.presenter.registry.currentGrant())

            val documentBefore = harness.eval(view, "String(performance.timeOrigin)")
            val viewHandleBefore = harness.state.viewHandle
            val scrollBefore = harness.eval(view, "String(Math.round(window.scrollY))")
            assertNotNull("scroll position missing before resize", scrollBefore)

            fun assertAutomaticControlAfterWindowChange(label: String, clicks: Int) {
                assertTrue("$label must not pause agent control", harness.state.agentOn)
                assertNotNull("$label must keep the grant", harness.presenter.registry.currentGrant())
                assertEquals("$label must keep the same presented view", viewHandleBefore, harness.state.viewHandle)
                assertTrue("$label must keep the same WebView", harness.currentWebView() === view)
                assertEquals(
                    "$label must keep the same document",
                    documentBefore,
                    harness.eval(view, "String(performance.timeOrigin)"),
                )
                assertEquals(
                    "$label must keep the page scroll position",
                    scrollBefore,
                    harness.eval(view, "String(Math.round(window.scrollY))"),
                )
                assertEquals(
                    "$label must keep the WebView at its fixed viewport size",
                    fixedWidth to fixedHeight,
                    harness.viewSize(),
                )
                assertEquals(
                    "$label must keep the fixed mobile CSS viewport",
                    innerWidthBefore,
                    harness.eval(view, "String(innerWidth)"),
                )
                val snapshot = harness.snapshot("snapshot-$label")
                assertTrue("snapshot after $label failed: ${snapshot.errorCode}", snapshot.ok)
                val apply = selectorFor(snapshot.data, "Apply")
                    ?: throw AssertionError("Apply selector missing after $label")
                val click = harness.click(apply, "click-$label")
                assertTrue("click after $label failed: ${click.errorCode}", click.ok)
                assertEquals(
                    "click after $label must reach the page",
                    jsString("click-count:$clicks"),
                    harness.eval(view, "document.getElementById('count').textContent"),
                )
            }

            fun changeWindowSize(
                width: Int,
                height: Int,
                label: String,
                clicks: Int,
                expectScaled: Boolean,
            ) {
                harness.resizeHost(width, height)
                harness.await("$label resized the fixed-viewport container") {
                    val (currentWidth, currentHeight) = harness.containerSize()
                    currentWidth in 1..(width + 1) && currentHeight in 1..(height + 1)
                }
                if (expectScaled) {
                    harness.await("$label scaled the fixed viewport", 10_000L) {
                        harness.containerScale() < 1f
                    }
                } else {
                    harness.await("$label restored 1:1 presentation", 10_000L) {
                        val scale = harness.containerScale()
                        scale >= 0.99f && scale <= 1.01f
                    }
                }
                // The production capture admits only a committed frame: a successful capture is
                // the hardware-drawn evidence for this window size, not a fixed delay or preDraw.
                val captured = harness.awaitCapture(label)
                assertEquals("image/png", captured.data["mime"]?.jsonPrimitive?.contentOrNull)
                assertTrue(
                    "capture after $label must contain pixels",
                    (captured.data["base64"]?.jsonPrimitive?.contentOrNull?.length ?: 0) > 0,
                )
                // Capture is the displayed (scaled) page rect, never the WebView's raw view width.
                // A one-pixel inset is allowed; the two sizes must describe the same drawn page.
                val displayed = harness.displayedPageSize()
                    ?: throw AssertionError("displayed page size missing after $label")
                val capturedWidth = captured.data["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
                val capturedHeight = captured.data["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
                assertTrue(
                    "capture width $capturedWidth must match displayed page rect ${displayed.first} ±1 after $label",
                    abs(capturedWidth - displayed.first) <= 1,
                )
                assertTrue(
                    "capture height $capturedHeight must match displayed page rect ${displayed.second} ±1 after $label",
                    abs(capturedHeight - displayed.second) <= 1,
                )
                assertAutomaticControlAfterWindowChange(label, clicks)
            }

            // Resize (fullscreen/restore style) only changes presentation: same view, document,
            // scroll and grant, and the next snapshot/click keeps working. The production
            // FixedViewportContainer keeps the WebView at its fixed mobile size.
            changeWindowSize(
                maxOf(240, (initialWidth * 3) / 4),
                maxOf(240, (initialHeight * 3) / 4),
                "resize",
                1,
                expectScaled = true,
            )
            changeWindowSize(initialWidth, initialHeight, "restore", 2, expectScaled = false)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun pageNotReadyIsNotGranted() {
        val harness = BrowserAuthorityHarness(instrumentation, "not-ready")
        try {
            harness.holdReadyCallbacks()
            val pending = harness.startOpen(
                OpenChamberProviderFixtures.ORIGIN,
                "open-not-ready",
                DEFAULT_ALLOW_WAIT_MS,
            )
            harness.await("open registered a page that is not ready") {
                harness.state.tabs.isNotEmpty() &&
                    harness.currentWebView() != null &&
                    !harness.state.pageReady
            }
            // The callbacks are held: no real probe can have produced a grant yet.
            SystemClock.sleep(1_000L)
            assertFalse("a page that is not ready must not be granted", harness.state.agentOn)
            assertNull("no grant may exist before the page is ready", harness.presenter.registry.currentGrant())
            assertEquals(ProviderMode.MANUAL, harness.state.mode)

            harness.releaseReadyCallbacks()
            harness.awaitDefaultGrant()
            harness.awaitPageReady()
            val allowed = harness.snapshot("snapshot-after-ready")
            assertTrue("snapshot after the ready default grant failed: ${allowed.errorCode}", allowed.ok)
            val result = runBlocking { pending.await() }
            assertTrue("the open must complete once its page is ready: ${result.errorCode}", result.ok)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun refusedUrlIsNeverOpenedOrGranted() {
        val harness = BrowserAuthorityHarness(instrumentation, "refused-url")
        try {
            val refused = harness.openDefault("http://example.com/", "open-refused-url")
            assertFalse("a non-https URL must be refused", refused.ok)
            assertEquals(ProviderCodes.INVALID_REQUEST, refused.errorCode)
            assertTrue("a refused open must not create a tab", harness.state.tabs.isEmpty())
            assertNull("a refused open must not create a page", harness.currentWebView())
            assertFalse("a refused open must not grant", harness.state.agentOn)
            assertNull("a refused open must leave no grant", harness.presenter.registry.currentGrant())

            val denied = harness.snapshot("snapshot-without-open")
            assertFalse("snapshot without an opened page must be refused", denied.ok)
            assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, denied.errorCode)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun hideWakesOnAgentActionButBackgroundDoesNot() {
        val harness = BrowserAuthorityHarness(instrumentation, "hide-background")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-before-hide")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()

            harness.hide()
            assertFalse("Hide must stop presenting the window", harness.state.shown)
            assertTrue("Hide must be recorded", harness.state.hidden)
            assertFalse("Hide must revoke the default grant", harness.state.agentOn)
            assertNull("Hide must leave no grant", harness.presenter.registry.currentGrant())

            // Showing a kept page is presentation only; it never restores authority.
            assertTrue("show must present the kept page again", harness.show())
            assertTrue("the page must be shown after show", harness.state.shown)
            assertFalse("show must not restore the grant", harness.state.agentOn)
            assertNull("show must leave no grant", harness.presenter.registry.currentGrant())
            val deniedAfterShow = harness.snapshot("snapshot-after-show")
            assertFalse("snapshot after show must be refused", deniedAfterShow.ok)
            assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, deniedAfterShow.errorCode)

            // An ordinary agent action (not an explicit open) wakes a merely hidden window and
            // re-grants through the presenter's allow verification.
            harness.hide()
            assertTrue("the second hide must be recorded", harness.state.hidden)
            val woke = harness.snapshot("snapshot-wakes-hidden")
            assertTrue("an agent action must wake the hidden float: ${woke.errorCode}", woke.ok)
            harness.awaitDefaultGrant()
            assertTrue("the waking action must show the float again", harness.state.shown)
            assertFalse("the waking action must clear the hidden flag", harness.state.hidden)
            val afterWake = harness.snapshot("snapshot-after-wake")
            assertTrue("snapshot after the waking action failed: ${afterWake.errorCode}", afterWake.ok)

            // App background: an agent open must not pop the window, and resume does not regrant.
            harness.pauseHost()
            assertTrue("the host must be recorded as paused", harness.state.paused)
            assertFalse("pause must stop presenting the window", harness.state.shown)
            assertFalse("pause must revoke the grant", harness.state.agentOn)
            assertNull("pause must leave no grant", harness.presenter.registry.currentGrant())
            val tabsBeforeBackgroundOpen = harness.state.tabs.map { it.id }
            val blocked = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-while-background")
            assertFalse("an agent open must not pop the background window", blocked.ok)
            assertFalse("background refusal must not show the window", harness.state.shown)
            assertFalse("background refusal must not grant", harness.state.agentOn)
            assertNull("background refusal must leave no grant", harness.presenter.registry.currentGrant())
            assertEquals(
                "background refusal must not create a tab",
                tabsBeforeBackgroundOpen,
                harness.state.tabs.map { it.id },
            )

            harness.resumeHost()
            assertFalse("resume must not re-enable the agent", harness.state.agentOn)
            assertNull("resume must leave no grant", harness.presenter.registry.currentGrant())
            val deniedAfterResume = harness.snapshot("snapshot-after-resume")
            assertFalse("snapshot after resume must be refused", deniedAfterResume.ok)
            assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, deniedAfterResume.errorCode)

            // The explicit window-background entry revokes a fresh default grant as well.
            val regranted = harness.openDefault(OpenChamberProviderFixtures.ORIGIN2, "open-before-background")
            assertTrue("open before background failed: ${regranted.errorCode}", regranted.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            harness.background()
            assertFalse("background must revoke the default grant", harness.state.agentOn)
            assertFalse("background must stop presenting the window", harness.state.shown)
            assertNull("background must leave no grant", harness.presenter.registry.currentGrant())
        } finally {
            harness.dispose()
        }
    }
}

/**
 * Real Clear site data: the live browser profile loses its cookie and localStorage while a second
 * named profile keeps both. The clear also revokes agent control, as the production action does.
 */
@RunWith(AndroidJUnit4::class)
class BrowserSiteDataIsolationInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun clearRemovesBrowserProfileDataAndKeepsAnotherProfile() {
        val harness = BrowserAuthorityHarness(instrumentation, "site-data")
        try {
            val opened = harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "site-open")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            val browserView = harness.currentWebView() ?: throw AssertionError("browser view missing")
            harness.await("browser fixture document") {
                harness.eval(
                    browserView,
                    "document.getElementById('marker') && document.getElementById('marker').textContent",
                ) == jsString("provider")
            }
            val browserCookies = harness.profileCookies(browserView)
            browserCookies.setCookie(OpenChamberProviderFixtures.ORIGIN, "$SITE_COOKIE=$BROWSER_VALUE; Path=/")
            browserCookies.flush()
            assertEquals(
                "browser cookie must exist before clear",
                BROWSER_VALUE,
                cookieValue(browserCookies.getCookie(OpenChamberProviderFixtures.ORIGIN), SITE_COOKIE),
            )
            assertEquals(
                jsString(BROWSER_VALUE),
                harness.eval(
                    browserView,
                    "localStorage.setItem('$SITE_STORAGE_KEY', '$BROWSER_VALUE');" +
                        "localStorage.getItem('$SITE_STORAGE_KEY')",
                ),
            )
            val timeOriginBeforeClear = harness.eval(browserView, "String(performance.timeOrigin)")
            assertNotNull("browser document timeOrigin missing", timeOriginBeforeClear)

            val otherView = harness.createBoundProfileView(OTHER_PROFILE)
            harness.await("other profile document") {
                harness.eval(
                    otherView,
                    "document.getElementById('marker') && document.getElementById('marker').textContent",
                ) == jsString("other-profile")
            }
            val otherCookies = harness.profileCookies(otherView)
            otherCookies.setCookie(OpenChamberProviderFixtures.ORIGIN, "$SITE_COOKIE=$OTHER_VALUE; Path=/")
            otherCookies.flush()
            assertEquals(
                "other profile cookie must exist before clear",
                OTHER_VALUE,
                cookieValue(otherCookies.getCookie(OpenChamberProviderFixtures.ORIGIN), SITE_COOKIE),
            )
            assertEquals(
                jsString(OTHER_VALUE),
                harness.eval(
                    otherView,
                    "localStorage.setItem('$SITE_STORAGE_KEY', '$OTHER_VALUE');" +
                        "localStorage.getItem('$SITE_STORAGE_KEY')",
                ),
            )

            val result = runBlocking {
                withContext(Dispatchers.Main.immediate) {
                    BrowserSiteDataClearer(PresenterSiteDataOps(harness.presenter)).clear()
                }
            }
            assertTrue("clear failed: $result", result is SiteDataClearResult.Cleared)
            assertTrue("clear must reload the shown page", (result as SiteDataClearResult.Cleared).reloaded)
            assertFalse("Clear site data must revoke agent control", harness.state.agentOn)
            assertNull("Clear site data must leave no grant", harness.presenter.registry.currentGrant())

            // A new document proves the reload happened before the storage assertions run.
            harness.await("browser page reloaded", 20_000L) {
                val origin = harness.eval(browserView, "String(performance.timeOrigin)")
                origin != null && origin != timeOriginBeforeClear &&
                    harness.eval(browserView, "document.readyState") == jsString("complete")
            }
            assertNull(
                "browser profile cookie must be removed",
                cookieValue(browserCookies.getCookie(OpenChamberProviderFixtures.ORIGIN), SITE_COOKIE),
            )
            assertEquals(
                "browser profile localStorage must be removed",
                "null",
                harness.eval(browserView, "localStorage.getItem('$SITE_STORAGE_KEY')"),
            )

            assertEquals(
                "another profile's cookie must survive",
                OTHER_VALUE,
                cookieValue(otherCookies.getCookie(OpenChamberProviderFixtures.ORIGIN), SITE_COOKIE),
            )
            assertEquals(
                "another profile's localStorage must survive",
                jsString(OTHER_VALUE),
                harness.eval(otherView, "localStorage.getItem('$SITE_STORAGE_KEY')"),
            )
        } finally {
            harness.dispose()
        }
    }
}
