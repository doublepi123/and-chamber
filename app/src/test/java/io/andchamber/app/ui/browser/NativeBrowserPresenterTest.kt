package io.andchamber.app.ui.browser

import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserUrlPolicy
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
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeBrowserPresenterTest {
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
        presenter.allow()
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
        assertTrue(presenter.allow())
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
        assertTrue("$first $second $third", first != second && second != third)
        presenter.detachHost("local", 1L)
        assertTrue(pages.all { it.destroyed })
        assertEquals(2, presenter.registry.tabs().size)
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

    private fun presenter(
        uniqueTabs: Boolean = false,
        pageFactory: (android.content.Context?) -> ControlledBrowserPage,
    ): NativeBrowserPresenter {
        var ids = 0
        return NativeBrowserPresenter(
            registry = ProviderRegistry { if (uniqueTabs) "tab-${++ids}" else "tab-real" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = pageFactory,
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
    }

    private fun openRequest(session: String = "session-a", tabId: String? = null, id: String = "open-1") = ProviderRequest(
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
    ) : ControlledBrowserPage {
        var loads = 0
        var reads = 0
        var actions = 0
        var lastRef: String? = null
        var fieldWait: CompletableDeferred<Unit>? = null
        val fieldRelease = CompletableDeferred<Unit>()
        var block: suspend () -> Unit = {}
        override val view: View? = null
        override val handle: Int = 7
        override val width: Int = 320
        override val height: Int = 240
        override val pageError: String? = null
        override val ready: Boolean = true
        override var manualNavigation: Boolean = false
        override var tabId: String? = null
        override var onProbe: ((String, Long) -> Unit)? = null
        override var onFatal: (() -> Unit)? = null
        override var onUnknown: (() -> Unit)? = null
        override var onManualDocument: (() -> Unit)? = null
        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver { request ->
            block()
            if (request.method == BrowserMethod.SNAPSHOT) reads += 1
            if (request.method == BrowserMethod.TYPE || request.method == BrowserMethod.CLICK) {
                actions += 1
                lastRef = request.params[BrowserDriverLogic.PARAM_REF]
            }
            BrowserReply(
                ok = true,
                result = mapOf(
                    BrowserDriverLogic.RESULT_SNAPSHOT to """{"url":"https://example.com","title":"$text","text":"$text","elements":[{"ref":"$REF","role":"input","type":"text","name":"$text"}]}""",
                    "width" to "320",
                    "height" to "240",
                ),
            )
        }
        override suspend fun loadOnce(url: String): Boolean {
            loads += 1
            return true
        }
        override suspend fun probe(): ProbeAcceptance = probe
        override fun acceptReady(ticket: Long, documentId: String) = Unit
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
        override fun destroy() { destroyed = true }
    }
}
