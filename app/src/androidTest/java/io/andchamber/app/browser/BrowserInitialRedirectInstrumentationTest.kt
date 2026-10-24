package io.andchamber.app.browser

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.view.ViewGroup
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
import androidx.webkit.WebViewFeature
import io.andchamber.app.MainActivity
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import io.andchamber.app.ui.browser.NativeBrowserState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val INJECTED_REDIRECT_TARGET = "https://example.com/openchamber-provider-initial-redirect"
private const val OPEN_TIMEOUT_MS = 120_000L
private const val OPEN_ALLOW_WAIT_MS = 60_000
private const val READY_WAIT_MS = 90_000L

/** A callback-shaped request. It is not a WebView 302; the deterministic test calls the client directly. */
private class SyntheticRedirectRequest(
    private val target: Uri,
    private val mainFrame: Boolean = true,
    private val redirect: Boolean = true,
    private val httpMethod: String = "GET",
) : WebResourceRequest {
    override fun getUrl(): Uri = target
    override fun isForMainFrame(): Boolean = mainFrame
    override fun isRedirect(): Boolean = redirect
    override fun hasGesture(): Boolean = false
    override fun getMethod(): String = httpMethod
    override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
}

/**
 * One presenter, one host and one page for the deterministic redirect probe. It intentionally uses
 * the production presenter, page factory and WebViewClient. The only test hooks are the debug-only
 * page decorator (to attach the in-memory fixture) and a direct call to the production client with
 * a synthetic redirect request. The wrapper always delegates to the inner client, so the production
 * admission and navigation policy still make every decision.
 */
private class RedirectProbeHarness(
    private val instrumentation: Instrumentation,
    private val instanceKey: String,
    private val policy: BrowserUrlPolicy,
) {
    val activity: MainActivity
    val presenter: NativeBrowserPresenter
    val host: FrameLayout
    private val sessionId = "session-$instanceKey"
    private val connectionId = "connection-$instanceKey"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val injected = AtomicBoolean()
    private val injectionBeforeReady = AtomicBoolean()
    private val injectionOverrideAllowed = AtomicBoolean()
    private val injectionInterceptBlocked = AtomicBoolean()
    private val injectionSeen = AtomicInteger()
    private val pageStarts = AtomicInteger()
    private val productionStartForwarded = AtomicBoolean()

    init {
        assertTrue(
            "MULTI_PROFILE is required; profile isolation must fail closed rather than skip",
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
        )
        assertTrue(
            "DOCUMENT_START_SCRIPT is required for a controlled browser page",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        )
        activity = resumedMainActivity() ?: launchMainActivity()
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
            presenter.installPageDecorator { page ->
                val view = page.view as? WebView ?: error("browser page view is not a WebView")
                OpenChamberProviderFixtures.attach(view, view.webViewClient)
                val inner = view.webViewClient
                view.webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(target: WebView, url: String?, isReload: Boolean) =
                        inner.doUpdateVisitedHistory(target, url, isReload)

                    override fun shouldOverrideUrlLoading(target: WebView, request: WebResourceRequest): Boolean =
                        inner.shouldOverrideUrlLoading(target, request)

                    override fun shouldInterceptRequest(target: WebView, request: WebResourceRequest): WebResourceResponse? =
                        inner.shouldInterceptRequest(target, request)

                    override fun onPageStarted(target: WebView, url: String?, favicon: Bitmap?) {
                        pageStarts.incrementAndGet()
                        if (injected.compareAndSet(false, true)) {
                            // The production client has not seen this start yet, so the session still
                            // has starts=0, no accepted witness and is not ready. This is a callback
                            // shape test, not a claim that the WebView followed a real 302.
                            val beforeProductionStart = !productionStartForwarded.get()
                            val state = presenter.state.value
                            injectionBeforeReady.set(beforeProductionStart && !state.pageReady && !state.requiresReopen)
                            val fake = SyntheticRedirectRequest(Uri.parse(INJECTED_REDIRECT_TARGET))
                            val overrideResult = inner.shouldOverrideUrlLoading(target, fake)
                            injectionOverrideAllowed.set(!overrideResult)
                            val interceptResult = inner.shouldInterceptRequest(target, fake)
                            injectionInterceptBlocked.set(interceptResult != null)
                            injectionSeen.incrementAndGet()
                        }
                        productionStartForwarded.set(true)
                        inner.onPageStarted(target, url, favicon)
                    }

                    override fun onPageCommitVisible(target: WebView, url: String?) =
                        inner.onPageCommitVisible(target, url)

                    override fun onPageFinished(target: WebView, url: String?) =
                        inner.onPageFinished(target, url)

                    override fun onScaleChanged(target: WebView, oldScale: Float, newScale: Float) =
                        inner.onScaleChanged(target, oldScale, newScale)

                    override fun onReceivedError(target: WebView, request: WebResourceRequest, error: WebResourceError) =
                        inner.onReceivedError(target, request, error)

                    override fun onReceivedSslError(target: WebView, handler: SslErrorHandler, error: SslError) =
                        inner.onReceivedSslError(target, handler, error)

                    override fun onRenderProcessGone(target: WebView, detail: RenderProcessGoneDetail): Boolean =
                        inner.onRenderProcessGone(target, detail)
                }
                host.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    val state: NativeBrowserState
        get() = presenter.state.value

    val injectionCount: Int get() = injectionSeen.get()
    val wasInjectedBeforeReady: Boolean get() = injectionBeforeReady.get()
    val injectedOverrideAllowed: Boolean get() = injectionOverrideAllowed.get()
    val injectedInterceptBlocked: Boolean get() = injectionInterceptBlocked.get()
    val pageStartCount: Int get() = pageStarts.get()

    fun open(url: String, requestId: String): ProviderResult = runBlocking {
        presenter.execute(
            ProviderRequest(
                serverEpoch = "instrumentation-test",
                connectionId = connectionId,
                requestId = requestId,
                action = "browser.open",
                parameters = mapOf("url" to url),
                directory = "/",
                sessionId = sessionId,
                timeoutMs = OPEN_TIMEOUT_MS,
                allowWaitMs = OPEN_ALLOW_WAIT_MS,
            ),
            presenter.registry,
            ProviderMode.MANUAL,
            null,
            SystemClock.elapsedRealtime() + OPEN_TIMEOUT_MS,
        ) { SystemClock.elapsedRealtime() }
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
                timeoutMs = 10_000L,
            ),
            presenter.registry,
            state.mode,
            state.selectedTabId,
            SystemClock.elapsedRealtime() + 10_000L,
        ) { SystemClock.elapsedRealtime() }
    }

    fun awaitReadyAndGrant() {
        await("page ready without reopen") {
            state.pageReady && state.shown && state.eligible && !state.requiresReopen
        }
        await("default grant") {
            state.agentOn && state.mode == ProviderMode.AGENT && presenter.registry.currentGrant() != null
        }
    }

    fun await(what: String, timeoutMs: Long = READY_WAIT_MS, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("timed out waiting for $what; ${diagnostic()}")
    }

    /** No URL, host, title, script or page text: only fixed state flags and callback counts. */
    fun diagnostic(): String =
        "state(pageReady=${state.pageReady},shown=${state.shown},eligible=${state.eligible}," +
            "requiresReopen=${state.requiresReopen},agentOn=${state.agentOn}," +
            "mode=${state.mode},pageErrorPresent=${state.pageError != null}); " +
            "injected(count=$injectionCount,starts=$pageStartCount,beforeReady=$wasInjectedBeforeReady," +
            "overrideAllowed=$injectedOverrideAllowed,interceptBlocked=$injectedInterceptBlocked)"

    fun dispose() {
        scope.cancel()
        onMain {
            presenter.detachHost(instanceKey, 1L)
            (host.parent as? ViewGroup)?.removeView(host)
            host.removeAllViews()
        }
    }

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

