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
import kotlin.coroutines.resume

/**
 * Real Chromium: a live page follows the app theme. prefers-color-scheme flips in place, without a
 * reload, when the app theme changes after the WebView was created.
 */
@RunWith(AndroidJUnit4::class)
class WebThemeFollowTest {
    private val server = MockWebServer()
    private var scenario: ActivityScenario<WebViewRegressionActivity>? = null
    private var web: ChamberWebView? = null

    @After fun tearDown() {
        scenario?.onActivity {
            it.container.removeAllViews()
            web?.destroy()
        }
        scenario?.close()
        server.shutdown()
    }

    @Test fun pageFollowsThemeFlipsWithoutReload() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().addHeader("Content-Type", "text/html")
                    .setBody("<!doctype html><title>page</title><p>fixture</p>")
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val target = ServerTarget.Remote("theme-test-${UUID.randomUUID()}", "Theme fixture", base, null)
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
        main { web!!.load("$base/", "theme-test") }
        until { js("document.title") == "\"page\"" }
        js("window.marker = 'kept'; 0")
        assertEquals("false", js("matchMedia('(prefers-color-scheme: dark)').matches"))

        main { web!!.applyTheme(true, Color.BLACK) }
        until { js("matchMedia('(prefers-color-scheme: dark)').matches") == "true" }

        main { web!!.applyTheme(false, Color.WHITE) }
        until { js("matchMedia('(prefers-color-scheme: dark)').matches") == "false" }

        assertEquals("the page must not reload on a theme flip", "\"kept\"", js("window.marker"))
    }

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun js(script: String): String = main {
        suspendCancellableCoroutine { continuation -> web!!.view!!.evaluateJavascript(script) { continuation.resume(it) } }
    }

    private suspend fun until(condition: suspend () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(20) }
    }
}
