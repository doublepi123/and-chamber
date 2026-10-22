package io.andchamber.app.runtime.process

import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.tool.BrowserToolEndpoint
import io.andchamber.app.browser.tool.BrowserToolTransport
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Uses the real binding, real presenter and real endpoint writer with only the loopback transport
 * replaced. The binding's transportFactory seam is used; no production auth path is bypassed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBrowserProviderBindingTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a presentation block keeps the connection and does not reconnect`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        assertEquals(1, harness.transports.size)
        assertEquals(1, harness.transports[0].starts.get())
        val first = harness.transports[0].connections.filterNotNull().single()
        assertTrue(harness.presenter.state.value.transportEligible)
        assertTrue(harness.presenter.state.value.connected)

        harness.presenter.setPresentationBlocked(true)
        advanceUntilIdle()

        assertEquals("blocking presentation creates no second transport", 1, harness.transports.size)
        assertTrue(harness.presenter.state.value.transportEligible)
        assertFalse(harness.presenter.state.value.eligible)
        assertTrue("the connection is not dropped by presentation", harness.presenter.state.value.connected)
        assertEquals(listOf(first), harness.transports[0].connections.filterNotNull())

        harness.presenter.setPresentationBlocked(false)
        advanceUntilIdle()
        assertEquals(1, harness.transports.size)
        assertTrue(harness.presenter.state.value.connected)
        harness.scope.cancel()
    }

    @Test
    fun `pause console invalidation and detach clear the connection and reconnect without a grant`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        val first = harness.transports[0].connections.filterNotNull().single()
        assertTrue(harness.presenter.state.value.connected)

        harness.presenter.onHostPaused()
        advanceUntilIdle()
        assertFalse(harness.presenter.state.value.transportEligible)
        assertFalse(harness.presenter.state.value.eligible)
        assertFalse(harness.binding.trustedHostAttached())
        assertFalse(harness.presenter.state.value.connected)
        assertNull(harness.presenter.registry.currentGrant())
        assertTrue(harness.transports[0].connections.contains(null))

        harness.presenter.onHostResumed()
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.connected)
        val second = harness.transports[0].connections.filterNotNull().last()
        assertNotEquals(first, second)
        assertTrue(second.startsWith("plugin-1-"))
        assertNull("reconnect restores no grant", harness.presenter.registry.currentGrant())

        harness.presenter.invalidateConsoleDocument("local")
        advanceUntilIdle()
        assertFalse(harness.presenter.state.value.transportEligible)
        assertFalse(harness.presenter.state.value.connected)

        harness.presenter.attachHostForTest("local", 2L)
        advanceUntilIdle()
        assertTrue(harness.presenter.state.value.connected)
        assertNull(harness.presenter.registry.currentGrant())

        harness.presenter.detachHost("local", 2L)
        advanceUntilIdle()
        assertFalse(harness.presenter.state.value.transportEligible)
        assertFalse(harness.presenter.state.value.connected)
        harness.scope.cancel()
    }

    @Test
    fun `a conflated console invalidation and reattach still issues a new connection`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        val first = harness.transports[0].connections.filterNotNull().single()

        // Both edges happen before the binding collector runs; the final transport state is true.
        harness.presenter.invalidateConsoleDocument("local")
        harness.presenter.attachHostForTest("local", 2L)
        assertTrue(harness.presenter.state.value.transportEligible)
        advanceUntilIdle()

        assertTrue("the old connection was cleared despite conflation", harness.transports[0].connections.contains(null))
        val second = harness.transports[0].connections.filterNotNull().last()
        assertNotEquals(first, second)
        assertTrue(harness.presenter.state.value.connected)
        assertNull(harness.presenter.registry.currentGrant())
        harness.scope.cancel()
    }

    @Test
    fun `stop closes the transport and deletes the endpoint file`() = runTest {
        val harness = harness()
        advanceUntilIdle()
        val endpointFile = BrowserToolEndpoint.hostFile(harness.home)
        assertTrue(Files.isRegularFile(endpointFile.toPath(), LinkOption.NOFOLLOW_LINKS))

        harness.binding.stop(1L)
        advanceUntilIdle()

        assertEquals(1, harness.transports[0].stops.get())
        assertFalse(Files.exists(endpointFile.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(harness.presenter.state.value.connected)
        assertTrue(harness.transports[0].connections.contains(null))
        harness.scope.cancel()
    }

    @Test
    fun `stop before start refuses the launch and creates no transport`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val presenter =
            NativeBrowserPresenter(
                registry = ProviderRegistry(),
                scope = CoroutineScope(dispatcher),
                main = dispatcher,
                clock = { 0L },
            )
        presenter.attachHostForTest("local", 1L)
        val home = folder.newFolder("home-stop-first")
        var factories = 0
        val binding =
            LocalBrowserProviderBinding(
                presenter = presenter,
                router = ProviderPageRouter(presenter),
                scope = scope,
                clock = { 0L },
                transportFactory = { _, _ ->
                    factories += 1
                    FakeTransport()
                },
                tokenSource = { "a".repeat(64) },
            )

        binding.stop(1L)

        assertFalse(binding.start(1L, home, folder.root))
        assertEquals("a revoked launch never builds a transport", 0, factories)
        assertFalse(Files.exists(BrowserToolEndpoint.hostFile(home).toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(presenter.state.value.connected)
        scope.cancel()
    }

    @Test
    fun `a stop while the transport is starting wins and tears it down`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val presenter =
            NativeBrowserPresenter(
                registry = ProviderRegistry(),
                scope = CoroutineScope(dispatcher),
                main = dispatcher,
                clock = { 0L },
            )
        presenter.attachHostForTest("local", 1L)
        val home = folder.newFolder("home-blocking")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var transport: FakeTransport
        val binding =
            LocalBrowserProviderBinding(
                presenter = presenter,
                router = ProviderPageRouter(presenter),
                scope = scope,
                clock = { 0L },
                transportFactory = { _, _ ->
                    FakeTransport(startEntered = entered, startRelease = release).also { transport = it }
                },
                tokenSource = { "a".repeat(64) },
            )
        var started: Boolean? = null
        val worker =
            Thread { started = binding.start(1L, home, folder.root) }.apply {
                isDaemon = true
                start()
            }
        assertTrue("start never blocked in the transport", entered.await(5, TimeUnit.SECONDS))

        binding.stop(1L)
        release.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))

        assertEquals(false, started)
        assertEquals(1, transport.starts.get())
        assertEquals("the aborted start stops its own transport", 1, transport.stops.get())
        assertFalse(
            "an aborted start never writes the endpoint",
            Files.exists(BrowserToolEndpoint.hostFile(home).toPath(), LinkOption.NOFOLLOW_LINKS),
        )
        assertFalse(presenter.state.value.connected)
        assertTrue("an aborted start never connects", transport.connections.isEmpty())

        advanceUntilIdle()
        assertFalse("a late eligibility emission cannot reconnect it", presenter.state.value.connected)
        assertTrue(transport.connections.isEmpty())
        scope.cancel()
    }

    @Test
    fun `stop immediately after start leaves no endpoint and no connection`() = runTest {
        val harness = harness()
        val endpointFile = BrowserToolEndpoint.hostFile(harness.home)
        assertTrue(Files.isRegularFile(endpointFile.toPath(), LinkOption.NOFOLLOW_LINKS))

        // Stop before the eligibility collector ever runs, right after the endpoint became ready.
        harness.binding.stop(1L)
        advanceUntilIdle()

        assertEquals(1, harness.transports[0].stops.get())
        assertFalse(Files.exists(endpointFile.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(harness.presenter.state.value.connected)
        assertEquals(1, harness.transports[0].connections.filterNotNull().size)
        assertTrue(harness.transports[0].connections.contains(null))
        harness.scope.cancel()
    }

    @Test
    fun `a delayed old-launch stop never deletes the new launch's endpoint`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val presenter =
            NativeBrowserPresenter(
                registry = ProviderRegistry(),
                scope = CoroutineScope(dispatcher),
                main = dispatcher,
                clock = { 0L },
            )
        presenter.attachHostForTest("local", 1L)
        val home = folder.newFolder("home-delayed-stop")
        val stopEntered = CountDownLatch(1)
        val stopRelease = CountDownLatch(1)
        val transports = mutableListOf<FakeTransport>()
        val tokenSeq = java.util.concurrent.atomic.AtomicInteger(0)
        val binding =
            LocalBrowserProviderBinding(
                presenter = presenter,
                router = ProviderPageRouter(presenter),
                scope = scope,
                clock = { 0L },
                transportFactory = { _, _ ->
                    val transport =
                        if (transports.isEmpty()) {
                            FakeTransport(port = 43210, stopEntered = stopEntered, stopRelease = stopRelease)
                        } else {
                            FakeTransport(port = 43211)
                        }
                    transports += transport
                    transport
                },
                tokenSource = { "%064x".format(tokenSeq.getAndIncrement()) },
            )
        assertTrue(binding.start(1L, home, folder.root))
        val endpoint = BrowserToolEndpoint.hostFile(home)
        val tokenA = "%064x".format(0)
        val tokenB = "%064x".format(1)
        assertEquals(BrowserToolEndpoint.content(43210, tokenA), endpoint.readText())

        // A stops and blocks in transport close, after the lock already removed A's file.
        val stopper = Thread { binding.stop(1L) }.apply { isDaemon = true; start() }
        assertTrue("the old cleanup never blocked", stopEntered.await(5, TimeUnit.SECONDS))

        assertTrue(binding.start(2L, home, folder.root))
        assertEquals(
            "the new launch published its own endpoint",
            BrowserToolEndpoint.content(43211, tokenB),
            endpoint.readText(),
        )

        stopRelease.countDown()
        stopper.join(TimeUnit.SECONDS.toMillis(5))

        assertTrue(
            "the delayed old cleanup deleted the new launch's endpoint",
            Files.isRegularFile(endpoint.toPath(), LinkOption.NOFOLLOW_LINKS),
        )
        assertEquals(BrowserToolEndpoint.content(43211, tokenB), endpoint.readText())
        assertTrue(presenter.state.value.connected)
        assertEquals(1, transports[1].connections.filterNotNull().size)
        assertEquals("a delayed stop must not stop the new transport", 0, transports[1].stops.get())
        scope.cancel()
    }

    @Test
    fun `an older start never clears a newer launch's pending install`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val presenter =
            NativeBrowserPresenter(
                registry = ProviderRegistry(),
                scope = CoroutineScope(dispatcher),
                main = dispatcher,
                clock = { 0L },
            )
        presenter.attachHostForTest("local", 1L)
        val home = folder.newFolder("home-pending-race")
        val startEntered = CountDownLatch(1)
        val startRelease = CountDownLatch(1)
        val transports = mutableListOf<FakeTransport>()
        val tokenSeq = java.util.concurrent.atomic.AtomicInteger(0)
        val binding =
            LocalBrowserProviderBinding(
                presenter = presenter,
                router = ProviderPageRouter(presenter),
                scope = scope,
                clock = { 0L },
                transportFactory = { _, _ ->
                    val transport =
                        if (transports.isEmpty()) {
                            FakeTransport(port = 43210, startEntered = startEntered, startRelease = startRelease)
                        } else {
                            FakeTransport(port = 43211)
                        }
                    transports += transport
                    transport
                },
                tokenSource = { "%064x".format(tokenSeq.getAndIncrement()) },
            )
        var olderStarted: Boolean? = null
        val older =
            Thread { olderStarted = binding.start(1L, home, folder.root) }.apply {
                isDaemon = true
                start()
            }
        assertTrue("the older start never blocked", startEntered.await(5, TimeUnit.SECONDS))

        assertTrue("the newer launch installs while the older start is blocked", binding.start(2L, home, folder.root))
        val endpoint = BrowserToolEndpoint.hostFile(home)
        val tokenB = "%064x".format(1)
        assertEquals(BrowserToolEndpoint.content(43211, tokenB), endpoint.readText())

        startRelease.countDown()
        older.join(TimeUnit.SECONDS.toMillis(5))

        assertEquals(false, olderStarted)
        assertEquals(
            "the aborted older start must not clear the newer pending install",
            BrowserToolEndpoint.content(43211, tokenB),
            endpoint.readText(),
        )
        assertTrue(presenter.state.value.connected)
        assertEquals(1, transports[1].connections.filterNotNull().size)
        scope.cancel()
    }

    @Test
    fun `clearing a stale endpoint leaves an installed launch's file alone`() = runTest {
        val harness = harness()
        val endpoint = BrowserToolEndpoint.hostFile(harness.home)
        assertTrue(harness.binding.clearStaleEndpoint(harness.home, folder.root))
        assertTrue(
            "an installed launch still owns its endpoint",
            Files.isRegularFile(endpoint.toPath(), LinkOption.NOFOLLOW_LINKS),
        )

        harness.binding.stop(1L)
        advanceUntilIdle()
        assertFalse(Files.exists(endpoint.toPath(), LinkOption.NOFOLLOW_LINKS))

        // A file left by an earlier process has no owner in this binding.
        endpoint.writeText("""{"version":1,"url":"http://127.0.0.1:1/v1/tool","token":"stale"}""")
        assertTrue(harness.binding.clearStaleEndpoint(harness.home, folder.root))
        assertFalse(Files.exists(endpoint.toPath(), LinkOption.NOFOLLOW_LINKS))
        harness.scope.cancel()
    }

    private class Harness(
        val presenter: NativeBrowserPresenter,
        val binding: LocalBrowserProviderBinding,
        val transports: MutableList<FakeTransport>,
        val home: File,
        val scope: CoroutineScope,
    )

    private class FakeTransport(
        private val port: Int = 43210,
        private val startEntered: CountDownLatch? = null,
        private val startRelease: CountDownLatch? = null,
        private val stopEntered: CountDownLatch? = null,
        private val stopRelease: CountDownLatch? = null,
    ) : BrowserToolTransport {
        val starts = java.util.concurrent.atomic.AtomicInteger(0)
        val stops = java.util.concurrent.atomic.AtomicInteger(0)
        val connections: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())

        override fun start(): Int {
            starts.incrementAndGet()
            startEntered?.countDown()
            startRelease?.await(5, TimeUnit.SECONDS)
            return port
        }

        override fun stop() {
            stops.incrementAndGet()
            stopEntered?.countDown()
            stopRelease?.await(5, TimeUnit.SECONDS)
        }

        override fun setConnection(connectionId: String?) {
            connections += connectionId
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
        val home = folder.newFolder("home")
        val transports = mutableListOf<FakeTransport>()
        val binding = LocalBrowserProviderBinding(
            presenter = presenter,
            router = router,
            scope = scope,
            clock = { 0L },
            transportFactory = { _, _ ->
                FakeTransport().also { transports += it }
            },
            tokenSource = { "a".repeat(64) },
        )
        assertTrue(binding.start(1L, home, folder.root))
        return Harness(presenter, binding, transports, home, scope)
    }
}
