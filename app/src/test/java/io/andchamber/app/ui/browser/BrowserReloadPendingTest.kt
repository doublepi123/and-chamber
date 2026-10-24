package io.andchamber.app.ui.browser

import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.DnsResolver
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The same-view reload pending barrier. A controlled reload keeps the still-displayed previous
 * document out of both ready-probe doors for the whole new ticket, then still ignores late probes
 * from that old document after the new witness is accepted, while a third witness stays strict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserReloadPendingTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- pure production probe rule ---------------------------------------------------------

    private val old = "a".repeat(32)
    private val new = "b".repeat(32)
    private val third = "c".repeat(32)

    private fun raw(id: String) = """{"ok":true,"documentId":"$id","readyState":"complete"}"""

    @Test
    fun `old witness on a new ticket is pending not accepted`() {
        assertEquals(
            ProbeAcceptance.Pending,
            BrowserDriverLogic.acceptProbe(null, raw(old), true, allowSameDocument = true, pendingOldDocumentId = old),
        )
    }

    @Test
    fun `the first non-old witness is the new document`() {
        assertEquals(
            ProbeAcceptance.Accept(new),
            BrowserDriverLogic.acceptProbe(null, raw(new), true, allowSameDocument = true, pendingOldDocumentId = old),
        )
    }

    @Test
    fun `late old witness after the new document is accepted stays ignored`() {
        assertEquals(
            ProbeAcceptance.Pending,
            BrowserDriverLogic.acceptProbe(new, raw(old), true, allowSameDocument = true, pendingOldDocumentId = old),
        )
        // A repeat probe of the accepted new document remains a normal accept.
        assertEquals(
            ProbeAcceptance.Accept(new),
            BrowserDriverLogic.acceptProbe(new, raw(new), true, allowSameDocument = true, pendingOldDocumentId = old),
        )
    }

    @Test
    fun `a third witness after the new document is still rejected`() {
        assertEquals(
            ProbeAcceptance.Reject("document changed"),
            BrowserDriverLogic.acceptProbe(new, raw(third), true, allowSameDocument = true, pendingOldDocumentId = old),
        )
    }

    @Test
    fun `without a pending barrier an initial document is accepted`() {
        assertEquals(
            ProbeAcceptance.Accept(old),
            BrowserDriverLogic.acceptProbe(null, raw(old), true, allowSameDocument = true),
        )
    }

    // ---- pure reload-start rule -------------------------------------------------------------

    @Test
    fun `a reload needs a validated previous witness`() {
        assertFalse(BrowserDriverLogic.canStartControlledReload(true, 1L, null, null, 0L, null))
        assertFalse(BrowserDriverLogic.canStartControlledReload(true, 1L, "a", null, 0L, null))
        assertTrue(BrowserDriverLogic.canStartControlledReload(true, 1L, "a", "a", 0L, null))
    }

    @Test
    fun `a pending first reload blocks an overlapping reload of the same ticket`() {
        assertFalse(
            BrowserDriverLogic.canStartControlledReload(
                controlledGet = true,
                ticket = 2L,
                acceptedDocumentId = null,
                previousDocumentId = old,
                pendingTicket = 2L,
                pendingOldDocumentId = old,
            ),
        )
        // Once the new document is accepted, a later mode change may reload from that witness.
        assertTrue(
            BrowserDriverLogic.canStartControlledReload(
                controlledGet = true,
                ticket = 2L,
                acceptedDocumentId = new,
                previousDocumentId = new,
                pendingTicket = 2L,
                pendingOldDocumentId = old,
            ),
        )
    }

    // ---- actual BrowserSession doors -------------------------------------------------------

    @Test
    fun `session accepts the new witness and refuses the old one through the final door`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        session.acceptedDocumentId = new
        setPending(session, ticket = session.ticket, old = old)

        assertEquals(old, session.pendingReloadOldDocument(session.ticket))
        assertTrue(session.isPendingReloadWitness(session.ticket, old))
        assertFalse("the previous document cannot be accepted", session.acceptReady(session.ticket, old))
        assertTrue("the new witness is accepted", session.acceptReady(session.ticket, new))
        assertFalse("a late old witness is still refused", session.acceptReady(session.ticket, old))
        assertEquals(new, session.acceptedDocumentId)
        assertFalse(session.unknownNavigation)
    }

    @Test
    fun `session still refuses a third witness after the new document`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        session.acceptedDocumentId = new
        setPending(session, ticket = session.ticket, old = old)

        assertFalse(session.acceptReady(session.ticket, third))
        assertTrue("a different document is a real mismatch", session.unknownNavigation)
    }

    @Test
    fun `session reload start refuses a pending first reload and allows the accepted successor`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        setPending(session, ticket = session.ticket, old = old)

        assertFalse("no accepted document yet", session.canStartControlledReload(old))
        session.acceptedDocumentId = new
        assertTrue("the accepted new document can start another reload", session.canStartControlledReload(new))
        assertFalse("an unvalidated previous witness is refused", session.canStartControlledReload(null))
    }

    @Test
    fun `configure runs only after the new ticket barrier is installed`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        session.acceptedDocumentId = old
        var observedTicket = 0L
        var barrierInstalled = false
        var acceptedCleared = false
        assertTrue(
            session.prepareControlledReload("https://example.com/search", old) {
                observedTicket = session.ticket
                barrierInstalled = session.isPendingReloadWitness(session.ticket, old)
                acceptedCleared = session.acceptedDocumentId == null
            },
        )
        assertEquals("the prepare created the new ticket before configure", 2L, observedTicket)
        assertTrue("the previous witness is already excluded inside configure", barrierInstalled)
        assertTrue("the accepted witness is already cleared inside configure", acceptedCleared)
        assertEquals(old, session.pendingReloadOldDocument(session.ticket))
    }

    @Test
    fun `a pending first reload refuses a second prepare before configure`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        session.acceptedDocumentId = old
        assertTrue(session.prepareControlledReload("https://example.com/search", old))
        var secondConfigured = false
        assertFalse(
            session.prepareControlledReload("https://example.com/search", old) {
                secondConfigured = true
            },
        )
        assertFalse("the rejected second prepare runs no configuration", secondConfigured)
    }

    // ---- production presenter final-accept door and mode refusal ---------------------------

    @Test
    fun `presenter final door ignores an old witness without changing the new grant`() = runBlocking {
        val page = FakePage()
        val presenter = presenter(page)
        openAndGrant(presenter)
        val grant = presenter.registry.currentGrant()

        page.documentId = new
        page.probeWitness = old
        page.pendingOld = old
        presenter.acceptProbeForTest(page, ReadyId)

        assertEquals("the final door must not call acceptReady", 0, page.acceptReadyCalls)
        assertEquals("it must not mark the page unknown", 0, page.unknownCalls)
        assertSame("the new grant is untouched", grant, presenter.registry.currentGrant())
        assertEquals(new, page.documentId)
    }

    @Test
    fun `presenter final door accepts a genuinely new witness`() = runBlocking {
        val page = FakePage()
        val presenter = presenter(page)
        openAndGrant(presenter)
        page.documentId = null
        page.probeWitness = new
        page.pendingOld = old
        presenter.acceptProbeForTest(page, ReadyId)
        assertEquals(1, page.acceptReadyCalls)
        assertEquals(new, page.documentId)
        assertEquals(0, page.unknownCalls)
    }

    @Test
    fun `an initial no-witness pending probe neither grants nor marks unknown`() = runBlocking {
        val page = FakePage()
        val presenter = presenter(page)
        openAndGrant(presenter)
        val grant = presenter.registry.currentGrant()

        page.documentId = null
        page.probeOverride = ProbeAcceptance.Pending
        presenter.acceptProbeForTest(page, ReadyId)

        assertEquals("a pending probe is not an acceptance", 0, page.acceptReadyCalls)
        assertEquals("it must not mark the page unknown", 0, page.unknownCalls)
        assertSame("it does not mint or replace a grant", grant, presenter.registry.currentGrant())
    }

    @Test
    fun `a pending reload refuses a second mode change without applying it`() = runBlocking {
        val page = FakePage()
        val presenter = presenter(page)
        openAndGrant(presenter)
        page.canReload = false

        assertEquals(ModeSwitch.Unchanged, presenter.setDesktopMode(true))
        assertFalse("configuration must not change", page.desktop)
        assertFalse("the tab mode must not be recorded", presenter.state.value.desktopMode)
        assertEquals(
            "the tab size must not be recorded",
            BrowserDesktopViewport.DEFAULT_WIDTH,
            presenter.state.value.desktopWidth,
        )
        assertEquals(0, page.reloadCalls)

        val resize = presenter.execute(
            ProviderRequest(
                serverEpoch = "e1",
                connectionId = "c1",
                requestId = "resize-pending",
                action = "browser.resize",
                parameters = mapOf("viewport" to "desktop"),
                directory = "/proj",
                sessionId = "session-a",
                timeoutMs = 5_000L,
            ),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertEquals("an unapplied mode change is busy, never a false applied", ProviderCodes.BUSY, resize.errorCode)
        assertFalse(page.desktop)
        assertEquals(0, page.reloadCalls)
    }

    private suspend fun openAndGrant(presenter: NativeBrowserPresenter) {
        val opened = presenter.execute(
            ProviderRequest(
                serverEpoch = "e1",
                connectionId = "c1",
                requestId = "open-1",
                action = "browser.open",
                parameters = mapOf("url" to "https://example.com/login"),
                directory = "/proj",
                sessionId = "session-a",
                timeoutMs = 5_000L,
            ),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertTrue("open ${opened.errorCode}", opened.ok)
        assertTrue("grant", presenter.allow())
    }

    private fun presenter(page: FakePage): NativeBrowserPresenter {
        val policy = BrowserUrlPolicy(
            resolver = DnsResolver { listOf(java.net.InetAddress.getByName("2001:db8::1")) },
        )
        return NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-real" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { page },
            main = dispatcher,
            clock = { 0L },
            policy = { policy },
        ).also {
            it.attachHostForTest("local", 1L)
            it.setConnection("c1", 1L)
        }
    }

    private fun newSession(): BrowserSession {
        val constructor = BrowserSession::class.java.getDeclaredConstructor(
            android.webkit.WebView::class.java,
            kotlin.jvm.functions.Function0::class.java,
            PixelCopyBridge::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(null, { BrowserUrlPolicy() }, PixelCopyBridge()) as BrowserSession
    }

    private fun setPending(session: BrowserSession, ticket: Long, old: String) {
        val pendingClass = Class.forName("io.andchamber.app.ui.browser.BrowserSession\$PendingReload")
        val constructor = pendingClass.getDeclaredConstructor(Long::class.javaPrimitiveType, String::class.java)
        constructor.isAccessible = true
        val pending = constructor.newInstance(ticket, old)
        val field = BrowserSession::class.java.getDeclaredField("pendingReload")
        field.isAccessible = true
        field.set(session, pending)
    }

    private companion object {
        val ReadyId = ReadyProbeIdentity("view", 1L, 4L)
    }

    private class FakePage : ControlledBrowserPage {
        override var tabId: String? = null
        override val view: View? = null
        override fun nativePresentationVisible(): Boolean = true
        override val handle: Int = System.identityHashCode(this)
        override val width: Int = 320
        override val height: Int = 240
        override val pageError: String? = null
        override val ready: Boolean get() = documentId != null
        override var manualNavigation: Boolean = false
        override var onProbe: ((String, Long) -> Unit)? = null
        override var onReadyProbe: ((ReadyProbeIdentity) -> Unit)? = null
        override var onFatal: (() -> Unit)? = null
        override var onUnknown: (() -> Unit)? = null
        override var onManualDocument: (() -> Unit)? = null
        override var onGeometry: ((GeometrySample) -> Unit)? = null
        override var onGeometryRequested: (() -> Unit)? = null

        var documentId: String? = "doc"
        var probeWitness: String? = null
        var probeOverride: ProbeAcceptance? = null
        var pendingOld: String? = null
        var acceptReadyCalls = 0
        var unknownCalls = 0
        var reloadCalls = 0
        var canReload = true
        var desktop = false

        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver {
            BrowserReply(ok = true)
        }
        override suspend fun loadOnce(url: String): Boolean = true
        override suspend fun probe(): ProbeAcceptance =
            probeOverride ?: ProbeAcceptance.Accept(probeWitness ?: documentId ?: "doc")
        override suspend fun probe(identity: ReadyProbeIdentity): ProbeAcceptance? = probe()
        override fun acceptReady(ticket: Long, documentId: String): Boolean {
            acceptReadyCalls += 1
            this.documentId = documentId
            return true
        }
        override fun matchesReady(identity: ReadyProbeIdentity): Boolean = true
        override fun owns(viewId: String, ticket: Long): Boolean = true
        override fun markUnknown() { unknownCalls += 1 }
        override fun clearAcceptedWitness() { documentId = null }
        override fun syncWitness(documentId: String) { this.documentId = documentId }
        override suspend fun currentFields(): Map<String, PageField> = emptyMap()
        override fun acceptedDocument(): String? = documentId
        override fun currentUrl(): String? = "https://example.com/search"
        override fun setDesktopMode(enabled: Boolean) { desktop = enabled }
        override fun desktopConfigured(): Boolean = desktop
        override fun controlledGetProvenance(): Boolean = true
        override fun reloadGet(previousDocument: String?, configure: () -> Unit): Boolean {
            if (!canReload || previousDocument == null) return false
            configure()
            reloadCalls += 1
            return true
        }
        override fun pendingReloadWitness(ticket: Long, documentId: String): Boolean =
            pendingOld != null && pendingOld == documentId
        override fun viewId(): String = "view"
        override fun ticket(): Long = 1L
        override fun finishSubmitNavigation() = Unit
        override fun destroy() = Unit
    }
}
