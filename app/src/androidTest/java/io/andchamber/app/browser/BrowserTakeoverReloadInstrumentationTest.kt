package io.andchamber.app.browser

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.andchamber.app.ui.browser.BrowserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The menu Reload after an explicit takeover, on the real production session and WebView client
 * (in-memory fixture, no network). From the commit on the page is the person's: their own
 * navigation before ready is not blocked. The reload's one controlled GET keeps its ticket and
 * witness (ready without requiresReopen), loads once, and no step grants the agent.
 */
@RunWith(AndroidJUnit4::class)
class BrowserTakeoverReloadInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun takeoverReloadBecomesReadyOnItsOwnTicketWithoutGrant() {
        val harness = BrowserAuthorityHarness(instrumentation, "takeover-reload-ready")
        try {
            assertTrue(harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-a").ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            harness.takeover()
            assertTrue(harness.state.explicitTakeover)

            val reloaded = runBlocking { harness.presenter.reloadManual() }
            assertTrue("reload failed: ${reloaded.errorCode} ${reloaded.message}", reloaded.ok)
            val view = harness.currentWebView()
            assertNotNull(view)
            val session = session(view!!)
            assertTrue("manual from the commit on", mainValue { session.manualNavigation })
            val ticket = mainValue { session.ticket }

            harness.await("reloaded page ready") { harness.state.pageReady }
            assertFalse("the controlled witness was accepted normally", harness.state.requiresReopen)
            assertEquals("the first start continued the controlled ticket", ticket, mainValue { session.ticket })
            assertTrue(mainValue { session.manualNavigation })
            assertNull("ready after takeover never grants", harness.presenter.registry.currentGrant())
            assertFalse(harness.state.agentOn)
            assertTrue(harness.state.explicitTakeover)
            // One controlled GET in this view: the fixture's per-view counter stays at one.
            assertEquals("\"1\"", harness.eval(view, "sessionStorage.getItem('loads')"))

            // A late duplicate ready callback and a reconnect still do not grant.
            instrumentation.runOnMainSync { view.webViewClient.onPageFinished(view, view.url) }
            SystemClock.sleep(300L)
            assertNull(harness.presenter.registry.currentGrant())
            assertFalse(harness.snapshot("after-reload").ok)
        } finally {
            harness.dispose()
        }
    }

    @Test
    fun takeoverReloadAdmitsThePersonsNavigationBeforeReady() {
        val harness = BrowserAuthorityHarness(instrumentation, "takeover-reload-early")
        try {
            assertTrue(harness.openDefault(OpenChamberProviderFixtures.ORIGIN, "open-a").ok)
            harness.awaitPageReady()
            harness.awaitDefaultGrant()
            harness.takeover()

            harness.holdReadyCallbacks()
            val reloaded = runBlocking { harness.presenter.reloadManual() }
            assertTrue(reloaded.ok)
            val view = harness.currentWebView()!!
            val session = session(view)
            assertFalse("ready is held", harness.state.pageReady)
            assertTrue("manual before ready", mainValue { session.manualNavigation })

            // The person's own top-level navigation (a link/location change) before ready.
            harness.await("reload document started") {
                harness.eval(view, "document.getElementById('marker') ? 'yes' : 'no'") == "\"yes\""
            }
            val startTicket = mainValue { session.ticket }
            harness.eval(view, "location.assign('${OpenChamberProviderFixtures.ORIGIN2}')")
            harness.await("the person's navigation was not blocked") {
                harness.eval(view, "document.getElementById('marker') ? document.getElementById('marker').textContent : ''") == "\"second\""
            }
            assertTrue("the person's document got its own ticket", mainValue { session.ticket } > startTicket)
            assertFalse(mainValue { session.unknownNavigation })
            assertNull(harness.presenter.registry.currentGrant())

            harness.releaseReadyCallbacks()
            SystemClock.sleep(300L)
            assertNull("no callback after the person's navigation grants", harness.presenter.registry.currentGrant())
            assertFalse(harness.state.agentOn)
            assertTrue(harness.state.explicitTakeover)
            assertTrue(mainValue { session.manualNavigation })
        } finally {
            harness.dispose()
        }
    }

    private fun session(view: WebView): BrowserSession =
        mainValue { view.tag as? BrowserSession } ?: error("the production page has no session tag")

    private fun <T> mainValue(block: () -> T): T {
        var value: Any? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
