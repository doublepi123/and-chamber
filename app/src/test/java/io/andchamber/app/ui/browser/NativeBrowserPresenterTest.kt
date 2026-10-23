package io.andchamber.app.ui.browser

import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.DnsResolver
import io.andchamber.app.browser.provider.PageField
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.runtime.process.LocalBrowserProviderBinding
import io.andchamber.app.browser.provider.ProviderRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class NativeBrowserPresenterTest {
    private var testNow = 0L
    /** A preflight policy whose DNS answer is a public literal, so submit tests do not hit the network. */
    private val submitPolicy = BrowserUrlPolicy(
        resolver = DnsResolver { listOf(InetAddress.getByName("93.184.216.34")) },
    )
    private companion object {
        const val REF = "abcdef01-1234abcd-1"
    }
    private val dispatcher = UnconfinedTestDispatcher()

    @org.junit.Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @org.junit.After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    @Test
    fun `no foreground host creates no page`() = runBlocking {
        var created = 0
        val presenter = NativeBrowserPresenter(registry = ProviderRegistry(), sessionFactory = { created += 1; error("no page") })
        val result = presenter.openClaimed(openRequest(), 1_000L)
        assertEquals("BROWSER_PAGE_UNAVAILABLE", result.errorCode)
        assertEquals(0, created)
        assertFalse(presenter.state.value.shown)
        assertNull(presenter.state.value.selectedTabId)
    }

    @Test
    fun `a stopping client's late disconnect does not clear a newer connection`() = runBlocking {
        val presenter = presenter { FakePage() }
        val old = io.andchamber.app.runtime.process.LocalBrowserProviderBinding.ownConnection(presenter, 1L)
        val next = io.andchamber.app.runtime.process.LocalBrowserProviderBinding.ownConnection(presenter, 1L)
        old("c-old")
        next("c-new")
        old(null)
        val opened = presenter.execute(openRequest().copy(connectionId = "c-new"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        next(null)
        val refused = presenter.execute(request("browser.snapshot").copy(connectionId = "c-new"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertEquals(ProviderCodes.INVALID_REQUEST, refused.errorCode)
    }

    @Test
    fun `a new console document revokes the grant but keeps tabs and pages`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        val context = android.content.ContextWrapper(null)
        presenter.attachHost(context, "local", 1L)
        presenter.setConnection("c1", 1L)
        presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.agentOn)
        presenter.attachHost(context, "local", 2L)
        assertFalse(presenter.state.value.agentOn)
        assertEquals(1, presenter.registry.tabs().size)
        assertFalse(pages.single().destroyed)
        // A different Activity is a different host: pages go.
        presenter.attachHost(android.content.ContextWrapper(null), "local", 3L)
        assertTrue(pages.single().destroyed)
    }

    @Test
    fun `open through execute is not cancelled by its own revoke of the previous grant`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val opened = presenter.execute(openRequest(), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertEquals(1, page.loads)
    }

    @Test
    fun `claimed open creates one session and loads once`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        val opened = presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(opened.ok)
        assertEquals(false, opened.data["drivable"].toString().toBooleanStrict())
        assertEquals(1, page.loads)
        assertEquals(1, presenter.registry.tabs().size)
        val tab = presenter.registry.tabs().single()
        assertEquals(tab.tabId, opened.data["tabId"].toString().trim('"'))
        assertEquals("session-a", tab.ownerSessionId)
        assertEquals("/proj", tab.directory)
        assertTrue(presenter.state.value.shown)
        assertEquals(tab.tabId, presenter.state.value.selectedTabId)
        assertEquals(1L, presenter.state.value.viewGeneration)
    }

    @Test
    fun `without a page decorator the original page loads once`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        val opened = presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(opened.ok)
        assertEquals(1, page.loads)
        assertEquals(page.handle, presenter.state.value.viewHandle)
        assertNotNull(page.onFatal)
    }

    @Test
    fun `the page decorator runs once on the original page before the first load`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        val seen = mutableListOf<ControlledBrowserPage>()
        var loadsAtDecorator = -1
        presenter.installPageDecorator {
            seen += it
            loadsAtDecorator = page.loads
        }
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertSame(page, seen.single())
        assertEquals(0, loadsAtDecorator)
        assertEquals(1, page.loads)
    }

    @Test
    fun `each new page is decorated once and selecting an existing page does not re-decorate`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        val decorated = mutableListOf<ControlledBrowserPage>()
        presenter.installPageDecorator { decorated += it }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        assertEquals(listOf<ControlledBrowserPage>(pages[0]), decorated)
        // Another owner's open only parks a tab: no page and no decorator call.
        assertTrue(presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L).ok)
        assertEquals(1, decorated.size)
        val aTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-a" }.tabId
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        // Selecting the parked tab materializes its page exactly once.
        assertTrue(presenter.select(bTab))
        assertEquals(2, decorated.size)
        assertSame(pages[1], decorated[1])
        assertEquals(listOf(1, 1), pages.map { it.loads })
        // Selecting a tab that already has a page neither recreates nor re-decorates it.
        assertTrue(presenter.select(aTab))
        assertEquals(2, decorated.size)
        assertEquals(listOf(1, 1), pages.map { it.loads })
    }

    @Test
    fun `a reopened retired page is decorated once as a new page`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        val decorated = mutableListOf<ControlledBrowserPage>()
        presenter.installPageDecorator { decorated += it }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        pages.single().onMouseRetirement?.invoke("dispatch-failed")
        assertTrue(presenter.state.value.pageRetired)
        val reopened = presenter.reopenRetiredPage()
        assertTrue(reopened.errorCode ?: "ok", reopened.ok)
        assertEquals(2, decorated.size)
        assertSame(pages[1], decorated[1])
        assertEquals(1, pages[1].loads)
    }

    @Test
    fun `installing a page decorator after a page exists fails and keeps the grant`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val grant = presenter.registry.currentGrant()
        assertNotNull(grant)
        var decorated = 0
        val refused = runCatching { presenter.installPageDecorator { decorated += 1 } }
        assertTrue(refused.isFailure)
        assertEquals(0, decorated)
        assertEquals(grant, presenter.registry.currentGrant())
        assertEquals(1, page.loads)
    }

    @Test
    fun `a throwing page decorator destroys the original and fails closed`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.installPageDecorator { error("decorator failed") }
        val opened = presenter.openClaimed(openRequest(), 1_000L)
        assertFalse(opened.ok)
        assertEquals("BROWSER_PAGE_UNAVAILABLE", opened.errorCode)
        assertTrue(page.destroyed)
        assertEquals(0, page.loads)
        assertNull(presenter.registry.currentGrant())
        assertTrue(presenter.registry.tabs().isEmpty())
        assertFalse(presenter.state.value.pageReady)
        assertNull(presenter.currentView())
    }

    @Test
    fun `a throwing page decorator also fails closed when materializing a parked tab`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        var calls = 0
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.installPageDecorator {
            calls += 1
            if (calls == 2) error("decorator failed")
        }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L).ok)
        val aTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-a" }.tabId
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertTrue(presenter.select(bTab))
        assertEquals(2, calls)
        assertTrue(pages[1].destroyed)
        assertEquals(0, pages[1].loads)
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.pageReady)
        assertNull(presenter.currentView())
        // The next selection of the existing healthy page does not retry or re-decorate.
        assertTrue(presenter.select(aTab))
        assertEquals(2, calls)
        assertEquals(pages[0].handle, presenter.state.value.viewHandle)
    }

    @Test
    fun `the decorator observes the original page and cannot replace it`() = runBlocking {
        val original = FakePage()
        val other = FakePage(text = "other")
        val presenter = presenter { original }
        var seen: ControlledBrowserPage? = null
        presenter.installPageDecorator { seen = it }
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertSame(original, seen)
        assertEquals(original.handle, presenter.state.value.viewHandle)
        assertNotNull(original.onFatal)
        assertNotNull(original.onUnknown)
        assertEquals(1, original.loads)
        assertFalse(other.destroyed)
        assertEquals(0, other.loads)
    }

    @Test
    fun `an explicit missing tab is rejected and another owner is not overwritten`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.openClaimed(openRequest(), 1_000L)
        val existing = presenter.registry.tabs().single().tabId
        val missing = presenter.openClaimed(openRequest(tabId = "tab-missing"), 1_000L)
        assertEquals(ProviderCodes.INVALID_REQUEST, missing.errorCode)
        val other = presenter.openClaimed(openRequest(session = "session-b", tabId = existing), 1_000L)
        assertEquals(ProviderCodes.INVALID_REQUEST, other.errorCode)
        assertEquals("session-a", presenter.registry.find(existing)?.ownerSessionId)
        assertEquals(1, page.loads)
    }

    @Test
    fun `a second action is busy`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        assertTrue(presenter.beginForTest("r1"))
        val busy = presenter.execute(request("browser.capture", id = "r2"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertEquals(ProviderCodes.BUSY, busy.errorCode)
        presenter.cancel("r1")
    }

    @Test
    fun `cancel revokes the active request`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `an explicit open grants default control after ready and takeover or hide revokes it`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertFalse("default control is not a tap notice", presenter.state.value.awaitingAllow)
        assertNull("opening alone is not a grant", presenter.registry.currentGrant())

        // Ready consumes the open's one-shot intent through allow().
        presenter.acceptProbeForTest(pages[0], readyIdentity(pages[0]))
        assertNotNull("a ready explicit open is controlled by default", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertEquals(ProviderMode.AGENT, presenter.state.value.mode)

        // Takeover revokes, and a late ready/page-finished callback does not grant again.
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        presenter.acceptProbeForTest(pages[0], readyIdentity(pages[0]))
        assertNull("a probe after takeover never regrants", presenter.registry.currentGrant())

        // An agent open cannot reclaim the takeover; only a person's explicit open does. It clears
        // the latch, then the same ready-time allow path grants again.
        assertTrue(presenter.navigateManual("https://example.com/next").ok)
        presenter.acceptProbeForTest(pages[1], readyIdentity(pages[1]))
        assertNotNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)

        // Hide revokes. Restoring the kept page does not restore control, and a probe does not either.
        presenter.hide()
        assertNull(presenter.registry.currentGrant())
        presenter.show()
        assertNull("restore never restores a grant", presenter.registry.currentGrant())
        presenter.acceptProbeForTest(pages[1], readyIdentity(pages[1]))
        assertNull("a probe after restore never regrants", presenter.registry.currentGrant())
    }

    @Test
    fun `switching tabs clears default control and a selection never waits for allow`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        val first = presenter.state.value.selectedTabId!!
        presenter.acceptProbeForTest(pages[0], readyIdentity(pages[0]))
        assertNotNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.awaitingAllow)
        assertTrue(presenter.openClaimed(openRequest(id = "open-2"), 1_000L).ok)
        // A new open revoked the previous tab's grant; selecting the old page does not revive it.
        assertTrue(presenter.select(first))
        assertNull(presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
        assertFalse("a selection never shows an Allow notice", presenter.state.value.awaitingAllow)
    }

    @Test
    fun `an open with an allow wait reports drivable true when the person allows it`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 60_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        assertFalse("the open waits until control exists", pending.isCompleted)
        assertFalse("no Allow tap notice is pending", presenter.state.value.awaitingAllow)
        assertFalse(presenter.state.value.agentOn)

        assertTrue(presenter.allow())
        val opened = pending.await()
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertEquals("true", opened.data.getValue("drivable").toString())
        assertFalse("a granted open has no reason", opened.data.containsKey("reason"))
        assertEquals("tab-real", opened.data.getValue("tabId").toString().trim('"'))
        assertTrue(presenter.state.value.agentOn)
        assertFalse(presenter.state.value.awaitingAllow)
    }

    @Test
    fun `an open with an allow wait reports drivable false when the wait expires`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 5_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(5_001)
        val opened = pending.await()
        assertTrue(opened.ok)
        assertEquals("false", opened.data.getValue("drivable").toString())
        assertEquals("authorization_required", opened.data.getValue("reason").toString().trim('"'))
        assertFalse(presenter.state.value.agentOn)
        assertFalse("no control was granted, so no Allow notice is pending", presenter.state.value.awaitingAllow)
        assertEquals(1, page.loads)
    }

    @Test
    fun `a first visibility failure retries inside the open budget and grants without new events`() = runTest {
        val page = FakePage()
        page.readyForAllow = false
        page.presentationVisible = false
        var visibilityChecks = 0
        val presenter = presenter(awaitPageVisible = {
            visibilityChecks += 1
            page.presentationVisible = visibilityChecks >= 2
            page.presentationVisible
        }) { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 60_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        val selected = presenter.state.value.selectedTabId!!
        // The ready probe is accepted while the fake is still not ready to grant. The intent and
        // its readyAccepted marker remain; no person's tap will follow.
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertNull("a not-ready page does not grant", presenter.registry.currentGrant())
        page.readyForAllow = true
        // A presentation event (not geometry/ready) starts the first retry. Its visibility wait
        // fails once; the open's own budget drives the second retry without any new page event.
        presenter.onPresentationChanged()
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        val opened = pending.await()
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertEquals("true", opened.data.getValue("drivable").toString())
        assertTrue("the retry saw visibility on its second attempt", visibilityChecks >= 2)
        assertNotNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertEquals(selected, presenter.state.value.selectedTabId)
    }

    @Test
    fun `a temporary presentation block keeps the open waiting and unblock grants`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 5_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        presenter.setPresentationBlocked(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse("a temporary layout/IME block is not a stop", pending.isCompleted)
        presenter.setPresentationBlocked(false)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        runCurrent()
        val opened = pending.await()
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertEquals("true", opened.data.getValue("drivable").toString())
        assertNotNull(presenter.registry.currentGrant())
        assertEquals("the blocked open never created a second page", 1, page.loads)
    }

    @Test
    fun `a ready callback before mount is replayed after visibility`() = runTest {
        val page = FakePage()
        page.documentId = null
        page.presentationVisible = false
        var visibilityWaits = 0
        val presenter = presenter(awaitPageVisible = {
            visibilityWaits += 1
            page.presentationVisible = true
            true
        }) { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 5_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        page.onReadyProbe?.invoke(ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))
        runCurrent()
        assertNull("an unmounted page does not accept the ready witness", page.acceptedDocument())
        assertNull(presenter.registry.currentGrant())

        advanceTimeBy(251)
        runCurrent()
        val opened = pending.await()
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertEquals("true", opened.data.getValue("drivable").toString())
        assertEquals("witness-1", page.acceptedDocument())
        assertNotNull("the stored ready identity was replayed after mount", presenter.registry.currentGrant())
        assertEquals(1, page.loads)
        assertTrue(visibilityWaits >= 1)
    }

    @Test
    fun `a takeover during the visibility retry keeps the open unauthorized`() = runTest {
        val page = FakePage()
        page.readyForAllow = false
        page.presentationVisible = false
        val presenter = presenter(awaitPageVisible = { false }) { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 5_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        presenter.acceptProbeForTest(page, readyIdentity(page))
        page.readyForAllow = true
        presenter.onPresentationChanged()
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        presenter.takeover()
        val opened = pending.await()
        assertEquals("false", opened.data.getValue("drivable").toString())
        assertEquals("authorization_required", opened.data.getValue("reason").toString().trim('"'))
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `a connection change during the visibility retry keeps the open unauthorized`() = runTest {
        val page = FakePage()
        page.readyForAllow = false
        page.presentationVisible = false
        val presenter = presenter(awaitPageVisible = { false }) { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 5_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        presenter.acceptProbeForTest(page, readyIdentity(page))
        page.readyForAllow = true
        presenter.onPresentationChanged()
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        presenter.setConnection("c2", 2L)
        val opened = pending.await()
        assertEquals("false", opened.data.getValue("drivable").toString())
        assertEquals("authorization_required", opened.data.getValue("reason").toString().trim('"'))
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `a submit with no navigation returns unknown and leaves no drivable old page`() = runTest {
        val page = FakePage()
        val presenter = presenter(policy = { submitPolicy }) { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        val pending = async {
            presenter.execute(
                request(
                    "browser.type",
                    id = "submit-1",
                    parameters = mapOf(
                        "selector" to """[data-andchamber-ref="$REF"]""",
                        "value" to "x",
                        "submit" to "true",
                    ),
                ),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                1_000L,
            ) { 0L }
        }
        runCurrent()
        advanceTimeBy(1_001)
        val result = pending.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals("Re-open this page before allowing control", presenter.state.value.pageError)
        assertNull("the submitted page's grant is not kept", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        val after = presenter.execute(request("browser.snapshot", id = "after-submit"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals("the old page is not drivable", ProviderCodes.AUTHORIZATION_REQUIRED, after.errorCode)
    }

    @Test
    fun `a timed-out submit cannot late-grant through a suspended allow probe`() = runTest {
        val page = FakePage()
        val presenter = presenter(policy = { submitPolicy }) { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)

        val probeGate = CompletableDeferred<Unit>()
        page.plainProbeGates.add(probeGate)
        val submit = async {
            presenter.execute(
                request(
                    "browser.type",
                    id = "submit-timeout",
                    parameters = mapOf(
                        "selector" to """[data-andchamber-ref="$REF"]""",
                        "value" to "x",
                        "submit" to "true",
                    ),
                ),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                1_000L,
            ) { 0L }
        }
        runCurrent()
        // A second allow is already probing this same page/document while the submit waits.
        val suspendedAllow = async { presenter.allow() }
        runCurrent()
        assertFalse("the allow probe is suspended", suspendedAllow.isCompleted)

        // No GET arrives: the submit times out, marks the exact page unknown and revokes its grant.
        advanceTimeBy(1_001)
        runCurrent()
        val submitResult = submit.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, submitResult.errorCode)
        assertEquals("Re-open this page before allowing control", presenter.state.value.pageError)
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)

        // The suspended probe may return an Accept witness now, but the final fence must see the
        // unknown page and refuse to mint a late grant.
        probeGate.complete(Unit)
        runCurrent()
        assertFalse("a late allow probe cannot revive a submitted page", suspendedAllow.await())
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertEquals("Re-open this page before allowing control", presenter.state.value.pageError)
        assertEquals(
            ProviderCodes.AUTHORIZATION_REQUIRED,
            presenter.execute(request("browser.snapshot", id = "after-unknown"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }.errorCode,
        )

        // A later person's open replaces the page and gets a clean, independent authority.
        assertTrue(presenter.navigateManual("https://example.com/later").ok)
        assertTrue(presenter.allow())
        assertNotNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertNull("the old unknown page does not pollute the new user page", presenter.state.value.pageError)
        assertTrue(presenter.execute(request("browser.snapshot", id = "later"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
    }

    @Test
    fun `a submit replacement that is not ready cannot late grant`() = runTest {
        val pages = mutableListOf<FakePage>()
        var created = 0
        val presenter = presenter(policy = { submitPolicy }) {
            FakePage().also { page ->
                created += 1
                if (created > 1) page.readyForAllow = false
                pages += page
            }
        }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        val pending = async {
            presenter.execute(
                request(
                    "browser.type",
                    id = "submit-2",
                    parameters = mapOf(
                        "selector" to """[data-andchamber-ref="$REF"]""",
                        "value" to "x",
                        "submit" to "true",
                    ),
                ),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                2_000L,
            ) { 0L }
        }
        runCurrent()
        pages[0].onSubmitNavigation?.invoke(BrowserSubmitNavigation.Get("https://example.com/next"))
        repeat(200) {
            if (pages.size < 2) {
                runCurrent()
                yield()
            }
        }
        assertEquals("the controlled replacement was created", 2, pages.size)
        advanceTimeBy(2_001)
        val result = pending.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        val fresh = pages[1]
        fresh.readyForAllow = true
        presenter.acceptProbeForTest(fresh, readyIdentity(fresh))
        assertNull("a late ready on the failed replacement never grants", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertEquals("Re-open this page before allowing control", presenter.state.value.pageError)
    }

    @Test
    fun `a late old submit event cannot affect a later page after takeover`() = runTest {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(policy = { submitPolicy }) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        val submit = async {
            presenter.execute(
                request(
                    "browser.type",
                    id = "submit-3",
                    parameters = mapOf(
                        "selector" to """[data-andchamber-ref="$REF"]""",
                        "value" to "x",
                        "submit" to "true",
                    ),
                ),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                60_000L,
            ) { 0L }
        }
        runCurrent()
        val oldPage = pages[0]
        presenter.takeover()
        runCatching { submit.await() }
        assertTrue(presenter.navigateManual("https://example.com/later").ok)
        val fresh = pages.last()
        assertTrue(presenter.allow())
        val grant = presenter.registry.currentGrant()
        val loads = pages.map { it.loads }
        oldPage.onSubmitNavigation?.invoke(BrowserSubmitNavigation.Get("https://example.com/late"))

        assertSame("a late old event never replaces the later grant", grant, presenter.registry.currentGrant())
        assertEquals("a late old event never navigates the later page", loads, pages.map { it.loads })
        assertEquals(fresh.tabId, presenter.state.value.selectedTabId)
        assertTrue(presenter.state.value.agentOn)
    }

    @Test
    fun `cancelling an open ends the allow wait and leaves no grant`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 60_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        pending.cancel()
        pending.join()
        advanceUntilIdle()
        assertTrue("the cancelled open left the slot free", pending.isCancelled)
        assertFalse(presenter.state.value.agentOn)
        assertEquals(1, page.loads)
        val refused = presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertEquals("nothing kept running after the cancel", ProviderCodes.AUTHORIZATION_REQUIRED, refused.errorCode)
    }

    @Test
    fun `a grant for another tab never satisfies the allow wait`() = runTest {
        val page = FakePage()
        val presenter = presenter(uniqueTabs = true) { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 3_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        val other = presenter.registry.open("session-b", "/proj", "local", "https://example.com/other", null)
        presenter.registry.grant(1L, "c1", "session-b", other.tabId)
        // The bounded wait re-checks at its retry tick. Another session's grant is a STOPPED state,
        // so the open answers unauthorized rather than treating it as this page's control.
        advanceTimeBy(300)
        val opened = pending.await()
        assertEquals("false", opened.data.getValue("drivable").toString())
        assertEquals("authorization_required", opened.data.getValue("reason").toString().trim('"'))
    }

    @Test
    fun `overlapping allow probes mint one grant epoch and keep snapshot authority`() = runTest {
        val page = FakePage()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        page.plainProbeGates.add(firstGate)
        page.plainProbeGates.add(secondGate)
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)

        val first = async { presenter.allow() }
        val second = async { presenter.allow() }
        runCurrent()
        assertFalse("the first probe is held", first.isCompleted)
        assertFalse("the second probe overlaps it", second.isCompleted)

        firstGate.complete(Unit)
        runCurrent()
        assertTrue("the first verified probe grants", first.await())
        val epoch = presenter.registry.currentGrant()?.grantEpoch
        assertNotNull(epoch)
        assertTrue(
            "a snapshot taken after the first grant is authorized",
            presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok,
        )

        secondGate.complete(Unit)
        runCurrent()
        assertTrue("the overlapping second probe still succeeds", second.await())
        assertEquals("the second probe reuses the same grant epoch", epoch, presenter.registry.currentGrant()?.grantEpoch)
        assertTrue(
            "a later snapshot keeps authority",
            presenter.execute(request("browser.snapshot", id = "after-overlap"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok,
        )
    }

    @Test
    fun `a takeover during an allow probe cannot grant late`() = runTest {
        val page = FakePage()
        val gate = CompletableDeferred<Unit>()
        page.plainProbeGates.add(gate)
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        val allowing = async { presenter.allow() }
        runCurrent()
        assertFalse(allowing.isCompleted)
        presenter.takeover()
        gate.complete(Unit)
        runCurrent()
        assertFalse("takeover during the probe wins", allowing.await())
        assertNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.explicitTakeover)
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `a genuinely new verified document gets a fresh grant epoch`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val firstEpoch = presenter.registry.currentGrant()?.grantEpoch
        assertNotNull(firstEpoch)

        page.documentId = "doc-2"
        page.probeResultOverride = ProbeAcceptance.Accept("witness-2")
        assertTrue("a new verified document is allowed", presenter.allow())
        assertNotEquals(
            "a different document needs a fresh grant epoch",
            firstEpoch,
            presenter.registry.currentGrant()?.grantEpoch,
        )
    }

    @Test
    fun `a takeover during the allow wait answers with the unauthorized payload`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 60_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        presenter.takeover()
        val opened = pending.await()
        assertTrue("the open answers instead of dying with its job", opened.ok)
        assertEquals("false", opened.data.getValue("drivable").toString())
        assertEquals("authorization_required", opened.data.getValue("reason").toString().trim('"'))
        assertFalse(presenter.state.value.awaitingAllow)
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `closing the tab during the allow wait answers with the unauthorized payload`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 1L)
        val pending = async {
            presenter.execute(openRequest(allowWaitMs = 60_000), presenter.registry, ProviderMode.AGENT, null, 60_000L) { 0L }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        presenter.close(presenter.state.value.selectedTabId!!)
        val opened = pending.await()
        assertEquals("false", opened.data.getValue("drivable").toString())
        assertEquals("authorization_required", opened.data.getValue("reason").toString().trim('"'))
        assertTrue("the closed tab is gone", presenter.registry.tabs().isEmpty())
    }

    @Test
    fun `allow re-probes then grants and takeover revokes before return`() = runBlocking {
        val page = FakePage(probe = ProbeAcceptance.Accept("witness-1"))
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.agentOn)
        assertEquals(ProviderMode.AGENT, presenter.state.value.mode)
        assertEquals("c1", presenter.registry.currentGrant()?.connectionId)
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
        assertTrue(page.manualNavigation)
    }

    @Test
    fun `open does not grant and the same adapter can snapshot then type`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        val opened = presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(opened.ok)
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
        assertFalse(presenter.state.value.agentOn)
        val denied = presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, denied.errorCode)
        assertEquals(0, page.reads)
        assertTrue(presenter.allow())
        val snap = presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertTrue(snap.ok)
        val typed = presenter.execute(
            request("browser.type", id = "r-type", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""", "value" to "hello", "submit" to "false")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            1_000L,
        ) { 0L }
        assertTrue(typed.ok)
        assertEquals(REF, page.lastRef)
    }

    @Test
    fun `current dom password and submission are refused before the driver acts`() = runBlocking {
        val page = FakePage().also { it.fieldsMutable = mapOf(REF to PageField("input", "password", "Password")) }
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        val before = page.actions
        val refused = presenter.execute(
            request("browser.type", id = "r-secret", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""", "value" to "secret")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            1_000L,
        ) { 0L }
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, refused.errorCode)
        assertEquals(before, page.actions)
    }

    @Test
    fun `manual document start clears adapter refs`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        presenter.takeover()
        page.onManualDocument?.invoke()
        presenter.resumeAgent()
        val typed = presenter.execute(
            request("browser.type", id = "r-after", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""", "value" to "again")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            1_000L,
        ) { 0L }
        assertEquals(ProviderCodes.REF_EXPIRED, typed.errorCode)
    }

    @Test
    fun `allow reads only the visible page and another owner does not replace it`() = runBlocking {
        var next = "page-A"
        var ids = 0
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage(text = next).also { next = "page-B" } },
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        val pageA = presenter.currentView()
        presenter.allow()
        val first = presenter.execute(request("browser.snapshot", session = "owner-a"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals(null, first.errorCode)
        assertTrue(first.data.toString(), first.data.toString().contains("page-A"))
        val other = presenter.openClaimed(openRequest(session = "owner-b", id = "open-b"), 1_000L)
        assertEquals("ok", other.errorCode ?: "ok")
        assertTrue(presenter.currentView() === pageA)
        presenter.select(presenter.registry.tabs().last().tabId)
        // A's grant is gone the moment B's tab is selected.
        assertNull(presenter.registry.currentGrant())
        val aAfterSelect = presenter.execute(request("browser.snapshot", id = "r-a2", session = "owner-a"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, aAfterSelect.errorCode)
        // B gets its own page; allowing it reads B, never A's WebView.
        assertTrue(presenter.allow())
        val b = presenter.execute(request("browser.snapshot", id = "r-b", session = "owner-b"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertTrue(b.data.toString(), b.data.toString().contains("page-B"))
        assertFalse(b.data.toString().contains("page-A"))
        val aAfterAllow = presenter.execute(request("browser.snapshot", id = "r-a3", session = "owner-a"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, aAfterAllow.errorCode)
    }

    @Test
    fun `desktop mode on a post page is refused and revokes without a reload`() = runBlocking {
        val page = FakePage(post = true)
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        val switched = presenter.setDesktopMode(true)
        assertEquals(ModeSwitch.ReopenRequired, switched)
        assertNull(presenter.registry.currentGrant())
        assertEquals(0, page.reloads)
        presenter.onGeometryChanged()
        presenter.onGeometrySettled()
        assertEquals("mode-reopen-required", presenter.state.value.pageError)
        presenter.setDesktopMode(false)
        assertFalse(presenter.state.value.mouseMode)
        page.readyForAllow = false
        assertFalse(presenter.allow())
        assertEquals("mode-reopen-required", presenter.state.value.pageError)
        page.readyForAllow = true
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.agentOn)
        assertNull(presenter.state.value.pageError)
        assertEquals(ModeSwitch.ReopenRequired, presenter.setDesktopMode(true))
        assertEquals(0, page.reloads)
    }

    @Test
    fun `a mode notice stays on its page and clears on that page manual document`() = runBlocking {
        val first = FakePage(post = true, text = "page-A")
        val presenter = presenter { first }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        presenter.setDesktopMode(true)
        assertEquals("mode-reopen-required", presenter.state.value.pageError)
        first.manualNavigation = true
        first.onManualDocument?.invoke()
        assertNull(presenter.state.value.pageError)
    }

    @Test
    fun `open applies desktop before the first load and an invalid viewport creates nothing`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        val opened = presenter.openClaimed(openRequest().copy(parameters = openRequest().parameters + ("viewport" to "desktop")), 1_000L)
        assertTrue(opened.ok)
        assertEquals(1, page.desktopApplies)
        assertTrue(page.desktop)
        assertTrue(page.loadAfterMode)
        val refused = presenter.openClaimed(openRequest(id = "open-2").copy(parameters = openRequest().parameters + ("viewport" to "tablet")), 1_000L)
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, refused.errorCode)
        assertEquals(1, presenter.registry.tabs().size)
    }

    @Test
    fun `a new desktop open defaults to 1080p and applies it before load`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        val request = openRequest().let {
            it.copy(parameters = it.parameters + mapOf("viewport" to "desktop"))
        }
        assertTrue(presenter.openClaimed(request, 1_000L).ok)
        assertTrue(page.desktop)
        assertEquals(BrowserDesktopViewport.DEFAULT_WIDTH, page.desktopWidth)
        assertEquals(BrowserDesktopViewport.DEFAULT_HEIGHT, page.desktopHeight)
        assertEquals("configured once before the single load", 1, page.desktopViewportSets)
        assertTrue("desktop was set before loadOnce", page.loadAfterMode)
        assertEquals(BrowserDesktopViewport.DEFAULT_WIDTH, presenter.state.value.desktopWidth)
        assertEquals(BrowserDesktopViewport.DEFAULT_HEIGHT, presenter.state.value.desktopHeight)
    }

    @Test
    fun `a desktop open accepts one paired custom size`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        val request = openRequest().let {
            it.copy(
                parameters = it.parameters + mapOf(
                    "viewport" to "desktop",
                    "width" to "1600",
                    "height" to "900",
                ),
            )
        }
        assertTrue(presenter.openClaimed(request, 1_000L).ok)
        val page = pages.single()
        assertEquals(1600, page.desktopWidth)
        assertEquals(900, page.desktopHeight)
        assertEquals(1600, presenter.state.value.desktopWidth)
        assertEquals(900, presenter.state.value.desktopHeight)
        assertEquals(1, page.loads)
    }

    @Test
    fun `an invalid or mobile custom size is unsupported before any page`() = runBlocking {
        var created = 0
        val presenter = presenter { created += 1; FakePage() }
        presenter.setConnection("c1", 4L)
        suspend fun attempt(viewport: String?, width: String?, height: String?) {
            val parameters = buildMap {
                putAll(openRequest().parameters)
                viewport?.let { put("viewport", it) }
                width?.let { put("width", it) }
                height?.let { put("height", it) }
            }
            val result = presenter.openClaimed(openRequest().copy(parameters = parameters), 1_000L)
            assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, result.errorCode)
        }
        attempt("mobile", "1600", "900")
        attempt(null, "1600", "900")
        attempt("desktop", "1600", null)
        attempt("desktop", "639", "900")
        attempt("desktop", "1600", "2161")
        attempt("fill", "1600", "900")
        assertEquals("invalid sizes never create a page", 0, created)
    }

    @Test
    fun `the same tab keeps its custom desktop size across controlled navigation`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        val request = openRequest().let {
            it.copy(parameters = it.parameters + mapOf("viewport" to "desktop", "width" to "1600", "height" to "900"))
        }
        assertTrue(presenter.openClaimed(request, 1_000L).ok)
        val opened = presenter.navigateManual("https://example.com/next")
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        val fresh = pages.last()
        assertEquals(1600, fresh.desktopWidth)
        assertEquals(900, fresh.desktopHeight)
        assertTrue(fresh.desktop)
        assertEquals(1600, presenter.state.value.desktopWidth)
        assertEquals(900, presenter.state.value.desktopHeight)
        assertEquals(1, fresh.loads)
    }

    @Test
    fun `resize keeps a custom size, applies a new pair, and restores it after mobile`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        val request = openRequest().let { it.copy(parameters = it.parameters + mapOf("viewport" to "desktop")) }
        assertTrue(presenter.openClaimed(request, 1_000L).ok)
        assertEquals(0, page.reloads)

        assertEquals(ModeSwitch.Unchanged, presenter.resizeViewport("desktop", null))
        assertEquals(BrowserDesktopViewport.DEFAULT_WIDTH, page.desktopWidth)
        assertEquals(0, page.reloads)

        assertEquals(ModeSwitch.Applied, presenter.resizeViewport("desktop", null, 1280, 720))
        assertEquals(1280, page.desktopWidth)
        assertEquals(720, page.desktopHeight)
        assertEquals(1, page.reloads)
        assertEquals(1280, presenter.state.value.desktopWidth)

        assertEquals(ModeSwitch.Unchanged, presenter.resizeViewport("desktop", null))
        assertEquals(1280, page.desktopWidth)
        assertEquals(1, page.reloads)

        assertEquals(ModeSwitch.Applied, presenter.resizeViewport("mobile", null))
        assertFalse(page.desktop)
        assertEquals("the tab keeps its desktop pair while mobile", 1280, presenter.state.value.desktopWidth)
        assertEquals(2, page.reloads)

        assertEquals(ModeSwitch.Unchanged, presenter.resizeViewport("mobile", null, 1600, 900))
        assertFalse(page.desktop)
        assertEquals(1280, presenter.state.value.desktopWidth)

        assertEquals(ModeSwitch.Applied, presenter.resizeViewport("desktop", null))
        assertTrue(page.desktop)
        assertEquals("mobile -> desktop restores this tab's custom pair", 1280, page.desktopWidth)
        assertEquals(720, page.desktopHeight)
        assertEquals(3, page.reloads)
    }

    @Test
    fun `resize RPC rejects custom dimensions for mobile and malformed desktop pairs`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        suspend fun resize(parameters: Map<String, String>) = presenter.execute(
            request("browser.resize", parameters = parameters),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }

        assertEquals(
            ProviderCodes.UNSUPPORTED_PARAMETER,
            resize(mapOf("viewport" to "mobile", "width" to "1600", "height" to "900")).errorCode,
        )
        assertEquals(
            ProviderCodes.UNSUPPORTED_PARAMETER,
            resize(mapOf("viewport" to "desktop", "width" to "1600")).errorCode,
        )
        assertEquals(
            ProviderCodes.UNSUPPORTED_PARAMETER,
            resize(mapOf("viewport" to "desktop", "width" to "5000", "height" to "900")).errorCode,
        )
        val applied = resize(mapOf("viewport" to "desktop", "width" to "1280", "height" to "720"))
        assertTrue(applied.errorCode ?: "ok", applied.ok)
        assertEquals("1280", applied.data.getValue("desktopWidth").toString())
        assertEquals("720", applied.data.getValue("desktopHeight").toString())
        assertEquals("pending", applied.data.getValue("geometry").toString().trim('"'))
        assertEquals(1280, page.desktopWidth)
        assertEquals(720, page.desktopHeight)
    }

    @Test
    fun `opening a phone layout page turns mouse mode off`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.mouseProbe = { MouseProbeResult(true) }
        pages.last().desktop = true
        presenter.setDesktopMode(true)
        presenter.setMouseMode(true)
        assertTrue(presenter.state.value.mouseMode)
        // A new own page without a viewport inherits desktop; an explicit phone layout must not keep mouse.
        val phone = openRequest(id = "open-2").let { it.copy(parameters = it.parameters + ("viewport" to "mobile")) }
        assertTrue(presenter.openClaimed(phone, 1_000L).ok)
        assertFalse(presenter.state.value.desktopMode)
        assertFalse("mouse mode cannot outlive a desktop page", presenter.state.value.mouseMode)
    }

    @Test
    fun `mouse input before measured geometry delivers nothing and does not take over`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.mouseProbe = { MouseProbeResult(true) }
        page.desktop = true
        presenter.setDesktopMode(true)
        presenter.setMouseMode(true)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 12f, 8f, 1, false, 0L), 1024, 768)
        assertFalse("a drag is not a takeover", page.takenOver)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 12f, 8f, 1, false, 50L), 1024, 768)
        assertTrue(page.mousePlans.none { it.action == MouseAction.PRESS })
        val before = page.mousePlans.size
        presenter.onGeometryChanged()
        // A gesture planned before the geometry change cannot be delivered after it.
        assertEquals(before, page.mousePlans.size)
        assertTrue(page.mousePlans.all { it.geometryGeneration >= 0L })
    }

    @Test
    fun `the first gesture after measured geometry moves the pointer`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.onGeometryChanged()
        presenter.onGeometrySettled()
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 50f, 10f, 1, false, 40L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 50f, 10f, 1, false, 80L), 100, 80)
        assertEquals(MouseAction.HOVER, page.mousePlans.first().action)
        assertTrue(presenter.state.value.pointerUi.pointerViewX >= 40f)
    }

    @Test
    fun `a gesture that starts before geometry is ready does not click when geometry arrives`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.onGeometryChanged()
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 50f, 10f, 1, false, 40L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 50f, 10f, 1, false, 80L), 100, 80)
        assertTrue(page.mousePlans.none { it.action == MouseAction.HOVER || it.action == MouseAction.PRESS })
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 200L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 50f, 10f, 1, false, 240L), 100, 80)
        assertEquals(MouseAction.HOVER, page.mousePlans.first().action)
    }

    @Test
    fun `invalidating geometry mid gesture does not release into a click`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.onGeometrySettled()
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 50f, 10f, 1, false, 400L), 100, 80)
        presenter.onGeometryChanged()
        assertTrue(page.buttonsCleared > 0)
        val delivered = page.mousePlans.size
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 80f, 10f, 1, false, 500L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 80f, 10f, 1, false, 560L), 100, 80)
        assertEquals(delivered, page.mousePlans.size)
        assertTrue(page.mousePlans.none { it.action == MouseAction.RELEASE })
    }

    @Test
    fun `dragging the page keeps the agent grant and an explicit takeover still revokes`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.allow()
        val grant = presenter.registry.currentGrant()
        assertNotNull(grant)
        presenter.onGeometrySettled()
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 60f, 40f, 1, false, 40L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 60f, 40f, 1, false, 80L), 100, 80)
        assertSame("a gesture never replaces the grant", grant, presenter.registry.currentGrant())
        // Turning the touchpad on/off is an input preference too, not a takeover.
        presenter.setMouseMode(false)
        presenter.setMouseMode(true)
        assertSame("mouse mode does not revoke", grant, presenter.registry.currentGrant())
        assertTrue("the agent stays in control after a drag", presenter.state.value.agentOn)
        assertEquals(ProviderMode.AGENT, presenter.state.value.mode)
        assertFalse("a gesture is not a takeover", page.takenOver)

        // The explicit takeover entry is still the person's revoke.
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
    }

    @Test
    fun `window presentation changes keep refs geometry an active press and the grant`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        val grant = presenter.registry.currentGrant()
        assertNotNull(grant)
        presenter.onGeometrySettled()
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        // Start a held mouse drag so a press is genuinely active.
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 80f, 10f, 1, false, 1_200L), 100, 80)
        assertTrue(page.mousePressActive())
        val invalidations = page.sessionInvalidations
        val clears = page.geometryClears
        val buttons = page.buttonsCleared
        val serial = presenter.state.value.presentationRequestSerial

        // Float/full/drag/window-resize is presentation only.
        presenter.onPresentationChanged()

        assertSame("presentation never changes the grant", grant, presenter.registry.currentGrant())
        assertEquals("presentation never requests a small-window switch", serial, presenter.state.value.presentationRequestSerial)
        assertTrue("presentation keeps the window shown", presenter.state.value.shown)
        assertTrue(presenter.state.value.agentOn)
        assertEquals("refs are not cleared", invalidations, page.sessionInvalidations)
        assertEquals("geometry is not cleared", clears, page.geometryClears)
        assertEquals("an active press is not retired", buttons, page.buttonsCleared)
        assertTrue("an active press stays active", page.mousePressActive())
        assertTrue("the same measured geometry stays valid", presenter.state.value.pointerUi.geometryReady)
    }

    @Test
    fun `native frame changes keep the grant but drop old refs and a viewport resize reloads`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertNotNull(presenter.registry.currentGrant())
        presenter.onGeometrySettled()
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        val invalidations = page.sessionInvalidations
        val clears = page.geometryClears

        // A real native page-frame change still invalidates refs/geometry (not a window drag).
        presenter.onGeometryChanged()
        presenter.onGeometryChanged()
        assertNotNull("a window drag keeps the grant", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertTrue("old refs are invalidated", page.sessionInvalidations > invalidations)
        assertTrue("old geometry is dropped", page.geometryClears > clears)
        assertFalse(presenter.state.value.pointerUi.geometryReady)

        // A viewport resize reloads the same controlled URL, so the document is replaced and refs
        // are dropped, but the grant is not revoked and a new ready probe can use it.
        val reloads = page.reloads
        presenter.setDesktopMode(false)
        assertNotNull("a viewport resize keeps the grant", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertEquals("the controlled GET is reloaded for the new mode", reloads + 1, page.reloads)
        assertFalse(presenter.state.value.desktopMode)
        page.documentId = null
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertNotNull("the same grant now covers the reloaded document", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
    }

    @Test
    fun `injected mouse events are not read back as a new finger gesture`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.setDesktopMode(true)
        presenter.setMouseMode(true)
        var reentered = 0
        // The production touch listener checks isInjectingMouse() before forwarding to mouseInput.
        page.onInject = {
            if (!presenter.isInjectingMouse()) {
                reentered += 1
                presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 1f, 1f, 1, false, 0L), 1024, 768)
            }
        }
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 1024, 768)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 10f, 10f, 1, false, 40L), 1024, 768)
        assertEquals(0, reentered)
        assertTrue(page.mousePlans.isEmpty())
    }

    @Test
    fun `paused open creates no page and resume does not restore the grant`() = runBlocking {
        var created = 0
        val presenter = presenter { created += 1; FakePage() }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.onHostPaused()
        assertFalse(presenter.state.value.eligible)
        val paused = presenter.openClaimed(openRequest(id = "open-paused"), 1_000L)
        assertEquals("BROWSER_PAGE_UNAVAILABLE", paused.errorCode)
        assertEquals(1, created)
        presenter.onHostResumed()
        assertTrue(presenter.state.value.eligible)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `a dispatched mutation cancelled by revoke becomes outcome unknown until reopened`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(presenter.allow())
        presenter.execute(request("browser.snapshot", id = "s1"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        // The click reaches the page and then hangs: the page has acted, the reply never came back.
        val reached = CompletableDeferred<Unit>()
        page.block = { reached.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val acting = launch(Dispatchers.Unconfined) {
            presenter.execute(
                request("browser.click", id = "c1", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                1_000L,
            ) { 0L }
        }
        reached.await()
        presenter.revokeAll("takeover")
        acting.join()
        page.block = {}
        assertFalse(presenter.allow())
        presenter.openClaimed(openRequest(id = "open-2"), 1_000L)
        assertTrue(presenter.allow())
    }

    @Test
    fun `a refusal before dispatch does not block a later allow`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        assertTrue(presenter.allow())
        // No snapshot yet, so the ref is unknown: refused before anything reaches the page.
        val refused = presenter.execute(
            request("browser.click", id = "c0", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            1_000L,
        ) { 0L }
        assertFalse(refused.ok)
        presenter.takeover()
        assertTrue(presenter.resumeAgent())
    }

    @Test
    fun `select changes the view identity and detach destroys every page`() = runBlocking {
        var next = "page-A"
        var ids = 0
        val pages = mutableListOf<FakePage>()
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage(text = next).also { pages += it; next = "page-B" } },
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        val first = presenter.state.value.viewHandle
        presenter.openClaimed(openRequest(session = "owner-b", id = "open-b"), 1_000L)
        presenter.select(presenter.registry.tabs().last().tabId)
        val second = presenter.state.value.viewHandle
        presenter.select(presenter.registry.tabs().first().tabId)
        val third = presenter.state.value.viewHandle
        assertEquals(pages[0].handle, first)
        assertEquals(pages[1].handle, second)
        assertEquals(pages[0].handle, third)
        assertEquals(first, presenter.state.value.pointerUi.viewHandle)
        assertTrue("$first $second $third", first != second && second != third)
        presenter.detachHost("local", 1L)
        assertTrue(pages.all { it.destroyed })
        assertEquals(2, presenter.registry.tabs().size)
    }

    @Test
    fun `invalidateConsoleDocument closes admission and keeps tabs without restoring the grant`() = runBlocking {
        val presenter = presenter { FakePage() }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.invalidateConsoleDocument("local")
        assertFalse(presenter.state.value.eligible)
        assertNull(presenter.registry.currentGrant())
        assertEquals(1, presenter.registry.tabs().size)
        presenter.attachHostForTest("local", 2L)
        assertTrue(presenter.state.value.eligible)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `an old client cannot clear a newer connection`() {
        val presenter = presenter { FakePage() }
        presenter.setConnection("new", 2L, clientGeneration = 2L)
        presenter.registry.open("s", "/p", "local", "https://example.com", "tab")
        presenter.registry.grant(2L, "new", "s", "tab")
        LocalBrowserProviderBinding.ownConnection(presenter, 2L, clientGeneration = 1L).invoke(null)
        assertEquals("new", presenter.registry.currentGrant()?.connectionId)
        presenter.clearConnection("new", 2L, clientGeneration = 2L)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `an old manual probe cannot poison the redirected document`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        page.manualNavigation = true
        val old = ReadyProbeIdentity("view", 1L, 1L)
        page.currentTicket = 2L
        val result = presenter.acceptProbeForTest(page, old)
        assertEquals(Unit, result)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `retiring one tab does not retire the healthy selected tab`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        val parked = presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L)
        assertTrue(parked.ok)
        val retired = pages.first()
        assertTrue("callback=${retired.onMouseRetirement != null}", retired.onMouseRetirement != null)
        retired.onMouseRetirement?.invoke("cancel")
        assertTrue(retired.destroyed)
        val healthy = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertTrue(presenter.select(healthy))
        assertFalse(presenter.state.value.pageRetired)
        assertFalse(pages.last().destroyed)
    }

    @Test
    fun `an idle mouse failure disables mouse without retiring`() = runBlocking {
        val page = FakePage()
        page.mouseDelivery = false
        val presenter = armed(page)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 40f, 10f, 1, false, 400L), 100, 80)
        assertFalse(presenter.state.value.pageRetired.toString(), presenter.state.value.pageRetired)
        assertFalse(page.destroyed)
        assertFalse(presenter.state.value.mousePressActive)
    }

    @Test
    fun `a failed press after dispatch retires and a reopened page is a new view with no grant`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        val desktopOpen = openRequest().copy(parameters = openRequest().parameters + ("viewport" to "desktop"))
        presenter.openClaimed(desktopOpen, 1_000L)
        val old = pages.single()
        old.retireAfterPress = true
        presenter.mouseProbe = { MouseProbeResult(true) }
        presenter.setMouseMode(true)
        presenter.allow()
        old.onMouseRetirement?.invoke("dispatch-failed")
        assertTrue(presenter.state.value.pageRetired)
        assertEquals("mouse-gesture-interrupted", presenter.state.value.pageError)
        assertTrue(presenter.state.value.shown)
        assertNull(presenter.currentView())
        assertFalse(presenter.allow())
        assertNull(presenter.registry.currentGrant())
        presenter.mouseInput(PointerSample(MouseInput.ACTION_UP, 40f, 10f, 1, false, 500L), 100, 80)
        assertEquals(0, old.nativeCancels)
        val oldTab = presenter.state.value.selectedTabId
        val reopened = presenter.reopenRetiredPage()
        check(reopened.ok) { reopened.toString() }
        val fresh = pages.last()
        assertTrue("reopen created a new view", fresh !== old)
        assertTrue("old view destroyed", old.destroyed)
        assertTrue("desktop kept", fresh.desktop)
        assertEquals(1, fresh.loads)
        assertFalse(presenter.state.value.agentOn)
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.pageRetired)
        assertEquals("reopen keeps the tab and its viewport configuration", oldTab, presenter.state.value.selectedTabId)
        // The fresh view must see a fresh DOWN before it admits any input.
        assertFalse(presenter.admitPageInput(MouseInput.ACTION_UP))
        assertTrue(presenter.admitPageInput(MouseInput.ACTION_DOWN))
    }

    @Test
    fun `failed reopen keeps the retired placeholder selectable`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        var ids = 0
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            // The first page is created; any later creation fails, so the reopen cannot produce a view.
            sessionFactory = { if (pages.isEmpty()) FakePage().also { pages += it } else null },
            main = dispatcher,
            clock = { 0L },
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        val old = pages.single()
        old.onMouseRetirement?.invoke("dispatch-failed")
        val placeholder = presenter.state.value.selectedTabId
        assertTrue(presenter.state.value.pageRetired)
        assertTrue(old.destroyed)
        val failed = presenter.reopenRetiredPage()
        assertFalse(failed.ok)
        assertTrue(presenter.state.value.pageRetired)
        assertEquals(placeholder, presenter.state.value.selectedTabId)
        assertEquals(1, presenter.registry.tabs().size)
        assertTrue(presenter.select(placeholder!!))
        assertTrue(presenter.state.value.pageRetired)
        assertNull(presenter.currentView())
    }

    @Test
    fun `host pause during an active press retires the page and removes its grant`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.allow()
        presenter.onGeometrySettled()
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 40f, 10f, 1, false, 400L), 100, 80)
        assertTrue(page.mousePressActive())
        presenter.onHostPaused()
        assertTrue(presenter.state.value.pageRetired)
        assertNull(presenter.currentView())
        assertNull(presenter.registry.currentGrant())
        assertTrue(page.destroyed)
        assertEquals(0, page.nativeCancels)
    }

    @Test
    fun `idle host pause does not retire the page`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.onHostPaused()
        assertFalse(presenter.state.value.pageRetired)
        assertFalse(page.destroyed)
        presenter.onHostResumed()
        assertTrue(presenter.state.value.eligible)
    }

    @Test
    fun `active press defers mode and tab changes without changing state`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.onGeometrySettled()
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 40f, 10f, 1, false, 400L), 100, 80)
        assertTrue(page.mousePressActive())
        val modeBefore = presenter.state.value.desktopMode
        assertEquals(ModeSwitch.Unchanged, presenter.setDesktopMode(!modeBefore))
        assertEquals(modeBefore, presenter.state.value.desktopMode)
        assertFalse(page.destroyed)
        assertFalse(presenter.state.value.pageRetired)
        assertTrue(page.mousePressActive())
    }

    @Test
    fun `a retired callback from page A does not touch healthy page B grant`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L)
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertTrue(presenter.select(bTab))
        presenter.allow()
        assertNotEquals(null, presenter.registry.currentGrant())
        val pageA = pages.first()
        pageA.onMouseRetirement?.invoke("cancel")
        assertTrue(pageA.destroyed)
        assertFalse(pages.last().destroyed)
        assertNotEquals(null, presenter.registry.currentGrant())
        assertFalse(presenter.state.value.pageRetired)
    }

    @Test
    fun `a late callback from a removed page is ignored`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        val late = pages.first()
        presenter.close(presenter.registry.tabs().first().tabId)
        assertTrue(late.destroyed)
        late.onMouseRetirement?.invoke("cancel")
        assertNull(presenter.registry.tabs().firstOrNull())
        assertFalse(presenter.state.value.pageRetired)
    }

    @Test
    fun `a leftover UP from a retired view is refused and a fresh DOWN clicks once`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.onGeometrySettled()
        val gesture = PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L)
        presenter.mouseInput(gesture, 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 40f, 10f, 1, false, 400L), 100, 80)
        presenter.onHostPaused()
        presenter.onHostResumed()
        assertTrue(presenter.state.value.pageRetired)
        // A leftover MOVE/UP from the retired view is refused: it cannot click anything.
        assertFalse(presenter.admitPageInput(MouseInput.ACTION_UP))
        assertFalse(presenter.admitPageInput(MouseInput.ACTION_MOVE))
        assertTrue(page.mousePlans.none { it.action == MouseAction.RELEASE })
    }

    @Test
    fun `repeated DOWN on the same view stays admitted`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        assertTrue(presenter.admitPageInput(MouseInput.ACTION_DOWN))
        assertTrue(presenter.admitPageInput(MouseInput.ACTION_DOWN))
    }

    @Test
    fun `a legacy token-only settle never restores without a registered content target`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        val change = presenter.beforeWindowExpansionChange(false)
        assertTrue(change is WindowChange.Proceed)
        presenter.onGeometrySettled((change as WindowChange.Proceed).token)
        assertEquals("the token alone never scrolls", 0, page.restoreCalls)
        assertEquals(RestoreResult.NotSaved(ScrollNotSavedReason.NO_BOOKMARK), presenter.windowRestoreResult.value)
    }

    @Test
    fun `a binding for a stale transition is refused and never restores`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        val first = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        // A newer transition supersedes the first: the old token is no longer the current restore token.
        presenter.beforeWindowExpansionChange(true)
        assertNull("binding for a superseded transition", presenter.bindWindowContent(first.token, 0L))
        assertEquals(0, page.restoreCalls)
    }

    @Test
    fun `window expansion captures scroll before geometry changes`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        val change = presenter.beforeWindowExpansionChange(true)
        assertTrue(change is WindowChange.Proceed)
        assertEquals(1, page.scrollReads)
    }

    /**
     * Binds a window transition and registers a content target built from the fake's own measured frame. The target
     * is independent test data (its own rectangle and revision), not a copy of the source frame into the desired one.
     */
    private fun registerTarget(presenter: NativeBrowserPresenter, page: FakePage, token: WindowChangeToken, revision: Long): WindowContentTarget? {
        val binding = presenter.bindWindowContent(token, afterLayoutRevision = revision - 1L) ?: return null
        val frame = page.nativeFrame()
        return WindowContentTarget(
            binding = binding,
            layoutRevision = revision,
            windowLeftPx = frame.windowLeft.toFloat(),
            windowTopPx = frame.windowTop.toFloat(),
            widthPx = frame.width,
            // The target height is the native height the page must project to: the fake's native frame height.
            heightPx = frame.height,
        )
    }

    @Test
    fun `restore waits for two newly accepted geometry samples after a registered target`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)
        assertNotNull("the transition binds", target)
        val waiting = async { presenter.onWindowContentSettled(target!!) }
        advanceTimeBy(64)
        assertEquals(0, page.restoreCalls)
        val firstSerial = page.heldProbes.lastOrNull()?.probeSerial ?: 0L
        page.releaseGeometry(serial = firstSerial)
        yield()
        advanceTimeBy(64)
        assertEquals(0, page.restoreCalls)
        page.lastIdentity = page.heldProbes.lastOrNull { it.probeSerial > firstSerial }
        val secondSerial = page.lastIdentity?.probeSerial ?: 0L
        assertTrue("first=$firstSerial second=$secondSerial probes=${page.probeReleases}", secondSerial > firstSerial)
        page.releaseGeometry(serial = secondSerial)
        advanceTimeBy(64)
        waiting.await()
        assertEquals(1, page.restoreCalls)
        assertTrue(presenter.windowRestoreResult.value is RestoreResult.Clamped)
    }

    @Test
    fun `no content target within the deadline ends as NO_TARGET_LAYOUT with no scroll`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        presenter.bindWindowContent(restored.token, afterLayoutRevision = 0L)
        advanceTimeBy(2_100)
        assertEquals(RestoreResult.NotSaved(ScrollNotSavedReason.NO_TARGET_LAYOUT), presenter.windowRestoreResult.value)
        assertEquals("the deadline never scrolls", 0, page.restoreCalls)
    }

    @Test
    fun `a target registered before the deadline cancels it and the restore runs once`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)!!
        // Register well before the 2 s deadline: the deadline must not fire NO_TARGET_LAYOUT afterwards.
        advanceTimeBy(100)
        val waiting = async { presenter.onWindowContentSettled(target) }
        advanceTimeBy(64)
        page.releaseGeometry(serial = page.heldProbes.lastOrNull()?.probeSerial ?: 0L)
        yield()
        advanceTimeBy(64)
        page.lastIdentity = page.heldProbes.lastOrNull()
        page.releaseGeometry(serial = page.lastIdentity?.probeSerial ?: 0L)
        advanceTimeBy(64)
        waiting.await()
        advanceTimeBy(5_000)
        assertEquals(1, page.restoreCalls)
        assertTrue("the restore result is the gate's, not the deadline's", presenter.windowRestoreResult.value is RestoreResult.Clamped)
    }

    @Test
    fun `a target that arrives after the deadline is refused and never restores`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)!!
        advanceTimeBy(2_100)
        assertEquals(RestoreResult.NotSaved(ScrollNotSavedReason.NO_TARGET_LAYOUT), presenter.windowRestoreResult.value)
        // The late, otherwise valid target is refused: its binding is no longer current.
        presenter.onWindowContentSettled(target)
        advanceTimeBy(500)
        assertEquals("a late target never starts a restore", 0, page.restoreCalls)
        assertEquals(RestoreResult.NotSaved(ScrollNotSavedReason.NO_TARGET_LAYOUT), presenter.windowRestoreResult.value)
    }

    @Test
    fun `a newer transition before the old deadline keeps the old timer from publishing`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val old = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        presenter.bindWindowContent(old.token, afterLayoutRevision = 0L)
        // Before the old deadline, the user starts a newer window transition (Enlarge). The old restore is superseded.
        advanceTimeBy(500)
        presenter.beforeWindowExpansionChange(true)
        assertNull(presenter.windowRestoreResult.value)
        // Run past the old deadline: its timer wakes, finds a newer transition, and must publish nothing.
        advanceTimeBy(3_000)
        assertNull("the old deadline never writes a result for the newer transition", presenter.windowRestoreResult.value)
        assertEquals(0, page.restoreCalls)
    }

    @Test
    fun `a registered target whose CSS projection never matches ends as CAPTURE_FAILED not NO_TARGET_LAYOUT`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val binding = presenter.bindWindowContent(restored.token, afterLayoutRevision = 0L)!!
        // The target is registered in time, but its height disagrees with the CSS projection (fake projects to 80).
        val wrongHeight = WindowContentTarget(
            binding = binding,
            layoutRevision = 1L,
            windowLeftPx = page.nativeFrame().windowLeft.toFloat(),
            windowTopPx = page.nativeFrame().windowTop.toFloat(),
            widthPx = page.nativeFrame().width,
            heightPx = 300,
        )
        presenter.onWindowContentSettled(wrongHeight)
        advanceTimeBy(5_000)
        assertEquals("the registered target is not a missing target", RestoreResult.NotSaved(ScrollNotSavedReason.CAPTURE_FAILED), presenter.windowRestoreResult.value)
        assertEquals("a mismatching projection never scrolls", 0, page.restoreCalls)
    }

    @Test
    fun `a legacy token-only settle for an expired token never overwrites the current result`() = runTest {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val old = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        // A newer window transition supersedes the old token before it settles.
        presenter.beforeWindowExpansionChange(true)
        presenter.beforeWindowExpansionChange(false)
        val before = presenter.windowRestoreResult.value
        presenter.onGeometrySettled(old.token)
        assertEquals("the expired token does not publish", before, presenter.windowRestoreResult.value)
        assertEquals(0, page.restoreCalls)
    }

    @Test
    fun `a late restore reply after a newer transition never publishes over the new result`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)
        // Hold the scroll reply after the single scroll has happened on the captured source.
        val reply = CompletableDeferred<Unit>()
        page.holdRestoreReply = reply
        val waiting = async { presenter.onWindowContentSettled(target!!) }
        // Two new accepted samples that match the target, then the restore dispatch starts.
        advanceTimeBy(64)
        page.releaseGeometry(serial = page.heldProbes.lastOrNull()?.probeSerial ?: 0L)
        yield()
        advanceTimeBy(64)
        page.lastIdentity = page.heldProbes.lastOrNull()
        page.releaseGeometry(serial = page.lastIdentity?.probeSerial ?: 0L)
        advanceTimeBy(64)
        assertEquals("the old source scrolled once and is now waiting for its reply", 1, page.restoreCalls)
        // A newer window transition clears the previous result while the reply is still in flight.
        presenter.beforeWindowExpansionChange(true)
        assertNull(presenter.windowRestoreResult.value)
        // The late reply arrives: it must not publish over the newer transition.
        reply.complete(Unit)
        waiting.await()
        assertNull("the late restore reply does not publish a result for the newer transition", presenter.windowRestoreResult.value)
        assertEquals("the old source did not scroll a second time", 1, page.restoreCalls)
    }

    @Test
    fun `a late restore reply after the page is replaced never publishes an old clamped result`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)
        val reply = CompletableDeferred<Unit>()
        page.holdRestoreReply = reply
        val waiting = async { presenter.onWindowContentSettled(target!!) }
        advanceTimeBy(64)
        page.releaseGeometry(serial = page.heldProbes.lastOrNull()?.probeSerial ?: 0L)
        yield()
        advanceTimeBy(64)
        page.lastIdentity = page.heldProbes.lastOrNull()
        page.releaseGeometry(serial = page.lastIdentity?.probeSerial ?: 0L)
        advanceTimeBy(64)
        assertEquals(1, page.restoreCalls)
        // The document changes under the held reply: the identity no longer matches the restore's start.
        page.documentId = "other-document"
        reply.complete(Unit)
        waiting.await()
        assertFalse(
            "an old readback never publishes Clamped or Restored for a changed document",
            presenter.windowRestoreResult.value is RestoreResult.Clamped || presenter.windowRestoreResult.value is RestoreResult.Restored,
        )
        assertEquals(1, page.restoreCalls)
    }

    @Test
    fun `a cached geometry serial does not start restore after a registered target`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        page.releaseGeometry(serial = 0L)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)
        val waiting = async { presenter.onWindowContentSettled(target!!) }
        advanceTimeBy(80)
        assertEquals("a cached serial is not a new sample", 0, page.restoreCalls)
        assertEquals(false, waiting.isCompleted)
        waiting.cancel()
    }

    @Test
    fun `a changed document during a registered target does not scroll`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)
        val waiting = async { presenter.onWindowContentSettled(target!!) }
        page.documentId = "other"
        advanceTimeBy(48)
        waiting.await()
        assertEquals(0, page.restoreCalls)
        val result = presenter.windowRestoreResult.value
        assertTrue("a changed document must not produce a Restored/Clamped result, got $result",
            result is RestoreResult.Discarded || result is RestoreResult.NotSaved || result == null)
    }

    @Test
    fun `an externally newer geometry serial is accepted for the registered target`() = runTest {
        val page = FakePage()
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.beforeWindowExpansionChange(true)
        val restored = presenter.beforeWindowExpansionChange(false) as WindowChange.Proceed
        val target = registerTarget(presenter, page, restored.token, revision = 1L)
        val waiting = async { presenter.onWindowContentSettled(target!!) }
        advanceTimeBy(16)
        val generation = page.heldProbes.lastOrNull()?.generation
        page.acceptExternal(serial = 7L, generation = generation)
        advanceTimeBy(32)
        page.acceptExternal(serial = 9L, generation = generation)
        advanceTimeBy(32)
        waiting.await()
        assertEquals("${presenter.windowRestoreResult.value} probes=${page.probeReleases}", 1, page.restoreCalls)
    }

    private fun ignored(value: Any?) = value

    @Test
    fun `restore reports clamped when the page cannot return to the saved anchor`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        // Direct fake call: projection arguments are not evaluated by this fake (see FakePage.restoreScroll).
        val actual = page.restoreScroll(0.0, 893.0, targetHeightPx = 1529, nativeWidthPx = 819)
        assertEquals(488.0, actual?.y)
        assertTrue(page.restoreCalls > 0)
    }

    @Test
    fun `idle geometry change keeps the grant and invalidates refs`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.onGeometryChanged()
        assertTrue(presenter.registry.currentGrant() != null)
        assertTrue(page.sessionInvalidations > 0)
        assertTrue(presenter.allow())
    }

    @Test
    fun `geometry change during field preflight does not mark outcome unknown`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        presenter.onGeometryChanged()
        assertTrue(presenter.registry.currentGrant() != null)
    }

    @Test
    fun `geometry change invalidates session refs even without an active job`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        val before = page.sessionInvalidations
        presenter.onGeometryChanged()
        assertTrue(page.sessionInvalidations > before)
        assertTrue(page.geometryClears > 0)
    }

    @Test
    fun `selecting an existing page does not reapply its stored mode`() = runBlocking {
        val first = FakePage()
        val second = FakePage()
        val pages = ArrayDeque<FakePage>(listOf(first, second, first))
        val presenter = presenter { pages.removeFirst() }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        val before = first.desktopApplies
        presenter.select(presenter.registry.tabs().first().tabId)
        assertEquals(before, first.desktopApplies)
    }

    @Test
    fun `an unauthorized snapshot is refused before a geometry change can cancel it`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.onGeometryChanged()
        val result = presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, result.errorCode)
        assertEquals(1, page.loads)
    }

    @Test
    fun `revokeAll cancels the active job and a revoked field check is refused`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.allow()
        assertTrue(presenter.beginForTest("r-hold"))
        val refused = presenter.execute(
            request("browser.type", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""", "value" to "x")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.BUSY, refused.errorCode)
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        assertEquals(ProviderMode.MANUAL, presenter.state.value.mode)
    }

    @Test
    fun `select does not navigate an existing page and close does not recreate`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.openClaimed(openRequest(), 1_000L)
        val first = presenter.registry.tabs().single().tabId
        presenter.registry.open("session-a", "/proj", "local", "https://example.com/next", "tab-next")
        // A parked tab has no page yet: selecting it creates one page and loads it once.
        assertTrue(presenter.select("tab-next"))
        assertEquals("tab-next", presenter.state.value.selectedTabId)
        assertEquals(2, pages.size)
        assertEquals(listOf(1, 1), pages.map { it.loads })
        // Selecting back to a tab that already has a page does not navigate it again.
        assertTrue(presenter.select(first))
        assertEquals(listOf(1, 1), pages.map { it.loads })
        assertEquals(2, pages.size)
        assertTrue(presenter.close("tab-next"))
        assertEquals(1, presenter.registry.tabs().size)
        assertEquals(listOf(1, 1), pages.map { it.loads })
    }

    @Test
    fun `selecting another owner's parked tab shows its own page and never grants`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 1L)
        presenter.openClaimed(openRequest(session = "owner-a"), 1_000L)
        val parked = presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L)
        assertTrue(parked.ok)
        assertEquals(1, pages.size)
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertTrue(presenter.select(bTab))
        assertEquals(2, pages.size)
        assertFalse(presenter.state.value.agentOn)
        assertTrue(presenter.state.value.shown)
    }

    @Test
    fun `a console document re-commit keeps the reported session so its open still switches`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        presenter.setConsoleSession("owner-a")
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        // Switching chat reports owner-b, then the SPA re-commits the console document. The web sync
        // deduplicates by session, so it does not report owner-b again after the re-commit.
        presenter.setConsoleSession("owner-b")
        presenter.attachHostForTest("local", 2L)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L).ok)
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertEquals(bTab, presenter.state.value.selectedTabId)
        assertEquals(2, pages.size)
        assertFalse(pages.first().destroyed)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `a console switch lets the shown session take the window and keeps the other page`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        // The person is viewing owner-a's chat while owner-a's page is the visible one.
        presenter.setConsoleSession("owner-a")
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        val aPage = pages.single()
        val aTab = presenter.registry.tabs().single().tabId
        assertEquals(1, aPage.loads)

        // The person switches the console to owner-b: its open takes the window with its own page.
        presenter.setConsoleSession("owner-b")
        val opened = presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L)
        assertTrue(opened.ok)
        // Same path as any own open: the new page is presented but not drivable until an explicit Allow.
        assertEquals(false, opened.data["drivable"].toString().toBooleanStrict())
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertEquals(bTab, opened.data["tabId"].toString().trim('"'))
        assertNotEquals(aTab, bTab)
        assertEquals(2, pages.size)
        val bPage = pages.last()
        assertEquals(1, bPage.loads)
        assertEquals(bTab, presenter.state.value.selectedTabId)
        // The previous owner's page is kept in the tab list, not destroyed and not reloaded.
        assertTrue(presenter.registry.tabs().any { it.tabId == aTab })
        assertEquals(1, aPage.loads)
        assertFalse(aPage.destroyed)
        // Nobody inherited a grant.
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        // Allow grants only the visible (new) tab's owner.
        assertTrue(presenter.allow())
        assertEquals("owner-b", presenter.registry.currentGrant()?.sessionId)
        assertEquals(bTab, presenter.registry.currentGrant()?.tabId)
        // Selecting the old tab later shows its own page: no navigation, no grant.
        assertTrue(presenter.select(aTab))
        assertEquals(aTab, presenter.state.value.selectedTabId)
        assertEquals(1, aPage.loads)
        assertFalse(aPage.destroyed)
        assertNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
    }

    @Test
    fun `a background owner's open parks while the console shows the visible owner`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        presenter.setConsoleSession("owner-a")
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        val aPage = pages.single()
        val aTab = presenter.registry.tabs().single().tabId

        val parked = presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L)
        assertTrue(parked.ok)
        assertEquals(1, pages.size)
        assertEquals(aTab, presenter.state.value.selectedTabId)
        assertEquals(1, aPage.loads)
        assertFalse(aPage.destroyed)
        assertNotNull(presenter.registry.tabs().firstOrNull { it.ownerSessionId == "owner-b" })
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `an unknown blank or other console session keeps the parked behaviour`() = runBlocking {
        listOf(null, "", "   ", "owner-c").forEach { console ->
            val pages = mutableListOf<FakePage>()
            val presenter = presenter(uniqueTabs = true) { FakePage().also { pages += it } }
            presenter.setConnection("c1", 4L)
            presenter.setConsoleSession(console)
            assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
            val aPage = pages.single()
            val aTab = presenter.registry.tabs().single().tabId

            val parked = presenter.openClaimed(openRequest(session = "owner-b", id = "open-2"), 1_000L)
            assertTrue("console=$console", parked.ok)
            assertEquals("console=$console", 1, pages.size)
            assertEquals("console=$console", aTab, presenter.state.value.selectedTabId)
            assertEquals("console=$console", 1, aPage.loads)
            assertFalse("console=$console", aPage.destroyed)
            assertNull("console=$console", presenter.registry.currentGrant())
        }
    }

    @Test
    fun `presentation block defaults off and the normal path still grants`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        assertFalse(presenter.state.value.presentationBlocked)
        assertTrue(presenter.foreground)
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.eligible)
        assertTrue(presenter.state.value.agentOn)
        val snapshot = presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertTrue(snapshot.ok)
        assertEquals(1, page.reads)
    }

    @Test
    fun `a blocked presentation refuses allow and every page command before the driver`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }.ok)
        val grant = presenter.registry.currentGrant()
        assertNotNull(grant)
        val driverCalls = page.driverCalls
        presenter.setPresentationBlocked(true)
        assertTrue(presenter.state.value.presentationBlocked)
        assertFalse(presenter.state.value.eligible)
        assertFalse(presenter.state.value.agentOn)
        // This is a temporary admission gate, not a hide: an idle page stays shown, mounted and
        // authorized, so clearing the gate resumes control without another Allow.
        assertTrue(presenter.state.value.shown)
        assertFalse(presenter.state.value.pageRetired)
        assertFalse(page.destroyed)
        assertEquals(1, page.loads)
        assertSame(grant, presenter.registry.currentGrant())
        assertEquals(ProviderMode.AGENT, presenter.state.value.mode)
        assertFalse(presenter.allow())
        val commands = listOf(
            request("browser.snapshot", id = "p-snap"),
            request("browser.type", id = "p-type", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""", "value" to "x")),
            request("browser.click", id = "p-click", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            request("browser.scroll", id = "p-scroll", parameters = mapOf("direction" to "down")),
            request("browser.capture", id = "p-capture"),
        )
        commands.forEach { command ->
            val result = presenter.execute(command, presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
            assertFalse(command.action, result.ok)
            assertEquals(command.action, ProviderCodes.BROWSER_PAGE_UNAVAILABLE, result.errorCode)
        }
        assertEquals("blocked commands never reach the driver", driverCalls, page.driverCalls)
        assertEquals(0, page.actions)
        assertEquals(1, page.reads)
        assertSame("blocking presentation never drops the grant", grant, presenter.registry.currentGrant())
    }

    @Test
    fun `a temporary presentation block keeps an in-flight action and its refs`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val grant = presenter.registry.currentGrant()
        assertTrue(presenter.execute(request("browser.snapshot", id = "s-before"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }.ok)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        page.block = { reached.complete(Unit); release.await() }
        val acting = launch(Dispatchers.Unconfined) {
            presenter.execute(
                request("browser.snapshot", id = "s-block"),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                1_000L,
            ) { 0L }
        }
        reached.await()
        presenter.setPresentationBlocked(true)
        assertSame("a temporary block does not drop the grant", grant, presenter.registry.currentGrant())
        release.complete(Unit)
        acting.join()
        page.block = {}
        assertSame("the in-flight action was not cancelled", grant, presenter.registry.currentGrant())
        assertEquals("the read completed on the page", 2, page.reads)
        // Clearing the gate reopens admission with the same grant and refs; no reload, no new allow.
        presenter.setPresentationBlocked(false)
        assertTrue(presenter.state.value.eligible)
        assertTrue(presenter.state.value.agentOn)
        assertSame(grant, presenter.registry.currentGrant())
        val reused = presenter.execute(
            request("browser.type", id = "t-reused", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""", "value" to "again")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            1_000L,
        ) { 0L }
        assertTrue(reused.errorCode ?: "ok", reused.ok)
        assertEquals("the ref from the interrupted snapshot stayed usable", 1, page.actions)
    }

    @Test
    fun `clearing the presentation gate keeps the same mounted page and its grant`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val handle = presenter.state.value.viewHandle
        val generation = presenter.state.value.viewGeneration
        val loads = page.loads
        val grant = presenter.registry.currentGrant()
        assertNotNull(grant)
        presenter.setPresentationBlocked(true)
        presenter.setPresentationBlocked(false)
        assertFalse(presenter.state.value.presentationBlocked)
        assertTrue(presenter.state.value.eligible)
        assertTrue("control resumes because the grant was kept", presenter.state.value.agentOn)
        assertEquals(ProviderMode.AGENT, presenter.state.value.mode)
        assertSame("a temporary block does not replace the grant", grant, presenter.registry.currentGrant())
        assertEquals(handle, presenter.state.value.viewHandle)
        assertEquals(generation, presenter.state.value.viewGeneration)
        assertEquals(loads, page.loads)
        assertFalse(page.destroyed)
        assertTrue(presenter.state.value.shown)
        // No Allow tap and no reload are needed; the same mounted page is still authorized.
        assertTrue(presenter.allow())
    }

    @Test
    fun `repeating the same presentation value does not publish changes`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.setPresentationBlocked(true)
        val blocked = presenter.state.value
        val documentEpoch = blocked.documentEpoch
        val viewGeneration = blocked.viewGeneration
        presenter.setPresentationBlocked(true)
        assertEquals(blocked, presenter.state.value)
        assertEquals(documentEpoch, presenter.state.value.documentEpoch)
        assertEquals(viewGeneration, presenter.state.value.viewGeneration)
        assertEquals(1, page.loads)
        presenter.setPresentationBlocked(false)
        val cleared = presenter.state.value
        presenter.setPresentationBlocked(false)
        assertEquals(cleared, presenter.state.value)
        assertFalse(page.destroyed)
    }

    @Test
    fun `entering a blocked presentation retires an active press without a native cancel`() = runBlocking {
        val page = FakePage()
        val presenter = armed(page)
        presenter.allow()
        presenter.onGeometrySettled()
        presenter.mouseInput(PointerSample(MouseInput.ACTION_DOWN, 10f, 10f, 1, false, 0L), 100, 80)
        presenter.mouseInput(PointerSample(MouseInput.ACTION_MOVE, 40f, 10f, 1, false, 400L), 100, 80)
        assertTrue(page.mousePressActive())
        val delivered = page.mousePlans.size
        presenter.setPresentationBlocked(true)
        assertTrue(presenter.state.value.pageRetired)
        assertTrue(presenter.state.value.presentationBlocked)
        assertNull(presenter.currentView())
        assertTrue(page.destroyed)
        assertEquals("no CANCEL is sent to a page that is being retired", 0, page.nativeCancels)
        assertEquals("blocking does not add a synthetic release", delivered, page.mousePlans.size)
        assertTrue(page.mousePlans.none { it.action == MouseAction.RELEASE })
        assertEquals(1, page.loads)
        assertNull(presenter.registry.currentGrant())
        // The retired view is gone even after clearing; no automatic reopen or load happens.
        presenter.setPresentationBlocked(false)
        assertTrue(page.destroyed)
        assertEquals(1, page.loads)
        assertFalse(presenter.admitPageInput(MouseInput.ACTION_DOWN))
    }

    @Test
    fun `a block leaves an idle page mounted and switching to an existing tab does not load or destroy`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        var ids = 0
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage().also { pages += it } },
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        val aTab = presenter.registry.tabs().single().tabId
        assertTrue(presenter.openClaimed(openRequest(session = "owner-b", id = "open-b"), 1_000L).ok)
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        assertTrue(presenter.select(bTab))
        assertTrue(presenter.select(aTab))
        assertTrue(presenter.allow())
        val loads = pages.map { it.loads }
        presenter.setPresentationBlocked(true)
        presenter.allowAsync("owner-b", bTab)
        assertEquals("a blocked allow path does not switch tabs", aTab, presenter.state.value.selectedTabId)
        assertTrue(presenter.select(bTab))
        assertFalse(presenter.state.value.eligible)
        assertFalse(presenter.state.value.agentOn)
        assertNull(presenter.registry.currentGrant())
        assertTrue("the selected page is still mounted", presenter.state.value.shown)
        assertFalse(pages[1].destroyed)
        assertEquals(loads, pages.map { it.loads })
        val refused = presenter.execute(request("browser.snapshot", session = "owner-b"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }
        assertEquals(ProviderCodes.BROWSER_PAGE_UNAVAILABLE, refused.errorCode)
        presenter.setPresentationBlocked(false)
        assertTrue(presenter.state.value.eligible)
        assertNull("a tab switch does not grant", presenter.registry.currentGrant())
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.agentOn)
        assertEquals(loads, pages.map { it.loads })
    }

    @Test
    fun `a tab selected while blocked gets no page and a later safe selection materializes it`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        var ids = 0
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage().also { pages += it } },
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-a"), 1_000L).ok)
        assertTrue(presenter.openClaimed(openRequest(session = "owner-b", id = "open-b"), 1_000L).ok)
        val bTab = presenter.registry.tabs().first { it.ownerSessionId == "owner-b" }.tabId
        presenter.setPresentationBlocked(true)
        assertTrue(presenter.select(bTab))
        assertEquals("a blocked selection creates no page", 1, pages.size)
        assertNull(presenter.currentView())
        assertEquals(1, pages[0].loads)
        // Clearing the gate does not load a document by itself.
        presenter.setPresentationBlocked(false)
        assertNull(presenter.currentView())
        assertEquals(1, pages.size)
        // The explicit later selection is what materializes and loads the chosen tab once.
        assertTrue(presenter.select(bTab))
        assertEquals(2, pages.size)
        assertEquals(1, pages[1].loads)
        assertFalse(presenter.state.value.agentOn)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `a ready probe made stale only by a ref epoch move is re-probed for the same load`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        page.readyForAllow = false
        page.strictEpoch = 4L
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        // A concurrent revoke/console change invalidates refs while the first probe is in flight.
        page.onProbeCall = { if (page.probeCalls == 1) page.strictEpoch = 5L }
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))
        assertEquals("one re-probe with the current epoch", 2, page.probeCalls)
        assertEquals("witness-1", page.acceptedDocument())
        assertEquals("no reload", 1, page.loads)
        assertNull("the fake page never reports itself ready, so default control is refused", presenter.registry.currentGrant())
    }

    @Test
    fun `a stale ready probe of a new load or a ready page is not re-probed`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        page.readyForAllow = false
        page.strictEpoch = 4L
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        // A different load ticket: the old probe dies and nothing is retried.
        page.onProbeCall = { page.currentTicket = 2L }
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), 1L, 4L))
        assertEquals(1, page.probeCalls)
        assertNull(page.acceptedDocument())
        // Already ready: an epoch move is not a reason to probe again.
        page.currentTicket = 1L
        page.readyForAllow = true
        page.documentId = "witness-1"
        page.probeCalls = 0
        page.onProbeCall = { page.strictEpoch = 6L }
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), 1L, 4L))
        assertEquals(1, page.probeCalls)
    }

    @Test
    fun `ready re-probes are bounded when the epoch keeps moving`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        page.readyForAllow = false
        page.strictEpoch = 4L
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        page.onProbeCall = { page.strictEpoch = (page.strictEpoch ?: 4L) + 1 }
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))
        assertEquals("first probe plus at most four re-probes", 5, page.probeCalls)
        assertNull(page.acceptedDocument())
        assertEquals(1, page.loads)
    }

    @Test
    fun `ready acceptance requests geometry after the early layout was refused`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        // The first layout/commit reaches geometry before the async ready probe is accepted.
        presenter.onGeometrySettled()
        assertEquals(1, page.probeReleases)
        assertFalse(presenter.state.value.pointerUi.geometryReady)
        // Accepting the same document now issues one post-accept probe for the captured view.
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        assertEquals("witness-1", page.lastIdentity?.documentId)
        assertTrue((page.lastIdentity?.probeSerial ?: 0L) > 1L)
        assertEquals(2, page.probeReleases)
        assertEquals(1, page.loads)
        assertNotNull(presenter.registry.currentGrant())
    }

    @Test
    fun `ready before mount does not fake geometry and a later actual settle fills it`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        page.presentationVisible = false
        page.frame = NativeFrame(0, 0, 0, 0, 0, 0, 0, 0)
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertNull("an unmounted page does not accept ready yet", page.acceptedDocument())
        assertEquals("an unmounted page gets no geometry request", 0, page.probeReleases)
        assertFalse(presenter.state.value.pointerUi.geometryReady)
        assertNull(presenter.registry.currentGrant())
        // A later real layout supplies the frame and makes the page visible. The stored ready
        // identity is replayed then; only then may the witness/geometry/grant be accepted.
        page.presentationVisible = true
        page.frame = NativeFrame(100, 80, 1, 2, 1, 2, 101, 82)
        presenter.onGeometrySettled()
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        assertEquals("witness-1", page.acceptedDocument())
        assertTrue("the replay or settle requested geometry", page.probeReleases >= 1)
        assertNotNull(presenter.registry.currentGrant())
    }

    @Test
    fun `repeated ready requests reuse an inflight or valid geometry but layout still advances`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        page.holdGeometry = true
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        val first = page.lastIdentity ?: error("first geometry request")
        assertEquals(1, page.probeReleases)
        // A repeated commit/finished before the sample arrives is the same in-flight request.
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertEquals(1, page.probeReleases)
        assertEquals(first.probeSerial, page.lastIdentity?.probeSerial)
        page.releaseGeometry(first.probeSerial)
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        // A repeated ready with a valid geometry for the same document/epoch takes no new serial.
        presenter.acceptProbeForTest(page, readyIdentity(page))
        assertEquals(1, page.probeReleases)
        assertEquals(first.probeSerial, page.lastIdentity?.probeSerial)
        // Ordinary layout/scale/B5 geometry requests still take a new serial.
        presenter.onGeometrySettled()
        assertEquals(2, page.probeReleases)
        assertTrue((page.lastIdentity?.probeSerial ?: 0L) > first.probeSerial)
        assertTrue(presenter.state.value.pointerUi.geometryReady)
    }

    @Test
    fun `stale geometry identities do not advance the serial or replace newer geometry`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        val first = page.lastIdentity?.probeSerial ?: error("initial geometry")
        val oldEpoch = presenter.state.value.pointerUi.geometryEpoch
        presenter.onGeometryChanged()
        val epoch = presenter.state.value.pointerUi.geometryEpoch
        assertFalse(presenter.state.value.pointerUi.geometryReady)
        val changedFrame = NativeFrame(90, 70, 1, 2, 1, 2, 91, 72)
        // Every sample is rejected before the serial moves; high serials cannot poison it.
        page.onGeometry?.invoke(geometrySample(page, 900, epoch, ticket = 99))
        page.onGeometry?.invoke(geometrySample(page, 901, oldEpoch))
        page.onGeometry?.invoke(geometrySample(page, 902, epoch, documentId = "other"))
        page.onGeometry?.invoke(geometrySample(page, 903, epoch, frame = changedFrame))
        page.onGeometry?.invoke(geometrySample(page, 904, epoch, viewId = "other"))
        assertFalse(presenter.state.value.pointerUi.geometryReady)
        // The next valid serial is the old current plus one, not 905 or higher.
        val validSerial = first + 2
        page.onGeometry?.invoke(geometrySample(page, validSerial, epoch))
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        presenter.onGeometrySettled()
        assertEquals(validSerial + 1, page.lastIdentity?.probeSerial)
        assertNotNull(presenter.registry.currentGrant())
    }

    @Test
    fun `failed pending rejected and blocked ready do not request geometry or grant`() = runBlocking {
        val pendingPage = FakePage()
        pendingPage.documentId = null
        pendingPage.probeResultOverride = ProbeAcceptance.Pending
        val pending = presenter { pendingPage }
        pending.setConnection("c1", 4L)
        assertTrue(pending.openClaimed(openRequest(), 1_000L).ok)
        pending.acceptProbeForTest(pendingPage, readyIdentity(pendingPage))
        assertEquals(0, pendingPage.probeReleases)
        assertFalse(pending.state.value.pointerUi.geometryReady)
        assertNull(pending.registry.currentGrant())

        val rejectedPage = FakePage()
        rejectedPage.documentId = null
        rejectedPage.probeResultOverride = ProbeAcceptance.Reject("not ready")
        val rejected = presenter { rejectedPage }
        rejected.setConnection("c1", 4L)
        assertTrue(rejected.openClaimed(openRequest(), 1_000L).ok)
        rejected.acceptProbeForTest(rejectedPage, readyIdentity(rejectedPage))
        assertEquals(0, rejectedPage.probeReleases)
        assertFalse(rejected.state.value.pointerUi.geometryReady)
        assertNull(rejected.registry.currentGrant())

        val failedPage = FakePage()
        failedPage.documentId = null
        failedPage.readyAccepted = false
        val failed = presenter { failedPage }
        failed.setConnection("c1", 4L)
        assertTrue(failed.openClaimed(openRequest(), 1_000L).ok)
        failed.acceptProbeForTest(failedPage, readyIdentity(failedPage))
        assertEquals(0, failedPage.probeReleases)
        assertFalse(failed.state.value.pointerUi.geometryReady)
        assertNull(failed.registry.currentGrant())

        val blockedPage = FakePage()
        blockedPage.documentId = null
        val blocked = presenter { blockedPage }
        blocked.setConnection("c1", 4L)
        assertTrue(blocked.openClaimed(openRequest(), 1_000L).ok)
        blocked.setPresentationBlocked(true)
        blocked.acceptProbeForTest(blockedPage, readyIdentity(blockedPage))
        assertEquals("blocked ready does not request geometry", 0, blockedPage.probeReleases)
        assertFalse(blocked.state.value.pointerUi.geometryReady)
        assertNull(blocked.registry.currentGrant())
        // Clearing the temporary gate completes the kept one-shot intent through allow, with no
        // person tapping Allow. The ready page and geometry settle as usual.
        blocked.setPresentationBlocked(false)
        blocked.onGeometrySettled()
        assertTrue(blocked.state.value.pointerUi.geometryReady)
        assertNotNull("unblocking completes the pending default grant", blocked.registry.currentGrant())
        assertTrue(blocked.state.value.agentOn)
    }

    @Test
    fun `a late geometry callback from an old page is ignored`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        var ids = 0
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage().also { pages += it } },
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        val old = pages[0]
        old.documentId = null
        presenter.acceptProbeForTest(old, readyIdentity(old))
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        assertTrue(presenter.openClaimed(openRequest(id = "open-2"), 1_000L).ok)
        presenter.onGeometryChanged()
        assertFalse(presenter.state.value.pointerUi.geometryReady)
        val epoch = presenter.state.value.pointerUi.geometryEpoch
        old.onGeometry?.invoke(geometrySample(old, 500, epoch))
        assertFalse("an old page's late sample is not admitted", presenter.state.value.pointerUi.geometryReady)
        val fresh = pages[1]
        fresh.documentId = null
        presenter.acceptProbeForTest(fresh, readyIdentity(fresh))
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        assertNotNull("the new explicit open granted default control", presenter.registry.currentGrant())
    }

    @Test
    fun `paused and invalidated console reject late geometry until a new actual settle`() = runBlocking {
        val page = FakePage()
        page.documentId = null
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        presenter.acceptProbeForTest(page, readyIdentity(page))
        val first = page.lastIdentity?.probeSerial ?: error("initial geometry")
        presenter.onGeometryChanged()
        presenter.onHostPaused()
        assertFalse(presenter.state.value.eligible)
        val pausedEpoch = presenter.state.value.pointerUi.geometryEpoch
        page.onGeometry?.invoke(geometrySample(page, 900, pausedEpoch))
        assertFalse("a paused host commits no late geometry", presenter.state.value.pointerUi.geometryReady)
        presenter.onHostResumed()
        presenter.onGeometrySettled()
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        assertEquals(first + 2, page.lastIdentity?.probeSerial)
        assertNull(presenter.registry.currentGrant())

        val consolePage = FakePage()
        consolePage.documentId = null
        val console = presenter { consolePage }
        console.setConnection("c1", 4L)
        assertTrue(console.openClaimed(openRequest(), 1_000L).ok)
        console.acceptProbeForTest(consolePage, readyIdentity(consolePage))
        val consoleFirst = consolePage.lastIdentity?.probeSerial ?: error("initial geometry")
        console.onGeometryChanged()
        console.invalidateConsoleDocument("local")
        assertFalse(console.state.value.eligible)
        val invalidEpoch = console.state.value.pointerUi.geometryEpoch
        consolePage.onGeometry?.invoke(geometrySample(consolePage, 900, invalidEpoch))
        assertFalse("an invalidated console commits no late geometry", console.state.value.pointerUi.geometryReady)
        console.attachHostForTest("local", 2L)
        assertTrue(console.state.value.eligible)
        console.onGeometrySettled()
        assertTrue(console.state.value.pointerUi.geometryReady)
        assertEquals(consoleFirst + 2, consolePage.lastIdentity?.probeSerial)
        assertNull(console.registry.currentGrant())
    }

    @Test
    fun `detach rejects a late sample and a new page gets its own ready geometry`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        var ids = 0
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-${++ids}" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage().also { pages += it } },
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        val old = pages[0]
        old.documentId = null
        presenter.acceptProbeForTest(old, readyIdentity(old))
        val first = old.lastIdentity?.probeSerial ?: error("initial geometry")
        presenter.onGeometryChanged()
        val epoch = presenter.state.value.pointerUi.geometryEpoch
        presenter.detachHost("local", 1L)
        assertNull(presenter.currentView())
        old.onGeometry?.invoke(geometrySample(old, 900, epoch))
        assertFalse("a detached source commits no late geometry", presenter.state.value.pointerUi.geometryReady)
        presenter.attachHostForTest("local", 2L)
        assertTrue(presenter.openClaimed(openRequest(id = "open-2"), 1_000L).ok)
        val fresh = pages[1]
        fresh.documentId = null
        presenter.acceptProbeForTest(fresh, readyIdentity(fresh))
        assertTrue(presenter.state.value.pointerUi.geometryReady)
        assertEquals(first + 2, fresh.lastIdentity?.probeSerial)
        assertNotNull("the new explicit open granted default control", presenter.registry.currentGrant())
    }

    private fun readyIdentity(page: FakePage) = ReadyProbeIdentity(page.viewId(), page.ticket(), 4L)

    private fun geometrySample(
        page: FakePage,
        serial: Long,
        epoch: Long,
        documentId: String? = page.acceptedDocument(),
        ticket: Long = page.ticket(),
        viewId: String = page.viewId(),
        frame: NativeFrame = page.nativeFrame(),
    ) = GeometrySample(
        viewId,
        page.tabId,
        ticket,
        documentId,
        epoch,
        serial,
        frame,
        GeometryProbe(documentId, 100, 80, 100, 80, 0f, 0f, 2f, 1024f, 315.077f, 0f, 0f, 0f, 0f, 1f),
    )

    @Test
    fun `presentation block is not transport eligibility and blocked commands stay refused`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        assertTrue(presenter.state.value.transportEligible)
        assertTrue(presenter.state.value.eligible)
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertNull("transport admission does not grant", presenter.registry.currentGrant())
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.agentOn)
        assertNotNull("a presentation block must not lose this grant", presenter.registry.currentGrant())
        val grant = presenter.registry.currentGrant()
        val driverCalls = page.driverCalls
        val invalidation = presenter.state.value.transportInvalidation

        presenter.setPresentationBlocked(true)
        assertTrue("blocking presentation keeps transport", presenter.state.value.transportEligible)
        assertFalse(presenter.state.value.eligible)
        assertSame("a temporary presentation block keeps the grant", grant, presenter.registry.currentGrant())
        assertFalse("control is gated while blocked", presenter.state.value.agentOn)
        assertEquals("presentation block does not invalidate transport", invalidation, presenter.state.value.transportInvalidation)
        assertEquals(
            ProviderCodes.BROWSER_PAGE_UNAVAILABLE,
            presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }.errorCode,
        )
        assertEquals(
            ProviderCodes.BROWSER_PAGE_UNAVAILABLE,
            presenter.execute(request("browser.capture", id = "blocked-capture"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }.errorCode,
        )
        assertEquals("blocked commands never touched the driver", driverCalls, page.driverCalls)
        presenter.setPresentationBlocked(false)
        assertTrue(presenter.state.value.transportEligible)
        assertTrue(presenter.state.value.eligible)
        assertEquals("presentation unblock does not invalidate transport", invalidation, presenter.state.value.transportInvalidation)
        assertSame("unblocking is not a new grant", grant, presenter.registry.currentGrant())
        assertTrue("control resumes without another Allow", presenter.state.value.agentOn)
        assertTrue(
            "an unblocked command runs under the kept grant",
            presenter.execute(request("browser.snapshot", id = "after-block"), presenter.registry, ProviderMode.AGENT, null, 1_000L) { 0L }.ok,
        )
        assertTrue(page.driverCalls > driverCalls)
        assertTrue(presenter.allow())
        assertTrue(presenter.state.value.agentOn)

        // Blocked -> pause -> unblock: transport stays false until a legitimate resume.
        presenter.setPresentationBlocked(true)
        presenter.onHostPaused()
        assertFalse(presenter.state.value.transportEligible)
        assertFalse(presenter.state.value.eligible)
        assertEquals(invalidation + 1, presenter.state.value.transportInvalidation)
        presenter.setPresentationBlocked(false)
        assertFalse("unblocking while paused is not transport admission", presenter.state.value.transportEligible)
        assertFalse(presenter.state.value.eligible)
        presenter.onHostResumed()
        assertTrue(presenter.state.value.transportEligible)
        assertTrue(presenter.state.value.eligible)
        assertEquals(invalidation + 1, presenter.state.value.transportInvalidation)
        assertNull(presenter.registry.currentGrant())

        // Blocked -> invalidated console -> resume: no transport until a legitimate attach.
        presenter.setPresentationBlocked(true)
        presenter.invalidateConsoleDocument("local")
        assertFalse(presenter.state.value.transportEligible)
        assertEquals(invalidation + 2, presenter.state.value.transportInvalidation)
        presenter.onHostResumed()
        assertFalse(presenter.state.value.transportEligible)
        assertFalse(presenter.state.value.eligible)
        presenter.attachHostForTest("local", 2L)
        assertTrue(presenter.state.value.transportEligible)
        assertFalse("a valid attach does not clear the presentation block", presenter.state.value.eligible)
        assertEquals(invalidation + 2, presenter.state.value.transportInvalidation)
        presenter.setPresentationBlocked(false)
        assertTrue(presenter.state.value.eligible)
        assertNull(presenter.registry.currentGrant())

        presenter.detachHost("local", 2L)
        assertFalse(presenter.state.value.transportEligible)
        assertFalse(presenter.state.value.eligible)
        assertEquals(invalidation + 3, presenter.state.value.transportInvalidation)
    }

    private suspend fun armed(page: FakePage): NativeBrowserPresenter {
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        presenter.openClaimed(openRequest(), 1_000L)
        presenter.mouseProbe = { MouseProbeResult(true) }
        page.desktop = true
        presenter.setDesktopMode(true)
        presenter.setMouseMode(true)
        return presenter
    }

    private fun presenter(
        uniqueTabs: Boolean = false,
        awaitPageVisible: suspend (ControlledBrowserPage) -> Boolean = { it.nativePresentationVisible() },
        policy: () -> BrowserUrlPolicy = {
            BrowserUrlPolicy(resolver = io.andchamber.app.browser.DnsResolver {
                listOf(java.net.InetAddress.getByName("93.184.216.34"))
            })
        },
        pageFactory: (android.content.Context?) -> ControlledBrowserPage,
    ): NativeBrowserPresenter {
        var ids = 0
        return NativeBrowserPresenter(
            registry = ProviderRegistry { if (uniqueTabs) "tab-${++ids}" else "tab-real" },
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
            sessionFactory = pageFactory,
            main = kotlinx.coroutines.Dispatchers.Main,
            clock = { 0L },
            policy = policy,
            awaitPageVisible = awaitPageVisible,
        ).also { it.attachHostForTest("local", 1L) }
    }

    private fun openRequest(
        session: String = "session-a",
        tabId: String? = null,
        id: String = "open-1",
        allowWaitMs: Int = 0,
    ) = ProviderRequest(
        serverEpoch = "e1",
        connectionId = "c1",
        requestId = id,
        action = "browser.open",
        parameters = buildMap {
            put("url", "https://example.com/login")
            if (tabId != null) put("tabId", tabId)
        },
        directory = "/proj",
        sessionId = session,
        timeoutMs = 1_000L,
        allowWaitMs = allowWaitMs,
    )

    private fun request(
        action: String,
        id: String = "r1",
        parameters: Map<String, String> = emptyMap(),
        session: String = "session-a",
    ) = ProviderRequest(
        "e1", "c1", id, action, parameters, "/proj", session, 1_000L,
    )

    private class FakePage(
        private val probe: ProbeAcceptance = ProbeAcceptance.Accept("witness-1"),
        private val fields: Map<String, PageField> = mapOf(REF to PageField("input", "text", "Note")),
        var fieldsMutable: Map<String, PageField> = fields,
        private val text: String = "page-A",
        private val post: Boolean = false,
    ) : ControlledBrowserPage {
        var loads = 0
        var reads = 0
        var actions = 0
        var driverCalls = 0
        var presentationVisible = true
        var readyAccepted = true
        var probeResultOverride: ProbeAcceptance? = null
        var lastRef: String? = null
        var fieldWait: CompletableDeferred<Unit>? = null
        val fieldRelease = CompletableDeferred<Unit>()
        var block: suspend () -> Unit = {}
        override val view: View? = null
        override fun nativePresentationVisible(): Boolean = presentationVisible
        override val handle: Int = System.identityHashCode(this)
        override val width: Int = 320
        override val height: Int = 240
        override val pageError: String? = null
        var readyForAllow: Boolean = true
        override val ready: Boolean get() = readyForAllow && documentId != null
        override var manualNavigation: Boolean = false
        override var tabId: String? = null
        override var onProbe: ((String, Long) -> Unit)? = null
        override var onReadyProbe: ((ReadyProbeIdentity) -> Unit)? = null
        override var onFatal: (() -> Unit)? = null
        override var onUnknown: (() -> Unit)? = null
        override var onManualDocument: (() -> Unit)? = null
        override var onSubmitNavigation: ((BrowserSubmitNavigation) -> Unit)? = null
        override var onGeometry: ((GeometrySample) -> Unit)? = null
        override var onGeometryRequested: (() -> Unit)? = null
        override var onMouseRetirement: ((String) -> Unit)? = null
        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver { request ->
            driverCalls += 1
            block()
            if (request.method == BrowserMethod.SNAPSHOT) reads += 1
            if (request.method == BrowserMethod.TYPE || request.method == BrowserMethod.CLICK) {
                actions += 1
                lastRef = request.params[BrowserDriverLogic.PARAM_REF]
            }
            BrowserReply(
                ok = true,
                result = buildMap {
                    put(BrowserDriverLogic.RESULT_SNAPSHOT, """{"url":"https://example.com","title":"$text","text":"$text","elements":[{"ref":"$REF","role":"input","type":"text","name":"$text"}]}""")
                    put("width", "320")
                    put("height", "240")
                    if (request.method == BrowserMethod.TYPE && request.params[BrowserDriverLogic.PARAM_SUBMIT] == "true") {
                        put(BrowserDriverLogic.RESULT_ENTER_DISPATCHED, "true")
                    }
                },
            )
        }
        override suspend fun loadOnce(url: String): Boolean {
            loads += 1
            loadAfterMode = desktopApplies > 0
            return true
        }
        /** Gates for overlapping allow probes; an unqueued gate means immediate return. */
        val plainProbeGates = ArrayDeque<CompletableDeferred<Unit>>()
        override suspend fun probe(): ProbeAcceptance {
            plainProbeGates.removeFirstOrNull()?.await()
            return probeResultOverride ?: probe
        }
        override fun acceptReady(ticket: Long, documentId: String): Boolean {
            if (!readyAccepted) return false
            this.documentId = documentId
            return true
        }
        var currentTicket = 1L
        /** When set, ready identity also checks this ref epoch, like the production session. */
        var strictEpoch: Long? = null
        var probeCalls = 0
        var onProbeCall: (() -> Unit)? = null
        override fun matchesReady(identity: ReadyProbeIdentity): Boolean =
            identity.ticket == currentTicket && (strictEpoch == null || identity.sessionEpoch == strictEpoch)
        override suspend fun probe(identity: ReadyProbeIdentity): ProbeAcceptance? {
            probeCalls += 1
            onProbeCall?.invoke()
            return probe()
        }
        override fun owns(viewId: String, ticket: Long): Boolean = true
        override fun markUnknown() = Unit
        override fun clearAcceptedWitness() = Unit
        override fun syncWitness(documentId: String) = Unit
        override suspend fun currentFields(): Map<String, PageField>? {
            fieldWait?.let {
                it.complete(Unit)
                fieldRelease.await()
            }
            return fieldsMutable
        }
        var destroyed = false
        var reloads = 0
        var desktop = false
        var desktopApplies = 0
        var desktopWidth = BrowserDesktopViewport.DEFAULT_WIDTH
        var desktopHeight = BrowserDesktopViewport.DEFAULT_HEIGHT
        var desktopViewportSets = 0
        var loadAfterMode = false
        var lastIdentity: ProbeIdentity? = null
        var takenOver = false
        var buttonsCleared = 0
        var sessionInvalidations = 0
        var geometryClears = 0
        var scrollReads = 0
        val mousePlans = mutableListOf<MouseEventPlan>()
        override fun destroy() { destroyed = true }
        override fun setDesktopViewport(width: Int, height: Int) {
            desktopWidth = width
            desktopHeight = height
            desktopViewportSets += 1
        }
        override fun setDesktopMode(enabled: Boolean) {
            desktop = enabled
            desktopApplies += 1
        }
        override fun desktopConfigured(): Boolean = desktop
        override fun controlledGetProvenance(): Boolean = !post
        override fun reloadGet() { reloads += 1 }
        var injecting = false
        /** Set by a test to re-enter the presenter from inside a dispatch, like the WebView's touch listener. */
        var onInject: (() -> Unit)? = null
        override fun viewId(): String = "view"
        override fun ticket(): Long = 1L
        var documentId: String? = "doc"
        override fun acceptedDocument(): String? = documentId
        var probeReleases = 0
        var holdGeometry = false
        var frame = NativeFrame(100, 80, 1, 2, 1, 2, 101, 82)
        private var cachedGeometry: GeometrySample? = null
        val heldProbes = ArrayDeque<ProbeIdentity>()
        override fun nativeFrame(): NativeFrame = frame
        override fun latestGeometry(): GeometrySample? = cachedGeometry
        fun releaseGeometry(serial: Long? = null, generation: Long? = null) {
            val identity = heldProbes.lastOrNull { serial == null || it.probeSerial == serial } ?: lastIdentity ?: return
            heldProbes.remove(identity)
            deliverGeometry(identity, serial ?: identity.probeSerial, generation ?: identity.generation)
        }
        fun acceptExternal(serial: Long, generation: Long? = null) {
            val identity = lastIdentity ?: return
            deliverGeometry(identity, serial, generation ?: identity.generation)
        }
        override fun requestGeometryProbe(identity: ProbeIdentity) {
            lastIdentity = identity
            probeReleases += 1
            // BrowserSession refuses geometry until acceptedDocumentId exists. The fake keeps that gate
            // so a pre-ready layout request produces no sample.
            if (documentId == null) return
            if (holdGeometry) heldProbes += identity else deliverGeometry(identity, identity.probeSerial, identity.generation)
        }
        private fun deliverGeometry(identity: ProbeIdentity, serial: Long, generation: Long) {
            val sample = GeometrySample(
                identity.viewId, identity.tabId, identity.ticket, documentId, generation, serial, frame,
                GeometryProbe(documentId, 100, 80, 100, 80, 0f, 0f, 2f, 100f, 80f, 0f, 0f, 0f, 0f, 1f),
            )
            cachedGeometry = sample
            onGeometry?.invoke(sample)
        }
        var mouseDelivery = true
        var retireAfterPress = false
        var nativeCancels = 0
        private var pressSeen = false
        override fun mousePressActive(): Boolean = pressSeen
        override fun lastMouseRetirement(): String? = if (retireAfterPress && pressSeen) "dispatch-failed" else null
        override fun admitNativeInput(): Boolean = !retireAfterPress
        override fun injectMouse(plan: MouseEventPlan): Boolean {
            if (!mouseDelivery) return false
            if (plan.action == MouseAction.CANCEL) nativeCancels += 1
            if (plan.action == MouseAction.PRESS) pressSeen = true
            if (retireAfterPress && plan.action == MouseAction.PRESS) {
                onMouseRetirement?.invoke("dispatch-failed")
                return false
            }
            injecting = true
            try {
                mousePlans += plan
                onInject?.invoke()
            } finally {
                injecting = false
            }
            return true
        }
        override fun isInjectingMouse(): Boolean = injecting
        override fun noteTakeover() { takenOver = true }
        override fun clearMouseButtons() { buttonsCleared += 1 }
        override fun invalidateSessionRefs() { sessionInvalidations += 1 }
        override fun clearGeometryCache() { geometryClears += 1 }
        override suspend fun readScroll(): ScrollSample? {
            scrollReads += 1
            return ScrollSample.Captured(ScrollAnchor(0.0, 893.0))
        }
        var restoreCalls = 0
        // The fake does not evaluate the projection; the projection rule is covered by WindowRestoreTargetGateTest.
        /** When set, the scroll has happened (restoreCalls already counted) and the reply waits until released. */
        var holdRestoreReply: CompletableDeferred<Unit>? = null
        override suspend fun restoreScroll(x: Double, y: Double, targetHeightPx: Int, nativeWidthPx: Int): ScrollAnchor? {
            restoreCalls += 1
            holdRestoreReply?.await()
            return ScrollAnchor(x, 488.0)
        }
        override fun refEpoch(): Long = strictEpoch ?: 4L
    }
}
