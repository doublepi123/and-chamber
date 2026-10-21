package io.andchamber.app.runtime.process

import io.andchamber.app.browser.provider.BrowserProviderClient
import io.andchamber.app.browser.provider.ProviderClaim
import io.andchamber.app.browser.provider.ProviderProtocol
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderResult
import io.andchamber.app.browser.provider.ProviderTransport
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Uses the real binding, real BrowserProviderClient and the real ProviderPageRouter with only the
 * HTTP transport replaced. The binding's existing clientFactory test seam is used; no production
 * auth or connection path is bypassed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBrowserProviderBindingTest {
    @Test
    fun `a presentation block keeps transport and the same connected client`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        assertEquals(1, harness.clients.size)
        assertEquals(1, harness.clients[0].started.get())
        assertTrue(harness.presenter.state.value.transportEligible)
        assertTrue(harness.presenter.state.value.eligible)
        assertTrue(harness.binding.trustedHostAttached())

        harness.clients[0].emit(readyEvent())
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.connected)
        harness.presenter.setPresentationBlocked(true)
        advanceUntilIdle()

        assertEquals("blocking presentation creates no second client", 1, harness.clients.size)
        assertEquals(0, harness.clients[0].stopped.get())
        assertTrue(harness.presenter.state.value.transportEligible)
        assertFalse(harness.presenter.state.value.eligible)
        assertTrue(harness.binding.trustedHostAttached())
        assertTrue("the provider connection is not dropped by presentation", harness.presenter.state.value.connected)

        harness.presenter.setPresentationBlocked(false)
        advanceUntilIdle()
        assertEquals(1, harness.clients.size)
        assertEquals(1, harness.clients[0].started.get())
        assertEquals(0, harness.clients[0].stopped.get())
        assertTrue(harness.presenter.state.value.eligible)
        harness.scope.cancel()
    }

    @Test
    fun `pause console invalidation and detach stop and reconnect without a grant`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        harness.clients[0].emit(readyEvent())
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.connected)

        harness.presenter.onHostPaused()
        advanceUntilIdle()
        assertFalse(harness.presenter.state.value.transportEligible)
        assertFalse(harness.presenter.state.value.eligible)
        assertFalse(harness.binding.trustedHostAttached())
        assertEquals(1, harness.clients[0].stopped.get())
        assertFalse(harness.presenter.state.value.connected)
        assertNull(harness.presenter.registry.currentGrant())

        harness.presenter.onHostResumed()
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.transportEligible)
        assertTrue(harness.presenter.state.value.eligible)
        assertEquals(2, harness.clients.size)
        assertEquals(1, harness.clients[1].started.get())
        assertNull("reconnect restores no grant", harness.presenter.registry.currentGrant())

        harness.presenter.invalidateConsoleDocument("local")
        advanceUntilIdle()
        assertFalse(harness.presenter.state.value.transportEligible)
        assertEquals(1, harness.clients[1].stopped.get())

        harness.presenter.attachHostForTest("local", 2L)
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.transportEligible)
        assertTrue(harness.presenter.state.value.eligible)
        assertEquals(3, harness.clients.size)
        assertNull(harness.presenter.registry.currentGrant())

        harness.presenter.detachHost("local", 2L)
        advanceUntilIdle()
        assertFalse(harness.presenter.state.value.transportEligible)
        assertEquals(1, harness.clients[2].stopped.get())

        harness.presenter.attachHostForTest("local", 3L)
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.transportEligible)
        assertTrue(harness.presenter.state.value.eligible)
        assertEquals(4, harness.clients.size)
        assertNull(harness.presenter.registry.currentGrant())
        harness.scope.cancel()
    }

    @Test
    fun `a conflated console invalidation and reattach still stops the old client`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        assertEquals(1, harness.clients.size)
        assertEquals(1, harness.clients[0].started.get())

        // Both edges happen before the binding collector runs. The final transport state is true,
        // so a distinctUntilChanged(eligible) collector would miss the required stop.
        harness.presenter.invalidateConsoleDocument("local")
        harness.presenter.attachHostForTest("local", 2L)
        assertTrue(harness.presenter.state.value.transportEligible)
        advanceUntilIdle()

        assertEquals("the old client was stopped despite conflation", 1, harness.clients[0].stopped.get())
        assertEquals("a fresh client connects after a real invalidation", 2, harness.clients.size)
        assertEquals(1, harness.clients[1].started.get())
        assertTrue(harness.presenter.state.value.eligible)
        assertNull(harness.presenter.registry.currentGrant())
        harness.scope.cancel()
    }

    private class Harness(
        val presenter: NativeBrowserPresenter,
        val binding: LocalBrowserProviderBinding,
        val clients: MutableList<ClientRecord>,
        val scope: CoroutineScope,
    )

    private class ClientRecord(val launchId: Long) {
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val stopped = java.util.concurrent.atomic.AtomicInteger(0)
        val transport = TraceTransport(
            onStart = { started.incrementAndGet() },
            onStop = { stopped.incrementAndGet() },
        )

        fun emit(event: String) = transport.emit(event)
    }

    private class TraceTransport(
        private val onStart: () -> Unit,
        private val onStop: () -> Unit,
    ) : ProviderTransport {
        private val events = Channel<String>(Channel.UNLIMITED)

        override suspend fun open(url: String, headers: Map<String, String>): ProviderProtocol.Ready =
            error("events() is the only stream")

        override fun events(): Flow<String> = flow {
            onStart()
            try {
                for (event in events) emit(event)
            } finally {
                onStop()
            }
        }

        override suspend fun claim(requestId: String, connectionId: String): ProviderClaim? =
            ProviderClaim(false, "", 0)

        override suspend fun result(
            requestId: String,
            connectionId: String,
            nonce: String,
            result: ProviderResult,
        ) = Unit

        fun emit(event: String) {
            check(events.trySend(event).isSuccess)
        }
    }

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val presenter = NativeBrowserPresenter(
            registry = ProviderRegistry(),
            scope = CoroutineScope(dispatcher),
            main = dispatcher,
            clock = { 0L },
        )
        presenter.attachHostForTest("local", 1L)
        val router = ProviderPageRouter(presenter)
        val clients = mutableListOf<ClientRecord>()
        val generations = AtomicLong()
        val binding = LocalBrowserProviderBinding(
            presenter = presenter,
            router = router,
            scope = scope,
            clock = { 0L },
            clientFactory = { origin, token, launchId ->
                val record = ClientRecord(launchId)
                clients += record
                BrowserProviderClient(
                    origin = origin,
                    token = token,
                    runtimeLaunchId = launchId,
                    instanceKey = "local",
                    clock = { 0L },
                    transport = record.transport,
                    executor = router,
                    scope = scope,
                    registry = presenter.registry,
                    onConnection = LocalBrowserProviderBinding.ownConnection(
                        presenter,
                        launchId,
                        generations.incrementAndGet(),
                    ),
                    onCancel = { requestId -> presenter.cancel(requestId) },
                )
            },
        )
        binding.noteVerifiedLaunch("http://127.0.0.1:3000", "token-1", 1L)
        return Harness(presenter, binding, clients, scope)
    }

    private fun readyEvent(epoch: String = "e1", connection: String = "c1") =
        """{"type":"openchamber:event-stream-ready","properties":{"protocolVersion":1,"serverEpoch":"$epoch","connectionId":"$connection"}}"""
}
