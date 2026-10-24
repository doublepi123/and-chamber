package io.andchamber.app.browser

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Callback-shape regression for the initial about:blank `onPageFinished` before the controlled
 * target's first document is accepted.
 *
 * The production client is called directly with `"about:blank"` immediately after an open with no
 * allow wait, not from a synthetic witness and not by clearing main starts. This is a callback
 * shape test, not a claim that the WebView always delivers that callback before `onPageStarted`;
 * the deterministic classifier/session cases live in `BrowserInitialNoWitnessProbeTest`.
 *
 * The final page must still become ready and granted without `requiresReopen`.
 */
@RunWith(AndroidJUnit4::class)
class BrowserInitialBlankProbeInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun initialBlankFinishedDoesNotForceReopenBeforeTheTargetDocument() {
        val key = "initial-blank"
        val harness = BrowserAuthorityHarness(instrumentation, key)
        try {
            val request = ProviderRequest(
                serverEpoch = "instrumentation-test",
                connectionId = "connection-$key",
                requestId = "blank-open",
                action = "browser.open",
                parameters = mapOf("url" to OpenChamberProviderFixtures.ORIGIN),
                directory = "/",
                sessionId = harness.sessionId,
                timeoutMs = 90_000L,
                allowWaitMs = 0,
            )
            val opened = runBlocking {
                harness.presenter.execute(
                    request,
                    harness.presenter.registry,
                    ProviderMode.MANUAL,
                    null,
                    SystemClock.elapsedRealtime() + 90_000L,
                ) { SystemClock.elapsedRealtime() }
            }
            assertTrue("open failed: ${opened.errorCode} ${opened.message}", opened.ok)
            val view = harness.currentWebView()
            assertNotNull("browser view missing after load-start", view)
            // Callback-shape observation only: a very fast fixture may already be ready here. The
            // deterministic before-start classification is covered by the JVM tests; this log lets
            // a device run see which shape it actually exercised.
            android.util.Log.i(
                "AndChamberInitialBlank",
                "pageReadyAtInjection=${harness.state.pageReady}",
            )
            instrumentation.runOnMainSync {
                view!!.webViewClient.onPageFinished(view, "about:blank")
            }

            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            assertFalse("the initial blank callback must not require reopen", harness.state.requiresReopen)
            assertTrue("the real target must still receive default control", harness.state.agentOn)
            val snapshot = harness.snapshot("blank-after-ready")
            assertTrue("snapshot after the target document: ${snapshot.errorCode}", snapshot.ok)
        } finally {
            harness.dispose()
        }
    }
}
