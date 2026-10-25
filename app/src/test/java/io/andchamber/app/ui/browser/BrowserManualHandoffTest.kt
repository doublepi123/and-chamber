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
 * A revoke that leaves the shown page without a grant hands that page to the person: the window says
 * "Manual control", so links and forms must work. A surviving grant, an explicit takeover, an open
 * replacement and a failed document keep their own rules. JVM fakes only, no WebView.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserManualHandoffTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Revoke paths that leave a live page ----

    @Test
    fun `hide then restore leaves a manual page and no grant`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        val page = pages.single()
        assertFalse("agent control keeps the agent's navigation rules", page.manualNavigation)

        presenter.hide()
        assertTrue("the page is the person's after hide", page.manualNavigation)
        assertNull(presenter.registry.currentGrant())

        assertTrue(presenter.restore())
        assertTrue("restoring presents only; the page stays manual", page.manualNavigation)
        assertNull("restoring never restores a grant", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertFalse("hide is not a takeover", presenter.state.value.explicitTakeover)
        assertEquals("the handoff is one marker, not a reload", 1, page.loads)
    }

    @Test
    fun `host pause then resume leaves a manual page and no grant`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        val page = pages.single()

        presenter.onHostPaused()
        assertTrue(page.manualNavigation)
        assertNull(presenter.registry.currentGrant())

        presenter.onHostResumed()
        assertTrue(page.manualNavigation)
        assertNull("resume never restores a grant", presenter.registry.currentGrant())
        assertFalse(presenter.state.value.agentOn)
        assertFalse(presenter.state.value.explicitTakeover)
    }

    @Test
    fun `an owner change revokes into a manual page`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)

        BrowserHostSurface(presenter).context("session-b")

        assertTrue(pages.single().manualNavigation)
        assertNull(presenter.registry.currentGrant())
    }

    @Test
    fun `a console document revoke and a connection replacement hand the page over`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        presenter.invalidateConsoleDocument("local")
        assertTrue("console document revoke", pages.single().manualNavigation)
        assertNull(presenter.registry.currentGrant())

        val other = mutableListOf<HandoffPage>()
        val second = presenter { HandoffPage().also { other += it } }
        openGranted(second)
        second.setConnection("c2", 5L)
        assertTrue("connection replacement", other.single().manualNavigation)
        assertNull(second.registry.currentGrant())
    }

    @Test
    fun `a revoke while the page is still loading marks the controlled load once`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        val page = pages.single()
        // No ready probe yet: the explicit open's default control is still pending.
        assertNull(presenter.registry.currentGrant())

        presenter.onHostPaused()
        assertTrue(page.manualNavigation)
        assertEquals("the pending controlled load is marked, not replaced", 1, page.keepManualCalls)
        assertEquals(1, page.loads)

        // A second revoke finds a manual page and does not touch the marker again.
        presenter.hide()
        presenter.restore()
        assertEquals(1, page.keepManualCalls)

        // The late ready probe of that load grants nothing: the revoke dropped its intent.
        presenter.onHostResumed()
        presenter.acceptProbeForTest(page, ReadyProbeIdentity(page.viewId(), page.ticket(), 4L))
        assertNull(presenter.registry.currentGrant())
        assertTrue(page.manualNavigation)
    }

    // ---- Paths that must not hand the page over ----

    @Test
    fun `successful agent control keeps the agent's navigation rules`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        val page = pages.single()
        val grant = presenter.registry.currentGrant()

        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        presenter.setPresentationBlocked(true)
        presenter.setPresentationBlocked(false)
        presenter.onPresentationChanged()
        presenter.onGeometrySettled()

        assertFalse(page.manualNavigation)
        assertEquals(0, page.keepManualCalls)
        assertSame("presentation changes revoke nothing", grant, presenter.registry.currentGrant())
        assertTrue(presenter.state.value.agentOn)
    }

    @Test
    fun `a read-only grant stays the agent's until it is revoked`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        val page = pages.single()
        val grant = presenter.registry.currentGrant()

        assertTrue(presenter.execute(request("browser.snapshot"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        page.clickFails = true
        val failed = presenter.execute(
            request("browser.click", id = "uncertain", parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]""")),
            presenter.registry, ProviderMode.AGENT, null, 5_000L,
        ) { 0L }
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, failed.errorCode)
        assertTrue(presenter.state.value.agentReadOnly)
        assertFalse("an unconfirmed action is not a handoff", page.manualNavigation)
        assertSame(grant, presenter.registry.currentGrant())
        // Reads under the surviving grant still work and do not hand the page over.
        assertTrue(presenter.execute(request("browser.snapshot", id = "read"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        assertTrue(presenter.execute(request("browser.capture", id = "cap"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        assertFalse(page.manualNavigation)
        assertSame(grant, presenter.registry.currentGrant())

        // The revoke removes the grant; only then is the page the person's. The uncertainty stays.
        presenter.hide()
        presenter.restore()
        assertNull(presenter.registry.currentGrant())
        assertTrue(page.manualNavigation)
        assertFalse(presenter.state.value.agentReadOnly)
        assertTrue(presenter.state.value.actionOutcomeUnknown)
        assertFalse("hide is not a takeover", presenter.state.value.explicitTakeover)

        // Resume re-verifies the document and gives navigation back to the agent.
        assertTrue(presenter.resumeAgent())
        assertNotNull(presenter.registry.currentGrant())
        assertFalse(page.manualNavigation)
    }

    @Test
    fun `an explicit takeover stays a latch through hide and restore`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        val page = pages.single()
        presenter.takeover()
        assertTrue(page.manualNavigation)
        assertEquals("takeover keeps its own marker", 0, page.keepManualCalls)

        presenter.hide()
        presenter.restore()
        assertTrue(page.manualNavigation)
        assertTrue("the latch is not cleared by hide or restore", presenter.state.value.explicitTakeover)
        assertNull(presenter.registry.currentGrant())
        val refused = presenter.execute(request("browser.snapshot", id = "after"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }
        assertEquals(ProviderCodes.AUTHORIZATION_REQUIRED, refused.errorCode)

        assertTrue(presenter.resumeAgent())
        assertFalse(presenter.state.value.explicitTakeover)
        assertFalse(page.manualNavigation)
    }

    @Test
    fun `an agent action that wakes a hidden window gets the agent's rules back`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        val presenter = presenter { HandoffPage().also { pages += it } }
        openGranted(presenter)
        val page = pages.single()
        presenter.hide()
        assertTrue(page.manualNavigation)

        val woke = presenter.execute(request("browser.snapshot", id = "wake"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }

        assertTrue(woke.errorCode ?: "ok", woke.ok)
        assertNotNull(presenter.registry.currentGrant())
        assertFalse("a granted wake hands navigation back", page.manualNavigation)
        assertFalse(presenter.state.value.explicitTakeover)
    }

    @Test
    fun `an open replacement does not mark the old page before the new one commits`() = runBlocking {
        val pages = mutableListOf<HandoffPage>()
        var oldManualAtNewLoad: Boolean? = null
        lateinit var presenter: NativeBrowserPresenter
        presenter = presenter {
            HandoffPage().also { created ->
                if (pages.isNotEmpty()) created.onLoad = { oldManualAtNewLoad = pages[0].manualNavigation }
                pages += created
            }
        }
        openGranted(presenter)

        assertTrue(presenter.reloadManual().ok)

        // The open already revoked the old grant with its own request kept alive.
        assertEquals("the old page was not handed over by the open's revoke", false, oldManualAtNewLoad)
        val fresh = pages[1]
        assertFalse(fresh.manualNavigation)
        assertEquals(0, fresh.keepManualCalls)
        assertEquals(0, pages[0].keepManualCalls)
        presenter.acceptProbeForTest(fresh, ReadyProbeIdentity(fresh.viewId(), fresh.ticket(), 4L))
        assertNotNull("the replacement still gets its default control", presenter.registry.currentGrant())
        assertFalse(fresh.manualNavigation)
    }

    @Test
    fun `the reasons that keep the agent's rules`() {
        for (reason in listOf("hidden", "host paused", "session-changed", "console document invalidated",
            "console document changed", "connection replaced", "connection closed", "binding revoke",
            "tab selected", "tab closed", "cancelled", "site-data-cleared", "host left")) {
            assertTrue(reason, browserRevokeHandsPageToPerson(reason, keepRequestId = null))
        }
        for (reason in listOf("open", "takeover", "fatal", "unknown navigation", "desktop mode refused")) {
            assertFalse(reason, browserRevokeHandsPageToPerson(reason, keepRequestId = null))
        }
        // Whoever keeps its own request alive decides the page's mode itself.
        assertFalse(browserRevokeHandsPageToPerson("hidden", keepRequestId = "r1"))
    }

    // ---- Helpers ----

    private suspend fun openGranted(presenter: NativeBrowserPresenter) {
        presenter.setConnection("c1", 4L)
        assertTrue(presenter.openClaimed(openRequest(), 1_000L).ok)
        assertTrue(presenter.allow())
        assertNotNull(presenter.registry.currentGrant())
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

    private fun openRequest(id: String = "open-1") = ProviderRequest(
        serverEpoch = "e1",
        connectionId = "c1",
        requestId = id,
        action = "browser.open",
        parameters = mapOf("url" to "https://example.com/login"),
        directory = "/proj",
        sessionId = "session-a",
        timeoutMs = 1_000L,
    )

    private fun request(
        action: String,
        id: String = "r1",
        parameters: Map<String, String> = emptyMap(),
    ) = ProviderRequest("e1", "c1", id, action, parameters, "/proj", "session-a", 1_000L)

    private companion object {
        const val REF = "abcdef01-1234abcd-1"
        var nextView = 0
    }

    private class HandoffPage : ControlledBrowserPage {
        private val id = "view-${++nextView}"
        override var tabId: String? = null
        override val view: View? = null
        override fun nativePresentationVisible(): Boolean = true
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
        override var onGeometry: ((GeometrySample) -> Unit)? = null
        override var onGeometryRequested: (() -> Unit)? = null
        var clickFails = false
        var onLoad: (() -> Unit)? = null
        var loads = 0
        var keepManualCalls = 0

        override fun keepManualForControlledLoad(): Boolean {
            keepManualCalls += 1
            manualNavigation = true
            return true
        }

        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver { request ->
            if (request.method == BrowserMethod.CLICK && clickFails) error("click outcome unknown")
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
        override fun viewId(): String = id
        override fun ticket(): Long = 7L
        override fun refEpoch(): Long = 4L
        override fun destroy() = Unit
    }
}