private fun fixtureRedirectPolicy(): BrowserUrlPolicy = BrowserUrlPolicy(
    resolver = DnsResolver { host ->
        check(host == "example.com") { "unexpected fixture host" }
        listOf(InetAddress.getByName("2001:db8::1"))
    },
)

/**
 * Deterministic callback-level regression for the first-open redirect. The production client is
 * called with the exact main-frame GET + redirect shape at the first document start, before that
 * start reaches the session, so starts=0 is genuine and the page cannot be ready yet. The call is
 * not a real 302 and does not claim to be one; it checks the override/intercept decisions and then
 * lets the real fixture document complete the normal ready/grant/snapshot path. The test uses only
 * the in-memory example.com fixture and no external network.
 */
@RunWith(AndroidJUnit4::class)
class BrowserInitialRedirectInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun injectedInitialRedirectContinuesAndStillReachesReadyGrant() {
        val harness = RedirectProbeHarness(
            instrumentation = instrumentation,
            instanceKey = "initial-redirect-injected",
            policy = fixtureRedirectPolicy(),
        )
        try {
            val opened = harness.open(OpenChamberProviderFixtures.ORIGIN, "injected-redirect-open")
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            harness.awaitReadyAndGrant()

            assertEquals("the synthetic redirect callback must run exactly once", 1, harness.injectionCount)
            assertEquals(
                "the initial controlled load must not be replaced by a second load; ${harness.diagnostic()}",
                1,
                harness.pageStartCount,
            )
            assertTrue(
                "the callback was not injected before the initial start/readiness; ${harness.diagnostic()}",
                harness.wasInjectedBeforeReady,
            )
            assertTrue(
                "the production override must let the controlled initial redirect continue; ${harness.diagnostic()}",
                harness.injectedOverrideAllowed,
            )
            assertFalse(
                "the production intercept must not block the controlled initial redirect; ${harness.diagnostic()}",
                harness.injectedInterceptBlocked,
            )
            assertFalse("the final document must not require reopen; ${harness.diagnostic()}", harness.state.requiresReopen)

            val snapshot = harness.snapshot("injected-redirect-snapshot")
            assertTrue("snapshot after the redirect continuation failed: ${snapshot.errorCode}", snapshot.ok)
            val elements = snapshot.data["elements"]?.jsonArray
            assertNotNull("snapshot must contain an element list", elements)
            assertFalse("snapshot must be readable after the redirect", elements!!.isEmpty())
        } finally {
            harness.dispose()
        }
    }
}
