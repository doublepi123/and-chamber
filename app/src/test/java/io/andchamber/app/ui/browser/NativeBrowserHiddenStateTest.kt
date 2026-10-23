package io.andchamber.app.ui.browser

import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.provider.PageField
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The person's explicit hide is presentation state with a persistent effect: the page stays
 * mounted, the grant is revoked, and nothing that merely publishes state or reports a page
 * callback may present the window again. A plain show/restore, an explicit open, or an agent
 * action may (the last two through allow and never after an explicit takeover). These tests use
 * the presenter's JVM fakes only; no WebView.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeBrowserHiddenStateTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `hide keeps the page, revokes the grant and restore does not grant again`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val tabId = presenter.state.value.selectedTabId
        assertNotNull(tabId)
        assertNotNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.shown)
        assertTrue(presenter.state.value.eligible)

        presenter.hide()

        assertFalse("a hidden window is not presented", presenter.state.value.shown)
        assertTrue(presenter.state.value.hidden)
        assertFalse("a hidden window is not admitted", presenter.state.value.eligible)
        assertTrue("the existing boundary keeps transport; hide only ends page admission", presenter.state.value.transportEligible)
        assertTrue("the connection itself is not cleared by hide", presenter.state.value.connected)
        assertNull("hide revokes the grant", presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
        assertFalse(presenter.state.value.agentOn)
        assertFalse("a hidden window is not foreground", presenter.foreground)
        assertEquals("the page is kept, not destroyed", false, page.destroyed)
        assertEquals("the page is not reloaded", 1, page.loads)
        assertEquals(1, presenter.registry.tabs().size)
        assertEquals(tabId, presenter.state.value.selectedTabId)

        assertFalse("allow refuses while hidden", presenter.allow())
        presenter.onHostPaused()
        val refused = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals("page tools cannot wake a background host", ProviderCodes.BROWSER_PAGE_UNAVAILABLE, refused.errorCode)
        presenter.onHostResumed()

        assertTrue("restore presents the kept page", presenter.show())
        assertTrue(presenter.state.value.shown)
        assertFalse(presenter.state.value.hidden)
        assertTrue(presenter.state.value.eligible)
        assertNull("restore never restores a grant", presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
        assertFalse(page.destroyed)
        assertEquals("restore does not reload", 1, page.loads)
        assertEquals(1, presenter.registry.tabs().size)

        // A fresh Allow after restore is a new grant, not the hidden page's old one.
        assertTrue(presenter.allow())
        assertNotNull(presenter.registry.currentGrant())

        // Hiding again and restoring again still never regrants by itself.
        presenter.hide()
        presenter.show()
        assertNull(presenter.registry.currentGrant())
        presenter.acceptProbeForTest(page, ReadyProbeIdentity("view", 1L, 4L))
        assertNull("a page callback after restore never regrants", presenter.registry.currentGrant())
    }

    @Test
    fun `a manual open from the address bar gets default control after ready`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)

        val opened = presenter.openUser("https://example.com/manual", "session-a")
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertNull("opening alone is still not a grant", presenter.registry.currentGrant())

        val page = pages.single()
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))

        assertNotNull("a ready manual open is controlled by default", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertEquals(ProviderMode.AGENT, presenter.state.value.mode)
        assertFalse("no Allow tap was needed", presenter.state.value.awaitingAllow)
    }

    @Test
    fun `a connection change before ready consumes default control`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openUser("https://example.com/manual", "session-a").ok)

        presenter.setConnection("c2", 5L)
        val page = pages.single()
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))

        assertNull("a replaced connection drops the intent", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `the default-control intent captures the post-load navigation ticket`() = runBlocking {
        val page = FakePage(ticketAfterLoad = 9L)
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertEquals("the fake fixes its ticket during loadOnce", 9L, page.ticket())
        assertNull("opening alone is not a grant", presenter.registry.currentGrant())

        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))

        assertNotNull("the intent names the ticket loadOnce produced", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
    }

    @Test
    fun `an agent wake waits for the remounted page before it grants`() = runBlocking {
        val page = FakePage()
        page.presentationVisible = false
        val presenter = presenter(awaitPageVisible = { candidate ->
            page.presentationVisible = true
            true
        }) { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.hide()
        val serial = presenter.state.value.presentationRequestSerial
        var visibleAtGrantProbe = false
        page.onPlainProbe = { visibleAtGrantProbe = page.presentationVisible }

        val result = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertTrue(result.errorCode ?: "ok", result.ok)
        assertTrue("the wake waited for the native page", visibleAtGrantProbe)
        assertTrue(presenter.state.value.shown)
        assertEquals(serial + 1, presenter.state.value.presentationRequestSerial)
        assertNotNull("allow ran only after the page was visible", presenter.registry.currentGrant())
        assertEquals(1, page.driverCalls)
    }

    @Test
    fun `an agent wake with a page that never becomes visible does not grant`() = runBlocking {
        val page = FakePage()
        page.presentationVisible = false
        val presenter = presenter(awaitPageVisible = { false }) { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.hide()
        val serial = presenter.state.value.presentationRequestSerial

        val result = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertEquals("no visible page means no default control", ProviderCodes.AUTHORIZATION_REQUIRED, result.errorCode)
        assertTrue("the window is still requested", presenter.state.value.shown)
        assertEquals(serial + 1, presenter.state.value.presentationRequestSerial)
        assertNull("allow is never asked for an unmounted page", presenter.registry.currentGrant())
        assertEquals("the action never reached the page", 0, page.driverCalls)
    }

    @Test
    fun `an agent action restores a merely hidden window once and re-verifies control`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.hide()
        val serial = presenter.state.value.presentationRequestSerial
        assertTrue(presenter.state.value.hidden)

        val first = presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }

        assertTrue(first.errorCode ?: "ok", first.ok)
        assertTrue("an agent action shows the hidden window", presenter.state.value.shown)
        assertFalse(presenter.state.value.hidden)
        assertEquals(serial + 1, presenter.state.value.presentationRequestSerial)
        assertNotNull("control is restored only through allow verification", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertEquals(1, page.driverCalls)
        val restored = presenter.registry.currentGrant()

        // Already shown: another action does not request the window again or re-grant.
        val second = presenter.execute(
            request("browser.snapshot", id = "r2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertTrue(second.errorCode ?: "ok", second.ok)
        assertEquals("a shown window is not requested twice", serial + 1, presenter.state.value.presentationRequestSerial)
        assertSame("an already shown window keeps its grant", restored, presenter.registry.currentGrant())
        assertEquals(2, page.driverCalls)
    }

    @Test
    fun `an explicit takeover is not overridden by an agent action`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.explicitTakeover)
        val serial = presenter.state.value.presentationRequestSerial

        // Shown but taken over: an action is unauthorized and grants nothing.
        val refused = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, refused.errorCode)
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertEquals(serial, presenter.state.value.presentationRequestSerial)

        // Hidden after takeover: an action does not bring the window back or take control.
        presenter.hide()
        assertTrue(presenter.state.value.hidden)
        val hiddenRefused = presenter.execute(
            request("browser.snapshot", id = "r2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.BROWSER_PAGE_UNAVAILABLE, hiddenRefused.errorCode)
        assertTrue("takeover protection keeps the window hidden", presenter.state.value.hidden)
        assertFalse(presenter.state.value.shown)
        assertEquals(serial, presenter.state.value.presentationRequestSerial)
        assertNull(presenter.registry.currentGrant())
        assertEquals("no action reached the page", 0, page.driverCalls)

        // A plain user restore is presentation only; it still grants nothing.
        assertTrue(presenter.show())
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertTrue("restoring the window does not clear the takeover", presenter.state.value.explicitTakeover)
    }

    @Test
    fun `resumeAgent clears takeover and a takeover during its probe blocks the late grant`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.takeover()
        assertTrue(presenter.state.value.explicitTakeover)

        assertTrue("the explicit user entry clears the latch and grants", presenter.resumeAgent())
        assertNotNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.explicitTakeover)
        assertTrue(presenter.state.value.agentOn)

        // A new takeover while resume's allow() is probing must win; the late grant is refused.
        presenter.takeover()
        assertTrue(presenter.state.value.explicitTakeover)
        page.onPlainProbe = { presenter.takeover() }
        assertFalse("a mid-probe takeover blocks resume", presenter.resumeAgent())
        assertNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.explicitTakeover)
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `a blocked ready intent grants after unblock without a tap`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.setPresentationBlocked(true)
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))
        assertNull("a blocked ready probe does not grant", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)

        presenter.setPresentationBlocked(false)

        assertNotNull("unblock completes the pending default grant", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertFalse("no Allow tap or new open was needed", presenter.state.value.awaitingAllow)
    }

    @Test
    fun `a takeover during the ready auto-grant stops the late grant`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        // Takeover lands while allow() is re-probing the ready page for the default grant.
        page.onPlainProbe = { presenter.takeover() }

        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))

        assertNull("a revoke cannot be overtaken by the late auto-grant", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.explicitTakeover)
        assertFalse(presenter.state.value.agentOn)
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
    }

    @Test
    fun `a pending hidden wake retries on the next action without a second presentation request`() = runBlocking {
        val page = FakePage()
        page.presentationVisible = false
        var waits = 0
        val presenter = presenter(awaitPageVisible = { candidate ->
            waits += 1
            page.presentationVisible = waits >= 2
            page.presentationVisible
        }) { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.hide()
        val serial = presenter.state.value.presentationRequestSerial

        val first = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, first.errorCode)
        assertEquals(serial + 1, presenter.state.value.presentationRequestSerial)
        assertNull("an unmounted page never grants", presenter.registry.currentGrant())
        assertEquals("the first action never reached the page", 0, page.driverCalls)

        val second = presenter.execute(
            request("browser.snapshot", id = "r2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertTrue("the next action retries the pending wake", second.ok)
        assertNotNull(presenter.registry.currentGrant())
        assertEquals("the retry does not request the window again", serial + 1, presenter.state.value.presentationRequestSerial)
        assertEquals(1, page.driverCalls)
    }

    @Test
    fun `a pending wake is never applied to a new connection`() = runBlocking {
        val page = FakePage()
        page.presentationVisible = false
        val presenter = presenter(awaitPageVisible = { false }) { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.hide()

        val first = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, first.errorCode)
        assertNull(presenter.registry.currentGrant())

        // The connection changes after the failed wake. Its old identity must not authorize allow
        // for the new connection.
        presenter.setConnection("c2", 5L)
        val second = presenter.execute(
            request("browser.snapshot", id = "r2", connection = "c2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, second.errorCode)
        assertNull("a new connection never inherits the old wake", presenter.registry.currentGrant())
        assertEquals("no page action on the new connection", 0, page.driverCalls)
    }

    @Test
    fun `a paused host never auto-shows and the hide survives resume`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.hide()
        val serial = presenter.state.value.presentationRequestSerial
        presenter.onHostPaused()

        val refused = presenter.execute(
            request("browser.snapshot"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.BROWSER_PAGE_UNAVAILABLE, refused.errorCode)
        assertFalse(presenter.state.value.shown)
        assertTrue(presenter.state.value.hidden)
        assertEquals(serial, presenter.state.value.presentationRequestSerial)

        // Foreground again: the next agent action is the one that may restore the window.
        presenter.onHostResumed()
        assertTrue(presenter.execute(
            request("browser.snapshot", id = "r2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }.ok)
        assertTrue(presenter.state.value.shown)
        assertFalse(presenter.state.value.hidden)
        assertEquals(serial + 1, presenter.state.value.presentationRequestSerial)
    }

    @Test
    fun `an explicit agent open shows a hidden window but does not reclaim a takeover`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        presenter.hide()
        val serial = presenter.state.value.presentationRequestSerial

        val opened = presenter.execute(
            openRequest(id = "open-2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertTrue("an explicit open shows the window", presenter.state.value.shown)
        assertFalse(presenter.state.value.hidden)
        assertEquals(serial + 1, presenter.state.value.presentationRequestSerial)
        assertEquals(2, pages.size)
        assertTrue("an agent open cannot clear the takeover latch", presenter.state.value.explicitTakeover)
        assertNull("an open is not a grant until its page is ready", presenter.registry.currentGrant())

        // Its ready probe cannot reclaim control either.
        presenter.acceptProbeForTest(pages[1], ReadyProbeIdentity(pages[1].viewId(), pages[1].ticket(), 4L))
        assertNull("a ready agent open never reclaims an explicit takeover", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.explicitTakeover)

        // The person's explicit resume does clear the latch and goes through allow.
        assertTrue(presenter.resumeAgent())
        assertNotNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.explicitTakeover)
        assertTrue(presenter.state.value.agentOn)

        // Once the person resumed, a later hide may be restored by an agent action.
        presenter.hide()
        val afterOpen = presenter.state.value.presentationRequestSerial
        assertTrue(presenter.execute(
            request("browser.snapshot", id = "r2"),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }.ok)
        assertTrue(presenter.state.value.shown)
        assertEquals(afterOpen + 1, presenter.state.value.presentationRequestSerial)
        assertNotNull("the resumed page is ready, so allow restores control", presenter.registry.currentGrant())

        // Opening while already shown does not request the floating-window mode again.
        val shownSerial = presenter.state.value.presentationRequestSerial
        assertTrue(presenter.openClaimed(openRequest(id = "open-3"), 1_000L).ok)
        assertEquals(shownSerial, presenter.state.value.presentationRequestSerial)
    }

    @Test
    fun `background is the compatibility name for hide`() = runBlocking {
        val presenter = presenter { FakePage() }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.background()
        assertTrue(presenter.state.value.hidden)
        assertFalse(presenter.state.value.shown)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `publishes, pause resume and a probe do not show a hidden window again`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.hide()

        // Every one of these publishes state for the same page, or reports a host signal.
        presenter.setConnection("c1", 5L)
        assertFalse(presenter.state.value.shown)
        presenter.attachHostForTest("local", 1L)
        assertFalse(presenter.state.value.shown)
        presenter.setPresentationBlocked(true)
        presenter.setPresentationBlocked(false)
        assertFalse(presenter.state.value.shown)
        presenter.onHostPaused()
        assertFalse(presenter.state.value.shown)
        presenter.onHostResumed()
        assertFalse("ending a pause does not un-hide the window", presenter.state.value.shown)
        assertTrue(presenter.state.value.hidden)

        // A ready probe is a page callback, not the person's restore.
        presenter.acceptProbeForTest(page, ReadyProbeIdentity("view", 1L, 4L))
        assertFalse("a probe never shows a hidden window", presenter.state.value.shown)
        assertTrue(presenter.state.value.hidden)

        assertTrue(presenter.show())
        assertTrue(presenter.state.value.shown)
    }

    @Test
    fun `an explicit manual open while hidden shows the page again`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.hide()
        assertFalse(presenter.state.value.shown)
        val serialBefore = presenter.state.value.presentationRequestSerial

        val opened = presenter.openUser("https://example.com/next", "session-a")

        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertTrue("an explicit open clears hide", presenter.state.value.shown)
        assertFalse(presenter.state.value.hidden)
        assertEquals("an explicit manual open requests the floating window", serialBefore + 1, presenter.state.value.presentationRequestSerial)
        assertEquals(2, pages.size)
        assertEquals(1, pages[1].loads)
        assertTrue("the replaced page is destroyed", pages[0].destroyed)
        assertEquals("an explicit navigation reuses the tab", 1, presenter.registry.tabs().size)
        assertNull("an open is not a grant", presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
    }

    @Test
    fun `a refused manual URL neither changes the page nor shows a hidden window`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.hide()

        val refused = presenter.openUser("http://example.com/plain", "session-a")

        assertEquals(ProviderCodes.INVALID_REQUEST, refused.errorCode)
        assertEquals("url refused", refused.message)
        assertTrue("a refused URL does not reveal the window", presenter.state.value.hidden)
        assertFalse(presenter.state.value.shown)
        assertEquals(1, page.loads)
        assertEquals(1, presenter.registry.tabs().size)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `manual navigation takes over and rejects a non allow-listed URL first`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertNotNull(presenter.registry.currentGrant())

        val refused = presenter.openUser("file:///etc/hosts", "session-a")
        assertEquals(ProviderCodes.INVALID_REQUEST, refused.errorCode)
        assertEquals("url refused", refused.message)
        assertNotNull("a refused URL runs before takeover", presenter.registry.currentGrant())
        assertEquals(1, pages[0].loads)
        assertEquals(1, presenter.registry.tabs().size)

        val tabId = presenter.registry.tabs().single().tabId
        val opened = presenter.navigateManual("https://example.org/accepted")
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertNull("an accepted manual navigation takes over", presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
        assertEquals("the new page is created", 2, pages.size)
        assertEquals(1, pages[1].loads)
        assertTrue("the replaced page is destroyed", pages[0].destroyed)
        assertEquals("navigation reuses the tab instead of growing the list", 1, presenter.registry.tabs().size)
        assertEquals(tabId, presenter.state.value.selectedTabId)
    }

    @Test
    fun `reloadManual reloads the shown address in the same tab`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val tabId = presenter.registry.tabs().single().tabId

        val reloaded = presenter.reloadManual()

        assertTrue(reloaded.errorCode ?: "ok", reloaded.ok)
        assertEquals(2, pages.size)
        assertEquals(1, pages[1].loads)
        assertTrue("the old page is replaced", pages[0].destroyed)
        assertEquals("reload reuses the tab", 1, presenter.registry.tabs().size)
        assertEquals(tabId, presenter.state.value.selectedTabId)
        assertNull("reload takes over", presenter.registry.currentGrant())
    }

    @Test
    fun `restoring a hidden window does not materialize a page that was never created`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        // Another owner's page is visible; the second open parks a tab without a WebView.
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L).ok)
        val before = pages.size
        val selectedBefore = presenter.state.value.selectedTabId
        presenter.hide()

        presenter.show()

        assertEquals("show never creates a page", before, pages.size)
        assertTrue(presenter.state.value.shown)
        assertEquals(selectedBefore, presenter.state.value.selectedTabId)
    }

    @Test
    fun `a console session change before ready consumes default control`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        presenter.setConsoleSession("session-a")
        assertTrue(presenter.openUser("https://example.com/manual", "session-a").ok)

        presenter.setConsoleSession("session-b")
        val page = pages.single()
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))

        assertNull("a different console session drops the intent", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
    }

    private fun presenter(
        uniqueTabs: Boolean = false,
        awaitPageVisible: suspend (ControlledBrowserPage) -> Boolean = { it.nativePresentationVisible() },
        pageFactory: (android.content.Context?) -> FakePage,
    ): NativeBrowserPresenter {
        var ids = 0
        return NativeBrowserPresenter(
            registry = ProviderRegistry { if (uniqueTabs) "tab-${++ids}" else "tab-real" },
            scope = CoroutineScope(Dispatchers.Main),
            sessionFactory = pageFactory,
            main = Dispatchers.Main,
            clock = { 0L },
            awaitPageVisible = awaitPageVisible,
        ).also { it.attachHostForTest("local", 1L) }
    }

    private fun openRequest(
        session: String = "session-a",
        id: String = "open-1",
    ) = ProviderRequest(
        serverEpoch = "e1",
        connectionId = "c1",
        requestId = id,
        action = "browser.open",
        parameters = mapOf("url" to "https://example.com/login"),
        directory = "/proj",
        sessionId = session,
        timeoutMs = 1_000L,
    )

    private fun request(
        action: String,
        id: String = "r1",
        session: String = "session-a",
        connection: String = "c1",
    ) = ProviderRequest("e1", connection, id, action, emptyMap(), "/proj", session, 1_000L)

    private class FakePage(
        private val ticketAfterLoad: Long? = null,
    ) : ControlledBrowserPage {
        override var tabId: String? = null
        override val view: View? = null
        var presentationVisible = true
        override fun nativePresentationVisible(): Boolean = presentationVisible
        override val handle: Int = System.identityHashCode(this)
        override val width: Int = 320
        override val height: Int = 240
        override val pageError: String? = null
        override val ready: Boolean = true
        override var manualNavigation: Boolean = false
        override var onProbe: ((String, Long) -> Unit)? = null
        override var onReadyProbe: ((ReadyProbeIdentity) -> Unit)? = null
        override var onFatal: (() -> Unit)? = null
        override var onUnknown: (() -> Unit)? = null
        override var onManualDocument: (() -> Unit)? = null
        override var onSubmitNavigation: ((BrowserSubmitNavigation) -> Unit)? = null
        override var onGeometry: ((GeometrySample) -> Unit)? = null
        override var onGeometryRequested: (() -> Unit)? = null
        var loads = 0
        var destroyed = false
        var driverCalls = 0

        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver =
            BrowserDriver {
                driverCalls += 1
                BrowserReply(
                    ok = true,
                    result = mapOf(
                        BrowserDriverLogic.RESULT_SNAPSHOT to
                            """{"url":"https://example.com","title":"page","text":"","elements":[]}""",
                    ),
                )
            }

        private var navigationTicket = 1L
        override suspend fun loadOnce(url: String): Boolean {
            loads += 1
            ticketAfterLoad?.let { navigationTicket = it }
            return true
        }

        /** Test hook fired by allow()'s plain probe, so a revoke can be injected mid-verification. */
        var onPlainProbe: (() -> Unit)? = null
        override suspend fun probe(): ProbeAcceptance {
            onPlainProbe?.let { hook ->
                onPlainProbe = null
                hook()
            }
            return ProbeAcceptance.Accept("witness-1")
        }
        override fun acceptReady(ticket: Long, documentId: String): Boolean = true
        override fun matchesReady(identity: ReadyProbeIdentity): Boolean = identity.ticket == navigationTicket
        override fun owns(viewId: String, ticket: Long): Boolean = true
        override fun markUnknown() = Unit
        override fun clearAcceptedWitness() = Unit
        override fun syncWitness(documentId: String) = Unit
        override suspend fun currentFields(): Map<String, PageField>? = null
        override fun acceptedDocument(): String? = "doc"
        override fun viewId(): String = "view"
        override fun ticket(): Long = navigationTicket
        override fun refEpoch(): Long = 4L
        override fun destroy() {
            destroyed = true
        }
    }
}
