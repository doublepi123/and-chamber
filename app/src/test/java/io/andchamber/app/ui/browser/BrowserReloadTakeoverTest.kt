package io.andchamber.app.ui.browser

import android.view.View
import io.andchamber.app.R
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserMethod
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
 * Menu Reload vs explicit Reopen, the read-only grant projection after an unconfirmed action, and
 * the Session -> Presenter address/error report. JVM fakes only, no WebView.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserReloadTakeoverTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 1. Reload / Reopen ----

    @Test
    fun `a plain reload after takeover keeps the latch and never reclaims the new page`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.takeover()
        assertTrue(presenter.state.value.explicitTakeover)

        val reloaded = presenter.reloadManual()

        assertTrue(reloaded.errorCode ?: "ok", reloaded.ok)
        assertEquals("reload replaces the page", 2, pages.size)
        assertTrue(pages[0].destroyed)
        assertTrue("a plain reload keeps the explicit takeover", presenter.state.value.explicitTakeover)
        val fresh = pages[1]
        // Before any ready callback: the person's own links/forms are already admitted.
        assertTrue("manual navigation from the commit on, before ready", fresh.manualNavigation)
        assertEquals("the controlled load was marked once", 1, fresh.keepManualCalls)
        assertEquals("exactly one controlled GET, no second load", 1, fresh.loads)
        assertNull(presenter.registry.currentGrant())

        // Ready probe of the new load: no default grant, and the page stays the person's.
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        assertNull("a ready reload after takeover grants nothing", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertTrue(presenter.state.value.explicitTakeover)
        assertTrue("the reloaded page keeps the person's navigation", fresh.manualNavigation)
        assertEquals("ready never loads again", 1, fresh.loads)

        // A late duplicate probe, a reconnect and a presentation change still cannot reclaim it.
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        presenter.setConnection("c1", 5L)
        presenter.setPresentationBlocked(true)
        presenter.setPresentationBlocked(false)
        presenter.onPresentationChanged()
        assertNull(presenter.registry.currentGrant())
        assertTrue(presenter.state.value.explicitTakeover)
        // An agent action is still refused.
        val refused = presenter.execute(request("browser.snapshot", connection = "c1"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, refused.errorCode)
        assertEquals("nothing reached the page", 0, fresh.driverCalls)

        // Only the person's Resume clears the latch.
        assertTrue(presenter.resumeAgent())
        assertNotNull(presenter.registry.currentGrant())
        assertFalse(presenter.state.value.explicitTakeover)
        assertFalse("resume hands navigation back to the agent", fresh.manualNavigation)
    }

    @Test
    fun `a takeover that lands while the reload is loading is honoured at commit`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        lateinit var presenter: NativeBrowserPresenter
        presenter = presenter {
            FakePage().also { created ->
                // The second page's load runs after the old grant was revoked; take over then.
                if (pages.isNotEmpty()) created.onLoad = { presenter.takeover() }
                pages += created
            }
        }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertFalse(presenter.state.value.explicitTakeover)

        assertTrue(presenter.reloadManual().ok)

        val fresh = pages[1]
        assertTrue("the takeover during the load is the latest decision", presenter.state.value.explicitTakeover)
        assertTrue("the committed page is already manual", fresh.manualNavigation)
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        assertNull("no intent was created for the takenover reload", presenter.registry.currentGrant())
    }

    @Test
    fun `a reload without takeover keeps today's default control`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())

        assertTrue(presenter.reloadManual().ok)
        assertNull("reload itself is not a grant", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.explicitTakeover)

        val fresh = pages[1]
        assertFalse("a reload without takeover is not a manual page", fresh.manualNavigation)
        assertEquals(0, fresh.keepManualCalls)
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        assertNotNull("a ready reload gets default control", presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
        assertFalse(fresh.manualNavigation)
    }

    @Test
    fun `an explicit reopen after takeover keeps the new-open meaning`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        presenter.takeover()

        assertTrue(presenter.reopenManual().ok)
        assertFalse("Reopen is a person's new open and replaces the takeover", presenter.state.value.explicitTakeover)

        val fresh = pages[1]
        assertFalse(fresh.manualNavigation)
        assertEquals(0, fresh.keepManualCalls)
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        assertNotNull("the reopened page gets default control", presenter.registry.currentGrant())
        assertFalse(fresh.manualNavigation)
    }

    @Test
    fun `an agent open cannot choose the takeover-preserving reload`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())

        // Whatever the agent puts in its parameters, an agent open is never a manual Reload.
        val opened = presenter.execute(
            openRequest(id = "open-2", extra = mapOf("kind" to "RELOAD", "manualKind" to "reload", "reload" to "true")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertTrue(opened.errorCode ?: "ok", opened.ok)
        assertFalse(presenter.state.value.explicitTakeover)
        val fresh = pages[1]
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        assertNotNull(presenter.registry.currentGrant())
        assertFalse(fresh.manualNavigation)
    }

    @Test
    fun `the reload decision is only a reload kind with a live takeover`() {
        assertTrue(browserReloadKeepsTakeover(ManualOpenKind.RELOAD, explicitTakeover = true))
        assertFalse(browserReloadKeepsTakeover(ManualOpenKind.RELOAD, explicitTakeover = false))
        assertFalse(browserReloadKeepsTakeover(ManualOpenKind.REOPEN, explicitTakeover = true))
        assertFalse(browserReloadKeepsTakeover(ManualOpenKind.NAVIGATE, explicitTakeover = true))
        assertFalse(browserReloadKeepsTakeover(null, explicitTakeover = true))
    }

    // ---- 2. Read-only grant ----

    @Test
    fun `an unconfirmed click shows a read-only grant and Take over revokes reads too`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertFalse(presenter.state.value.agentReadOnly)
        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        val grant = presenter.registry.currentGrant()

        page.clickFails = true
        val failed = presenter.execute(
            request("browser.click", id = "uncertain", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, failed.errorCode)
        val state = presenter.state.value
        assertTrue(state.actionOutcomeUnknown)
        assertFalse("agentOn is not used to hide the read-only state", state.agentOn)
        assertTrue("the live grant is shown as read-only", state.agentReadOnly)
        assertSame(grant, presenter.registry.currentGrant())
        assertEquals(
            R.string.browser_action_outcome_unknown,
            browserFloatStatusResource(
                state.pageError, state.agentOn, state.pageReady, state.pageRetired, false, false,
                requiresReopen = state.requiresReopen, actionOutcomeUnknown = state.actionOutcomeUnknown,
                agentReadOnly = state.agentReadOnly,
            ),
        )
        assertEquals(
            "the window offers Take over",
            BrowserControlAction.TakeOver,
            browserControlAction(state.agentOn, false, canResume = true, agentReadOnly = state.agentReadOnly),
        )
        // The mutation gate is unchanged: reads only.
        assertTrue(presenter.execute(request("browser.snapshot", id = "read"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        assertTrue(presenter.execute(request("browser.capture", id = "cap"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        val actions = page.actions
        assertEquals(
            ProviderCodes.OUTCOME_UNKNOWN,
            presenter.execute(
                request("browser.click", id = "refused", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
                presenter.registry,
                ProviderMode.AGENT,
                null,
                5_000L,
            ) { 0L }.errorCode,
        )
        assertEquals(actions, page.actions)

        // Take over goes through the ordinary revoke.
        presenter.takeover()
        assertNull(presenter.registry.currentGrant())
        val after = presenter.state.value
        assertFalse("no grant, no read-only notice", after.agentReadOnly)
        assertTrue("the unconfirmed effect itself is still recorded", after.actionOutcomeUnknown)
        assertEquals(
            BrowserControlAction.Resume,
            browserControlAction(after.agentOn, false, canResume = true, agentReadOnly = after.agentReadOnly),
        )
        assertEquals(
            R.string.browser_float_status_manual,
            browserFloatStatusResource(
                after.pageError, after.agentOn, after.pageReady, after.pageRetired, false, false,
                requiresReopen = after.requiresReopen, actionOutcomeUnknown = after.actionOutcomeUnknown,
                agentReadOnly = after.agentReadOnly,
            ),
        )
        val reads = page.reads
        assertEquals(
            ProviderCodes.AUTHORIZATION_REQUIRED,
            presenter.execute(request("browser.snapshot", id = "after-takeover"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.errorCode,
        )
        assertEquals(
            ProviderCodes.AUTHORIZATION_REQUIRED,
            presenter.execute(request("browser.capture", id = "cap-after"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.errorCode,
        )
        assertEquals("no read reached the page after takeover", reads, page.reads)
    }

    @Test
    fun `the read-only projection needs the live grant of the shown page`() {
        fun ro(
            agentMode: Boolean = true,
            grantTab: String? = "t",
            grantConn: String? = "c",
            selected: String? = "t",
            conn: String? = "c",
            live: Boolean = true,
            recovery: PageRecovery = PageRecovery.OBSERVE_ONLY,
            blocked: Boolean = false,
            hidden: Boolean = false,
            takeover: Boolean = false,
        ) = browserAgentReadOnly(agentMode, grantTab, grantConn, selected, conn, live, recovery, blocked, hidden, takeover)
        assertTrue(ro())
        assertFalse(ro(agentMode = false))
        assertFalse(ro(grantTab = null))
        assertFalse(ro(grantTab = "other"))
        assertFalse(ro(grantConn = "old"))
        assertFalse(ro(conn = null))
        assertFalse(ro(live = false))
        assertFalse(ro(recovery = PageRecovery.NONE))
        assertFalse(ro(recovery = PageRecovery.REOPEN_REQUIRED))
        assertFalse(ro(blocked = true))
        assertFalse(ro(hidden = true))
        assertFalse(ro(takeover = true))
    }

    // ---- 3. Address / error published by the session ----

    @Test
    fun `a same-document address change and a policy refusal publish without another action`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val grant = presenter.registry.currentGrant()
        page.url = "https://example.com/login"
        page.report()
        assertEquals("https://example.com/login", presenter.state.value.displayUrl)
        val epoch = presenter.state.value.documentEpoch

        // SPA pushState: only the session's report runs.
        page.url = "https://example.com/inbox?folder=2"
        page.report()
        assertEquals("https://example.com/inbox?folder=2", presenter.state.value.displayUrl)
        assertEquals(
            "https://example.com/inbox?folder=2",
            browserShownAddress(presenter.state.value.displayUrl, presenter.state.value.tabs.single().url),
        )

        // A policy refusal: the page's own error appears at once.
        page.error = "This address isn't allowed"
        page.report()
        assertEquals("This address isn't allowed", presenter.state.value.pageError)

        // Clearing the error is published too.
        page.error = null
        page.report()
        assertNull(presenter.state.value.pageError)

        // None of this is authority or recovery.
        assertSame("a report never grants or revokes", grant, presenter.registry.currentGrant())
        assertEquals("a report is not a navigation", epoch, presenter.state.value.documentEpoch)
        assertFalse(presenter.state.value.actionOutcomeUnknown)
        assertFalse(presenter.state.value.requiresReopen)
    }

    @Test
    fun `a report from an old page, view or load never reaches the new page`() = runBlocking {
        val pages = mutableListOf<FakePage>()
        val presenter = presenter { FakePage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        val old = pages[0]
        old.url = "https://example.com/old"
        old.report()

        assertTrue(presenter.reloadManual().ok)
        val fresh = pages[1]
        fresh.url = "https://example.com/new"
        fresh.report()
        assertEquals("https://example.com/new", presenter.state.value.displayUrl)

        // The replaced page reports late: dropped. Its own state is not read for display either.
        old.url = "https://evil.example.com/late"
        old.error = "late error"
        val before = presenter.state.value
        old.report()
        assertSame("a replaced page's report publishes nothing", before, presenter.state.value)

        // Same page object, wrong view or older ticket: dropped.
        fresh.url = "https://example.com/stale"
        fresh.report(viewId = "another-view")
        fresh.report(ticket = fresh.ticket() - 1)
        assertEquals("https://example.com/new", presenter.state.value.displayUrl)

        // The current identity publishes.
        fresh.report()
        assertEquals("https://example.com/stale", presenter.state.value.displayUrl)
    }

    private fun presenter(pageFactory: (android.content.Context?) -> ControlledBrowserPage): NativeBrowserPresenter =
        NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-real" },
            scope = CoroutineScope(Dispatchers.Main),
            sessionFactory = pageFactory,
            main = Dispatchers.Main,
            clock = { 0L },
            awaitPageVisible = { it.nativePresentationVisible() },
        ).also { it.attachHostForTest("local", 1L) }

    private fun openRequest(id: String = "open-1", extra: Map<String, String> = emptyMap()) = ProviderRequest(
        serverEpoch = "e1",
        connectionId = "c1",
        requestId = id,
        action = "browser.open",
        parameters = mapOf("url" to "https://example.com/login") + extra,
        directory = "/proj",
        sessionId = "session-a",
        timeoutMs = 1_000L,
    )

    private fun request(
        action: String,
        id: String = "r1",
        parameters: Map<String, String> = emptyMap(),
        connection: String = "c1",
    ) = ProviderRequest("e1", connection, id, action, parameters, "/proj", "session-a", 1_000L)

    private companion object {
        const val REF = "abcdef01-1234abcd-1"
        var nextView = 0
    }

    private class FakePage : ControlledBrowserPage {
        private val id = "view-${++nextView}"
        override var tabId: String? = null
        override val view: View? = null
        override fun nativePresentationVisible(): Boolean = true
        override val handle: Int = System.identityHashCode(this)
        override val width: Int = 320
        override val height: Int = 240
        var error: String? = null
        override val pageError: String? get() = error
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
        override var onPageStatus: ((BrowserPageStatus) -> Unit)? = null
        var url: String? = null
        var destroyed = false
        var driverCalls = 0
        var reads = 0
        var actions = 0
        var clickFails = false
        var onLoad: (() -> Unit)? = null
        var loads = 0
        var keepManualCalls = 0

        override fun keepManualForControlledLoad(): Boolean {
            keepManualCalls += 1
            manualNavigation = true
            return true
        }

        /** Like the session: a Main-thread display report carrying this view and load identity. */
        fun report(viewId: String = id, ticket: Long = ticket()) {
            onPageStatus?.invoke(BrowserPageStatus(viewId, ticket, url, error))
        }

        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver { request ->
            driverCalls += 1
            if (request.method == BrowserMethod.SNAPSHOT || request.method == BrowserMethod.CAPTURE) reads += 1
            if (request.method == BrowserMethod.CLICK) {
                actions += 1
                if (clickFails) error("click outcome unknown")
            }
            BrowserReply(
                ok = true,
                result = mapOf(
                    BrowserDriverLogic.RESULT_SNAPSHOT to
                        """{"url":"https://example.com","title":"t","text":"t","elements":[{"ref":"$REF","role":"button","type":"","name":"Go"}]}""",
                    "width" to "320",
                    "height" to "240",
                    BrowserDriverLogic.RESULT_MIME to "image/png",
                    BrowserDriverLogic.RESULT_DATA to "iVBORw0KGgo=",
                    "cssWidth" to "320",
                    "cssHeight" to "240",
                    "visualWidth" to "320",
                    "visualHeight" to "240",
                    "dpr" to "1",
                    "scale" to "1",
                ),
            )
        }

        override suspend fun loadOnce(url: String): Boolean {
            loads += 1
            onLoad?.invoke()
            return true
        }
        override suspend fun probe(): ProbeAcceptance = ProbeAcceptance.Accept("witness-1")
        override suspend fun probe(identity: ReadyProbeIdentity): ProbeAcceptance? = ProbeAcceptance.Accept("witness-1")
        override fun acceptReady(ticket: Long, documentId: String): Boolean = true
        override fun matchesReady(identity: ReadyProbeIdentity): Boolean = identity.ticket == ticket() && identity.viewId == id
        override fun owns(viewId: String, ticket: Long): Boolean = true
        override fun markUnknown() = Unit
        override fun clearAcceptedWitness() = Unit
        override fun syncWitness(documentId: String) = Unit
        override suspend fun currentFields(): Map<String, PageField>? = mapOf(REF to PageField("button", "", "Go"))
        override fun acceptedDocument(): String? = "doc"
        override fun currentUrl(): String? = url
        override fun viewId(): String = id
        override fun ticket(): Long = 7L
        override fun refEpoch(): Long = 4L
        override fun destroy() {
            destroyed = true
        }
    }
}
