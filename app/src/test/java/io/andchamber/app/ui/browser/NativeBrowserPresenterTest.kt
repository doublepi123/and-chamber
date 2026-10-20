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
        val page = FakePage(fields = mapOf(REF to PageField("input", "password", "Password")))
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
        assertEquals(ProviderCodes.UNSUPPORTED_PARAMETER, typed.errorCode)
    }

    @Test
    fun `select does not navigate and close does not recreate`() = runBlocking {
        val page = FakePage()
        val presenter = presenter { page }
        presenter.openClaimed(openRequest(), 1_000L)
        val first = presenter.registry.tabs().single().tabId
        presenter.registry.open("session-a", "/proj", "local", "https://example.com/next", "tab-next")
        assertTrue(presenter.select("tab-next"))
        assertEquals(1, page.loads)
        assertEquals("tab-next", presenter.state.value.selectedTabId)
        assertTrue(presenter.close(first))
        assertEquals(1, presenter.registry.tabs().size)
        assertEquals(1, page.loads)
    }

    private fun presenter(pageFactory: (android.content.Context?) -> ControlledBrowserPage): NativeBrowserPresenter {
        return NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-real" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = pageFactory,
            main = dispatcher,
        ).also { it.attachHostForTest("local", 1L) }
    }

    private fun openRequest(session: String = "session-a", tabId: String? = null) = ProviderRequest(
        serverEpoch = "e1",
        connectionId = "c1",
        requestId = "open-1",
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
    ) = ProviderRequest(
        "e1", "c1", id, action, parameters, "/proj", "session-a", 1_000L,
    )

    private class FakePage(
        private val probe: ProbeAcceptance = ProbeAcceptance.Accept("witness-1"),
        private val fields: Map<String, PageField> = mapOf(REF to PageField("input", "text", "Note")),
    ) : ControlledBrowserPage {
        var loads = 0
        var reads = 0
        var actions = 0
        var lastRef: String? = null
        var block: suspend () -> Unit = {}
        override val view: View? = null
        override val handle: Int = 7
        override val width: Int = 320
        override val height: Int = 240
        override val pageError: String? = null
        override val ready: Boolean = true
        override var manualNavigation: Boolean = false
        override var onProbe: ((String, Long) -> Unit)? = null
        override var onFatal: (() -> Unit)? = null
        override var onUnknown: (() -> Unit)? = null
        override var onManualDocument: (() -> Unit)? = null
        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver { request ->
            runBlocking { block() }
            if (request.method == BrowserMethod.SNAPSHOT) reads += 1
            if (request.method == BrowserMethod.TYPE || request.method == BrowserMethod.CLICK) {
                actions += 1
                lastRef = request.params[BrowserDriverLogic.PARAM_REF]
            }
            BrowserReply(
                ok = true,
                result = mapOf(
                    BrowserDriverLogic.RESULT_SNAPSHOT to """{"url":"https://example.com","title":"Hi","text":"hello","elements":[{"ref":"$REF","role":"input","type":"text","name":"Note"}]}""",
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
        override suspend fun currentFields(): Map<String, PageField>? = fields
        override fun destroy() = Unit
    }
}
