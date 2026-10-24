package io.andchamber.app.ui.browser

import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserMethod
import io.andchamber.app.browser.BrowserReply
import io.andchamber.app.browser.BrowserRequest
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.DnsResolver
import io.andchamber.app.browser.provider.PageField
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused regression tests for the narrow action-navigation receipt: an ordinary same-tab https
 * `<a href>` click is continued only from the engine's native GET receipt, with the exact bound
 * token and target. No WebView is created here; the fake page owns the callback surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserClickNavigationTest {
    private companion object {
        const val REF = "abcdef01-1234abcd-1"
        const val TARGET = "https://example.com/next"
        const val OTHER = "https://example.com/other"
    }

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
    fun `a link click with no receipt returns unknown once and never replays`() = runBlocking {
        val harness = harness()
        val result = harness.click(timeoutMs = 300L)
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals("exactly one synthetic click was dispatched", 1, harness.page.clickCalls)
        assertEquals("the original page is not replaced without a receipt", 1, harness.pages.size)
    }

    @Test
    fun `a click event with a different token cannot continue`() = runBlocking {
        val harness = harness()
        val pending = async { harness.click(timeoutMs = 300L) }
        awaitCondition { harness.page.navigationArmed }
        harness.page.onSubmitNavigation?.invoke(
            BrowserSubmitNavigation.Get(TARGET, "not-the-token", BrowserActionKind.CLICK),
        )
        val result = pending.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals(1, harness.pages.size)
    }

    @Test
    fun `a click GET with a different target is not continued`() = runBlocking {
        val harness = harness()
        val pending = async { harness.click(timeoutMs = 2_000L) }
        awaitCondition { harness.page.navigationArmed }
        harness.page.onSubmitNavigation?.invoke(
            BrowserSubmitNavigation.Get(OTHER, harness.page.clickToken, BrowserActionKind.CLICK),
        )
        val result = pending.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals("a wrong target never creates a replacement", 1, harness.pages.size)
    }

    @Test
    fun `a receipt that arrives before the click ACK is still bound by token`() = runBlocking {
        val harness = harness()
        // The fake driver emits the native GET during the same dispatch, before it returns the ACK.
        harness.page.emitDuringClick =
            BrowserSubmitNavigation.Get(TARGET, harness.page.clickToken, BrowserActionKind.CLICK)
        val pending = async { harness.click(timeoutMs = 5_000L) }
        // Wait for the replacement to be created: proves the early event passed token/target checks.
        awaitCondition(timeoutMs = 3_000L) { harness.pages.size == 2 }
        harness.allowSelected()
        val result = pending.await()
        assertTrue(result.errorCode ?: "ok", result.ok)
        assertEquals("the clicked ref is preserved", REF, result.data["clicked"]?.jsonPrimitive?.contentOrNull.orEmpty().substringAfter("ref=\"", "").substringBefore('"'))
        assertEquals(TARGET, result.data["finalURL"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, result.data["drivable"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, result.data["requiresSnapshot"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("never claims the Enter input semantics", null, result.data["enterDispatched"])
        assertEquals(1, harness.page.clickCalls)
    }

    @Test
    fun `a receipt that arrives after the ACK continues once`() = runBlocking {
        val harness = harness()
        val pending = async { harness.click(timeoutMs = 5_000L) }
        awaitCondition { harness.page.navigationArmed }
        harness.page.onSubmitNavigation?.invoke(
            BrowserSubmitNavigation.Get(TARGET, harness.page.clickToken, BrowserActionKind.CLICK),
        )
        awaitCondition(timeoutMs = 3_000L) { harness.pages.size == 2 }
        harness.allowSelected()
        val result = pending.await()
        assertTrue(result.errorCode ?: "ok", result.ok)
        assertEquals(1, harness.page.clickCalls)
    }

    @Test
    fun `a click target refused by policy is uncertain and never continued`() = runBlocking {
        val harness = harness(targetUrl = "https://localhost/next")
        val pending = async { harness.click(timeoutMs = 2_000L) }
        awaitCondition { harness.page.navigationArmed }
        harness.page.onSubmitNavigation?.invoke(
            BrowserSubmitNavigation.Get("https://localhost/next", harness.page.clickToken, BrowserActionKind.CLICK),
        )
        val result = pending.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals("a refused target never creates a replacement", 1, harness.pages.size)
        assertEquals(1, harness.page.clickCalls)
    }

    @Test
    fun `a DOM control click is acknowledged without waiting for a navigation`() = runBlocking {
        val harness = harness(navigationExpected = false)
        val started = System.currentTimeMillis()
        val result = harness.click(timeoutMs = 5_000L)
        assertTrue(result.errorCode ?: "ok", result.ok)
        assertTrue("a DOM click returns from its own ACK", System.currentTimeMillis() - started < 2_000L)
        assertEquals(false, result.data[BrowserDriverLogic.RESULT_NAVIGATION_EXPECTED]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, harness.page.clickCalls)
        assertEquals("the arm is released", 1, harness.page.disarmed)
        assertEquals(1, harness.pages.size)
    }

    @Test
    fun `a click event without an active action is ignored`() = runBlocking {
        val harness = harness()
        val grant = harness.presenter.registry.currentGrant()
        // No action receipt exists here. The callback must not grant, replace or throw.
        harness.page.onSubmitNavigation?.invoke(
            BrowserSubmitNavigation.Get(TARGET, "stale", BrowserActionKind.CLICK),
        )
        assertSame(grant, harness.presenter.registry.currentGrant())
        assertEquals(1, harness.pages.size)
        assertTrue(harness.presenter.execute(harness.openRequest("open-2"), harness.presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
    }

    @Test
    fun `an Enter receipt rejects a click kind and keeps its own semantics`() = runBlocking {
        val harness = harness()
        val pending = async { harness.submit(timeoutMs = 300L) }
        awaitCondition { harness.page.navigationArmed }
        // Same token, but the armed action is ENTER: a CLICK-kind event can never confirm it.
        harness.page.onSubmitNavigation?.invoke(
            BrowserSubmitNavigation.Get(TARGET, harness.page.enterToken, BrowserActionKind.CLICK),
        )
        val result = pending.await()
        assertEquals(ProviderCodes.OUTCOME_UNKNOWN, result.errorCode)
        assertEquals(1, harness.pages.size)
    }

    private suspend fun awaitCondition(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("condition timed out")
            yield()
            delay(5)
        }
    }

    private class Harness(
        val presenter: NativeBrowserPresenter,
        val pages: MutableList<FakePage>,
        val policy: BrowserUrlPolicy,
    ) {
        val page: FakePage get() = pages.first()

        suspend fun click(timeoutMs: Long): ProviderResult = presenter.execute(
            ProviderRequest(
                serverEpoch = "e1",
                connectionId = "c1",
                requestId = "click-1",
                action = "browser.click",
                parameters = mapOf("selector" to """[data-andchamber-ref="$REF"]"""),
                directory = "/proj",
                sessionId = "session-a",
                timeoutMs = timeoutMs,
            ),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            timeoutMs,
        ) { 0L }

        suspend fun submit(timeoutMs: Long): ProviderResult = presenter.execute(
            ProviderRequest(
                serverEpoch = "e1",
                connectionId = "c1",
                requestId = "submit-1",
                action = "browser.type",
                parameters = mapOf(
                    "selector" to """[data-andchamber-ref="$REF"]""",
                    "value" to "android",
                    "submit" to "true",
                ),
                directory = "/proj",
                sessionId = "session-a",
                timeoutMs = timeoutMs,
            ),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            timeoutMs,
        ) { 0L }

        suspend fun allowSelected() {
            assertTrue("the replacement page must be grantable", presenter.allow())
        }

        fun openRequest(id: String) = ProviderRequest(
            serverEpoch = "e1",
            connectionId = "c1",
            requestId = id,
            action = "browser.open",
            parameters = mapOf("url" to "https://example.com/login"),
            directory = "/proj",
            sessionId = "session-a",
            timeoutMs = 1_000L,
        )
    }

    private suspend fun harness(navigationExpected: Boolean = true, targetUrl: String = TARGET): Harness {
        val pages = mutableListOf<FakePage>()
        val policy = BrowserUrlPolicy(
            resolver = DnsResolver { listOf(java.net.InetAddress.getByName("2001:db8::1")) },
        )
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry { "tab-real" },
            scope = CoroutineScope(dispatcher),
            sessionFactory = { FakePage(navigationExpected, targetUrl).also { pages += it } },
            main = dispatcher,
            clock = { 0L },
            policy = { policy },
        ).also { it.attachHostForTest("local", 1L) }
        val harness = Harness(presenter, pages, policy)
        presenter.setConnection("c1", 1L)
        assertTrue("open", presenter.execute(harness.openRequest("open-1"), presenter.registry, ProviderMode.AGENT, null, 5_000L) { 0L }.ok)
        assertTrue("default grant", presenter.allow())
        val snap = presenter.execute(
            ProviderRequest(
                serverEpoch = "e1",
                connectionId = "c1",
                requestId = "snap-1",
                action = "browser.snapshot",
                parameters = emptyMap(),
                directory = "/proj",
                sessionId = "session-a",
                timeoutMs = 5_000L,
            ),
            presenter.registry,
            ProviderMode.AGENT,
            null,
            5_000L,
        ) { 0L }
        assertTrue("snapshot ${snap.errorCode}", snap.ok)
        return harness
    }

    private class FakePage(
        private val navigationExpected: Boolean,
        private val expectedTarget: String,
    ) : ControlledBrowserPage {
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
        override var onSubmitNavigation: ((BrowserSubmitNavigation) -> Unit)? = null
        override var onGeometry: ((GeometrySample) -> Unit)? = null
        override var onGeometryRequested: (() -> Unit)? = null

        var documentId: String? = "doc"
        var currentUrlValue: String? = "https://example.com/results"
        var clickCalls = 0
        var disarmed = 0
        var navigationArmed = false
        var emitDuringClick: BrowserSubmitNavigation? = null
        val clickToken = "click-token"
        val enterToken = "enter-token"

        override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserDriver { request ->
            when (request.method) {
                BrowserMethod.SNAPSHOT -> BrowserReply(
                    ok = true,
                    result = mapOf(
                        BrowserDriverLogic.RESULT_SNAPSHOT to
                            """{"url":"https://example.com/results","title":"Results","text":"body","elements":[{"ref":"$REF","role":"link","name":"Android"}]}""",
                    ),
                )
                BrowserMethod.CLICK -> {
                    clickCalls += 1
                    navigationArmed = true
                    emitDuringClick?.let { onSubmitNavigation?.invoke(it) }
                    BrowserReply(
                        ok = true,
                        result = mapOf(
                            BrowserDriverLogic.RESULT_CLICK_DISPATCHED to "true",
                            BrowserDriverLogic.RESULT_NAVIGATION_EXPECTED to navigationExpected.toString(),
                            BrowserDriverLogic.RESULT_NAVIGATION_TOKEN to clickToken,
                            BrowserDriverLogic.RESULT_EXPECTED_URL to expectedTarget,
                        ),
                    )
                }
                BrowserMethod.TYPE -> {
                    navigationArmed = true
                    BrowserReply(
                        ok = true,
                        result = mapOf(
                            BrowserDriverLogic.RESULT_ENTER_DISPATCHED to "true",
                            BrowserDriverLogic.RESULT_NAVIGATION_TOKEN to enterToken,
                        ),
                    )
                }
                else -> BrowserReply(ok = true)
            }
        }

        override suspend fun loadOnce(url: String): Boolean {
            currentUrlValue = url
            if (documentId == null) documentId = "doc-${System.identityHashCode(this)}"
            return true
        }
        override suspend fun probe(): ProbeAcceptance = ProbeAcceptance.Accept(documentId ?: "doc")
        override fun acceptReady(ticket: Long, documentId: String): Boolean {
            this.documentId = documentId
            return true
        }
        override fun matchesReady(identity: ReadyProbeIdentity): Boolean = true
        override fun owns(viewId: String, ticket: Long): Boolean = true
        override fun markUnknown() { documentId = null }
        override fun clearAcceptedWitness() { documentId = null }
        override fun syncWitness(documentId: String) { this.documentId = documentId }
        override suspend fun currentFields(): Map<String, PageField> = mapOf(REF to PageField("input", "text", "Android"))
        override fun acceptedDocument(): String? = documentId
        override fun currentUrl(): String? = currentUrlValue
        override fun viewId(): String = "view"
        override fun ticket(): Long = 1L
        override fun finishSubmitNavigation() { disarmed += 1 }
        override fun destroy() = Unit
    }
}
