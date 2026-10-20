package io.andchamber.app.ui.web

import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.andchamber.app.data.DefaultWebSessionAuthenticator
import io.andchamber.app.data.HttpDownloadRequest
import io.andchamber.app.data.HttpDownloadResult
import io.andchamber.app.data.HttpDownloadStarter
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.runtime.WebSessionResult
import io.andchamber.app.testhost.WebViewRegressionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Real Chromium + real profile CookieManager + production native authenticator. */
@RunWith(AndroidJUnit4::class)
class GatewayCookieRaceTest {
    private lateinit var scenario: ActivityScenario<WebViewRegressionActivity>
    private lateinit var web: ChamberWebView
    private lateinit var target: ServerTarget.Remote
    private val server = MockWebServer()
    private val oldReceived = CountDownLatch(1)
    private val releaseOld = CountDownLatch(1)
    private val oldDispatched = CountDownLatch(1)
    private val posts = AtomicInteger()
    private var expireOld = true
    private val auth = DefaultWebSessionAuthenticator(OkHttpClient(), localPassword = { null })

    @Before fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val valid = request.getHeader("Cookie").orEmpty().contains("oc_ui_session=valid-test")
                return when {
                    path == "/health" -> MockResponse().setBody("""{"status":"ok"}""")
                    path == "/auth/session" && request.method == "POST" -> {
                        posts.incrementAndGet()
                        MockResponse().setBody("{}").addHeader("Set-Cookie", SESSION)
                    }
                    path == "/auth/session?old=1" -> {
                        check(!valid) { "Old request must precede native login" }
                        oldReceived.countDown()
                        check(releaseOld.await(20, TimeUnit.SECONDS)) { "Old headers were never released" }
                        val response = MockResponse().setResponseCode(401).setBody("old-response")
                        if (expireOld) response.addHeader("Set-Cookie", EXPIRY)
                        oldDispatched.countDown()
                        response
                    }
                    path == "/auth/session" -> MockResponse().setResponseCode(if (valid) 200 else 401).setBody("{}")
                    path == "/return" -> html("""
                        window.oldDone=false;
                        fetch('/auth/session?old=1').then(r=>r.text()).then(()=>window.oldDone=true);
                    """)
                    path == "/?session=handoff-test" -> html("""
                        fetch('/auth/session').then(r=>document.title=r.status===200?'authenticated':'unlock');
                    """)
                    path == "/network-error" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        target = ServerTarget.Remote("webview-test-${UUID.randomUUID()}", "Test fixture", server.url("/").toString().trimEnd('/'), "dummy-test-password")
        scenario = ActivityScenario.launch(WebViewRegressionActivity::class.java)
        scenario.onActivity { activity ->
            // Unsupported MULTI_PROFILE is a failed proof, never an assumption/ignored test.
            assertTrue("MULTI_PROFILE required for this regression proof", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            val binding = WebSessionProfiles().acquire(target)
            GatewayFixtureProfiles.register(binding.profileName, binding.identity)
            web = ChamberWebView(activity, binding, false, Color.WHITE, object : HttpDownloadStarter {
                override fun start(cookies: WebSessionCookies, request: HttpDownloadRequest, onFinished: (HttpDownloadResult) -> Unit) {
                    error("Downloads are not part of this fixture")
                }
            })
            activity.container.addView(web.attachableView())
        }
        cookies().storeCookies(target.baseUrl, listOf("cf_clearance=test-marker; Path=/; HttpOnly; SameSite=Strict"))
        main { web.load("${target.baseUrl}/return", "test-session") }
        assertTrue("Old request headers received", oldReceived.await(10, TimeUnit.SECONDS))
    }

    @After fun tearDown() {
        releaseOld.countDown()
        if (::scenario.isInitialized) {
            scenario.onActivity {
                if (::web.isInitialized) {
                    it.container.removeAllViews()
                    web.destroy()
                }
            }
            scenario.close()
        }
        server.shutdown()
    }

    @Test fun baselineLateExpiryDeletesCommittedNativeCookie() = runBlocking {
        prepare()
        releaseOld.countDown()
        until { js("window.oldDone") == "true" }
        until { !hasSession() }
        assertMarker()
    }

    @Test fun handoffRejectsLateExpiryAndLoadsAuthenticatedSessionPath() = runBlocking {
        val identity = web.binding.identity
        val profile = web.binding.profileName
        val ticket = main { web.beginGatewayHandoff() }
        assertTrue(ticket > 0)
        assertTrue(main { web.awaitGatewayHandoff(ticket) })
        assertMarker()
        assertEquals(identity, cookies().identity)
        main { assertEquals(profile, WebViewCompat.getProfile(web.view!!).name) }
        // Obtain the cookie capability AFTER the barrier: the production view may be replaced.
        prepare()
        releaseOld.countDown()
        assertTrue(oldDispatched.await(10, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            activity.container.removeAllViews()
            activity.container.addView(web.attachableView())
        }
        main {
            web.load("${target.baseUrl}/?session=handoff-test", "test-session")
        }
        until { js("document.title") == "\"authenticated\"" }
        assertTrue("Late old 401 must not delete newly committed cookie", hasSession())
        assertEquals("\"/?session=handoff-test\"", js("location.pathname+location.search"))
        assertEquals(identity, cookies().identity)
        assertMarker()
        assertEquals(1, posts.get())
    }

    @Test fun earlyExpiryBeforeNativeCommitIsHarmless() = runBlocking {
        releaseOld.countDown()
        until { js("window.oldDone") == "true" }
        prepare()
        assertTrue(hasSession())
        assertMarker()
    }

    @Test fun late401WithoutExpiryDoesNotDeleteCookie() = runBlocking {
        expireOld = false
        prepare()
        releaseOld.countDown()
        until { js("window.oldDone") == "true" }
        assertTrue(hasSession())
        assertMarker()
    }

    @Test fun cancelledHandoffNeverSendsNativePassword() = runBlocking {
        val ticket = main { web.beginGatewayHandoff() }
        main { web.cancelGatewayHandoff(ticket) }
        if (main { web.awaitGatewayHandoff(ticket) }) auth.prepare(target, cookies())
        assertEquals(0, posts.get())
        assertMarker()
    }

    @Test fun destroyedHandoffNeverSendsNativePassword() = runBlocking {
        val ticket = main { web.beginGatewayHandoff() }
        main { web.destroy() }
        assertFalse(main { web.awaitGatewayHandoff(ticket) })
        assertEquals(-1L, main { web.beginGatewayHandoff() })
        assertEquals(0, posts.get())
    }

    @Test fun duplicateBeginCoalescesOneTicketAndOneNativeLogin() = runBlocking {
        val callbacks = AtomicInteger()
        val tickets = main {
            web.onGatewayCompleted = { callbacks.incrementAndGet() }
            web.beginGatewayHandoff() to web.beginGatewayHandoff()
        }
        assertTrue(tickets.first > 0)
        assertEquals(tickets.first, tickets.second)
        assertTrue(main { web.awaitGatewayHandoff(tickets.first) })
        until { callbacks.get() == 1 }
        prepare()
        assertEquals(1, callbacks.get())
        assertEquals(1, posts.get())
    }

    @Test fun completedThenCancelledHandoffRetriesWithFreshDocumentOnSameView() = runBlocking {
        val underlying = main { web.view!! }
        val first = main { web.beginGatewayHandoff() }
        assertTrue(first > 0)
        assertTrue(main { web.awaitGatewayHandoff(first) })
        // A fragment-only retry would retain this JS global (or never finish its new barrier).
        assertEquals("true", js("window.__fixtureDocumentSentinel='first-document';true"))
        main { web.cancelGatewayHandoff(first) }
        val retry = main { web.beginGatewayHandoff() }
        assertTrue("Retry must allocate a new handoff ticket", retry > first)
        assertFalse("Cancelled ticket cannot release the retry", main { web.awaitGatewayHandoff(first) })
        assertTrue("Fresh-document retry must complete without a fragment-only hang", main { web.awaitGatewayHandoff(retry) })
        main { assertSame("Retry must use the same underlying WebView", underlying, web.view) }
        assertEquals("\"undefined\"", js("typeof window.__fixtureDocumentSentinel"))
        finishAuthenticatedRecovery("completed/cancelled retry")

        // Real Chromium connection failure: a legitimate NEW page's error must still be visible.
        main { assertTrue(web.navigateWithinSession("${target.baseUrl}/network-error")) }
        until("new /network-error navigation must expose pageError") { main { web.pageError != null } }
        main { web.load("${target.baseUrl}/?session=handoff-test", "test-session") }
        until("valid navigation after network error must become authenticated") { js("document.title") == "\"authenticated\"" }
        main { assertNull("A subsequent valid page must not inherit a stale page error", web.pageError) }
        assertEquals("\"/?session=handoff-test\"", js("location.pathname+location.search"))
        assertTrue(hasSession())
        assertMarker()
    }

    @Test fun pooledDetachAndNewCallbackOwnerCannotReviveOldHandoff() = runBlocking {
        val underlying = main { web.view!! }
        val identity = web.binding.identity
        val profile = web.binding.profileName
        val oldCallbacks = AtomicInteger()
        val newCallbacks = ConcurrentLinkedQueue<Long>()
        var oldTicket = -1L
        var newTicket = -1L
        scenario.onActivity { activity ->
            web.onGatewayCompleted = { oldCallbacks.incrementAndGet() }
            oldTicket = web.beginGatewayHandoff()
            // Dispose the old screen owner before Main can dispatch its posted callback.
            activity.container.removeAllViews()
            web.cancelGatewayHandoff(oldTicket)
            web.onGatewayCompleted = null
            // Pool re-entry keeps the exact same ChamberWebView, WebView and profile.
            activity.container.addView(web.attachableView())
            web.onGatewayCompleted = { ticket -> newCallbacks.add(ticket) }
            newTicket = web.beginGatewayHandoff()
        }
        assertTrue(oldTicket > 0)
        assertTrue("Re-entry must allocate a new ticket", newTicket > oldTicket)
        main { web.cancelGatewayHandoff(oldTicket) }
        assertFalse("Disposed owner's ticket must stay invalid", main { web.awaitGatewayHandoff(oldTicket) })
        assertTrue(main { web.awaitGatewayHandoff(newTicket) })
        until { newCallbacks.isNotEmpty() }
        assertEquals("Disposed callback owner must not be notified", 0, oldCallbacks.get())
        assertEquals("A stale posted callback must not be delivered to the new owner", listOf(newTicket), newCallbacks.toList())
        main {
            assertSame(underlying, web.view)
            assertEquals(profile, WebViewCompat.getProfile(web.view!!).name)
        }
        assertEquals(identity, cookies().identity)
        finishAuthenticatedRecovery("pooled-owner retry")
        assertEquals(listOf(newTicket), newCallbacks.toList())
        assertEquals(0, oldCallbacks.get())
    }

    private suspend fun finishAuthenticatedRecovery(stage: String) {
        assertEquals(target.id, cookies().identity.targetId)
        assertMarker()
        prepare()
        releaseOld.countDown()
        assertTrue(oldDispatched.await(10, TimeUnit.SECONDS))
        main { web.load("${target.baseUrl}/?session=handoff-test", "test-session") }
        until("$stage: final target document must become authenticated") { js("document.title") == "\"authenticated\"" }
        main {
            assertNull("Internal blank/old document state must not poison the valid target page", web.pageError)
            assertEquals(web.binding.profileName, WebViewCompat.getProfile(web.view!!).name)
        }
        assertEquals("\"/?session=handoff-test\"", js("location.pathname+location.search"))
        assertTrue("Late expiry must not delete the recovered session", hasSession())
        assertMarker()
        assertEquals(1, posts.get())
    }

    private suspend fun prepare() {
        assertTrue("Production native prepare must be Ready", auth.prepare(target, cookies()) is WebSessionResult.Ready)
        assertTrue("setCookie callback must commit native session", hasSession())
    }
    private suspend fun cookies() = main { checkNotNull(web.sessionCookies) }
    private suspend fun hasSession() = cookies().cookieHeader(target.baseUrl).orEmpty().contains("oc_ui_session=valid-test")
    private suspend fun assertMarker() = assertTrue("CF-like marker must survive", cookies().cookieHeader(target.baseUrl).orEmpty().contains("cf_clearance=test-marker"))
    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun js(script: String): String = main {
        suspendCancellableCoroutine { continuation -> web.view!!.evaluateJavascript(script) { continuation.resume(it) } }
    }
    private suspend fun until(label: String = "observable condition", condition: suspend () -> Boolean) {
        Log.i("GatewayRecoveryTest", "waiting: $label")
        try {
            withTimeout(10_000) {
                // Poll an observable condition; elapsed time alone never counts as evidence.
                while (!condition()) delay(10)
            }
            Log.i("GatewayRecoveryTest", "completed: $label")
        } catch (timeout: TimeoutCancellationException) {
            val state = main {
                val url = web.view?.url
                val category = when {
                    url?.startsWith("chrome-error:") == true -> "chrome-error"
                    url?.startsWith("about:") == true -> "blank"
                    originOf(url) == web.binding.identity.origin -> "target"
                    else -> "other"
                }
                "pageErrorHasValue=${web.pageError != null} viewCategory=$category"
            }
            // Diagnostic-only snapshot after the unchanged deadline; never log a raw URL.
            val origin = JSONObject.quote(web.binding.identity.origin)
            val documentCategory = withTimeoutOrNull(1_000) {
                js("""(()=>{const u=document.documentURI||'';return u.startsWith('chrome-error:')?'chrome-error':u.startsWith('about:')?'blank':location.origin===$origin?'target':'other'})()""")
            } ?: "unavailable"
            Log.e("GatewayRecoveryTest", "timeout: $label $state documentCategory=$documentCategory")
            throw AssertionError("Timed out waiting for: $label; $state documentCategory=$documentCategory", timeout)
        }
    }
    private fun html(script: String) = MockResponse().addHeader("Content-Type", "text/html").setBody("<!doctype html><title>waiting</title><script>$script</script>")

    companion object {
        private const val SESSION = "oc_ui_session=valid-test; Path=/; HttpOnly; SameSite=Strict"
        private const val EXPIRY = "oc_ui_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
    }
}
