package io.andchamber.app.ui.web

import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import io.andchamber.app.data.HttpDownloadRequest
import io.andchamber.app.data.HttpDownloadResult
import io.andchamber.app.data.HttpDownloadStarter
import io.andchamber.app.data.WebSessionProfiles
import io.andchamber.app.runtime.ServerTarget
import io.andchamber.app.runtime.WebSessionCookies
import io.andchamber.app.testhost.WebViewRegressionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * Real Chromium: the local target's WebView answers OpenChamber's update check itself, so the request
 * never reaches the server; a remote target's request goes through unchanged.
 */
@RunWith(AndroidJUnit4::class)
class LocalUpdateGateWebViewTest {
    private val server = MockWebServer()
    private val paths = ConcurrentLinkedQueue<String>()
    private var scenario: ActivityScenario<WebViewRegressionActivity>? = null
    private var web: ChamberWebView? = null

    private fun start(local: Boolean) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                return when {
                    path == "/" -> MockResponse().addHeader("Content-Type", "text/html")
                        .setBody("<!doctype html><title>page</title><p>fixture</p>")
                    path.startsWith(LocalUpdateGate.UPDATE_CHECK_PATH) ->
                        MockResponse().addHeader("Content-Type", "application/json")
                            .setBody("""{"available":true,"version":"99.0.0","currentVersion":"1.0.0"}""")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val target: ServerTarget =
            if (local) ServerTarget.Local(base) else ServerTarget.Remote("gate-test-${UUID.randomUUID()}", "Gate fixture", base, null)
        scenario = ActivityScenario.launch(WebViewRegressionActivity::class.java).also {
            it.onActivity { activity ->
                assertTrue("MULTI_PROFILE required", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                val binding = WebSessionProfiles().acquire(target)
                GatewayFixtureProfiles.register(binding.profileName, binding.identity)
                web = ChamberWebView(activity, binding, false, Color.WHITE, object : HttpDownloadStarter {
                    override fun start(cookies: WebSessionCookies, request: HttpDownloadRequest, onFinished: (HttpDownloadResult) -> Unit) =
                        error("Downloads are not part of this fixture")
                })
                activity.container.addView(web!!.attachableView())
            }
        }
        runBlocking {
            main { web!!.load("$base/", "gate-test") }
            until { js("document.title") == "\"page\"" }
        }
    }

    @After fun tearDown() {
        scenario?.onActivity {
            it.container.removeAllViews()
            web?.destroy()
        }
        scenario?.close()
        server.shutdown()
    }

    @Test fun localTargetAnswersUpdateCheckWithoutReachingServer() = runBlocking {
        start(local = true)
        val body = fetchUpdateCheck()
        assertEquals("""{"status":200,"available":false}""", body)
        assertTrue("update check must not reach the server: $paths", paths.none { it.startsWith(LocalUpdateGate.UPDATE_CHECK_PATH) })
    }

    @Test fun remoteTargetUpdateCheckReachesServer() = runBlocking {
        start(local = false)
        val body = fetchUpdateCheck()
        assertEquals("""{"status":200,"available":true}""", body)
        assertTrue("remote update check must reach the server: $paths", paths.any { it.startsWith(LocalUpdateGate.UPDATE_CHECK_PATH) })
    }

    private suspend fun fetchUpdateCheck(): String {
        main {
            web!!.view!!.evaluateJavascript(
                """window.gateResult=null;fetch('${LocalUpdateGate.UPDATE_CHECK_PATH}?appType=web&reportUsage=false')
                  .then(r=>r.json().then(j=>window.gateResult=JSON.stringify({status:r.status,available:j.available})))
                  .catch(e=>window.gateResult='error:'+e)""",
                null,
            )
        }
        until { js("window.gateResult") != "null" }
        return org.json.JSONTokener(js("window.gateResult")).nextValue() as String
    }

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun js(script: String): String = main {
        suspendCancellableCoroutine { continuation -> web!!.view!!.evaluateJavascript(script) { continuation.resume(it) } }
    }

    private suspend fun until(condition: suspend () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(20) }
    }
}
