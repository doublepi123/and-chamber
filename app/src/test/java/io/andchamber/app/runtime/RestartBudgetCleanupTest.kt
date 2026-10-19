package io.andchamber.app.runtime

import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeInstaller
import io.andchamber.app.runtime.install.RuntimeMetadata
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.HealthReport
import io.andchamber.app.runtime.process.OpenChamberServer
import io.andchamber.app.service.RuntimeServicePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Restart-budget exhaustion must stop the server before the terminal Failed lets the service finish,
 * and the user's Stop / backoff paths must keep working. Uses a well-behaved fake: stop() clears alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestartBudgetCleanupTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeServer(
        /** When true, stop() counts the call, then throws, like a failing native teardown. */
        private val stopThrows: Boolean = false,
        /** Called with the stop count after each stop() call, before any throw. Used for deterministic interleaving. */
        private val onStop: (Int) -> Unit = {},
    ) : OpenChamberServer {
        var alive = false
        var stops = 0
        var starts = 0
        var failNextStarts = 0

        override fun start(
            runtime: InstalledRuntime,
            uiPassword: String,
            onLog: (String) -> Unit,
            isCancelled: () -> Boolean,
        ): Long {
            if (failNextStarts > 0) {
                failNextStarts--
                error("restart did not come up")
            }
            starts++
            alive = true
            return starts.toLong()
        }

        override fun stop() {
            stops++
            alive = false
            onStop(stops)
            if (stopThrows) error("native teardown failed")
        }

        override fun isAlive(): Boolean = alive

        override fun readyLaunchId(): Long? = if (alive) starts.toLong() else null
    }

    private class Installer(private val runtime: InstalledRuntime) : RuntimeInstaller {
        override fun recover(): Boolean = false

        override fun installedRuntime(): InstalledRuntime? = runtime

        override suspend fun install(report: InstallReporter): InstalledRuntime = runtime

        override fun discardEnvironment() = Unit

        override fun removeAll() = Unit
    }

    /** Health answers from a flag, so a test controls the watchdog's view of the child. */
    private class FlagProbe(var ok: Boolean = true) : HealthProbe {
        override fun read(port: Int): HealthReport? =
            if (ok) HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "a")
            else null
    }

    private fun runtimeFor(root: File) =
        InstalledRuntime(
            metadata =
                RuntimeMetadata(
                    runtimeVersion = "r",
                    alpineVersion = "3.24.1",
                    abi = "arm64-v8a",
                    port = 3000,
                    openChamberVersion = "2.4.0",
                    openCodeVersion = "2.0.32",
                    installedAt = 1L,
                ),
            rootfs = File(root, "rootfs"),
        )

    private fun TestScope.controller(
        server: OpenChamberServer,
        health: HealthProbe,
        killed: MutableList<Int>,
        root: File,
        policy: RestartPolicy,
        scope: CoroutineScope,
    ): DefaultLocalRuntimeController =
        DefaultLocalRuntimeController(
            paths = RuntimePaths(root),
            abi = "arm64-v8a",
            installer = Installer(runtimeFor(root)),
            server = server,
            health = health,
            uiPassword = { "ui-secret" },
            listener = RuntimeStateListener {},
            foregroundStarter = ForegroundStarter {},
            killOwned = { killed.add(1) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = scope,
            nowMillis = { testScheduler.currentTime },
            restartPolicy = policy,
            watchdogPollMillis = 500L,
            supportedAbis = setOf("arm64-v8a"),
        )

    private fun TestScope.step(millis: Long = 0L) {
        runCurrent()
        if (millis > 0) advanceTimeBy(millis)
        runCurrent()
    }

    /**
     * Budget of one restart; health never answers while the child stays alive. After the budget is
     * spent the server must be stopped (alive false) and the service-facing state must be terminal.
     */
    @Test
    fun `budget exhausted with a live child stops the server before the terminal failure`() =
        runTest {
            val server = FakeServer()
            val killed = mutableListOf<Int>()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
            try {
                // Health answers during start so the server becomes Running, then goes silent while alive.
                val health = FlagProbe(ok = true)
                val running = controller(server, health, killed, folder.root, RestartPolicy(listOf(1_000L), 120_000L), scope)
                running.initialize()
                step()
                running.perform(RuntimeOperation.Start)
                step()
                assertTrue("precondition: Running", running.state.value is RuntimeState.Running)
                health.ok = false

                repeat(30) { step(500L) }

                val state = running.state.value
                assertTrue("terminal Failed", state is RuntimeState.Failed)
                assertEquals("busy false once recovery ends", false, running.busy.value)
                assertEquals(RuntimeServicePolicy.Action.Finish, RuntimeServicePolicy.decide(running.busy.value, state))
                assertFalse("server must be stopped on budget exhaustion", server.isAlive())
                assertEquals("one stop before the restart, one final cleanup", 2, server.stops)
                assertTrue("owned cleanup ran on the final failure", killed.isNotEmpty())
            } finally {
                scope.cancel()
            }
        }

    /**
     * server.stop() throws during exhaustion cleanup. killOwned must still run, the terminal Failed must
     * still be published, and busy must drop to false.
     */
    @Test
    fun `a throwing server stop still runs owned cleanup and publishes the terminal failure`() =
        runTest {
            val server = FakeServer(stopThrows = true)
            val killed = mutableListOf<Int>()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
            try {
                val health = FlagProbe(ok = true)
                val controller = controller(server, health, killed, folder.root, RestartPolicy(listOf(1_000L), 120_000L), scope)
                controller.initialize()
                step()
                controller.perform(RuntimeOperation.Start)
                step()
                health.ok = false
                repeat(30) { step(500L) }

                val state = controller.state.value
                assertTrue("terminal Failed despite stop throwing", state is RuntimeState.Failed)
                assertEquals(FailurePhase.Runtime, (state as RuntimeState.Failed).phase)
                assertFalse("busy false after the recovery job ends", controller.busy.value)
                assertTrue("owned cleanup must run even when server.stop throws", killed.isNotEmpty())
            } finally {
                scope.cancel()
            }
        }

    /**
     * A user Stop is issued from inside the final server.stop() of budget exhaustion (stop count 2:
     * the first is the pre-restart stop). Stop is queued behind the operation lock that the recovery
     * holds, so it must run after the cleanup, never interleave with it, and end in Stopped with
     * busy false and the server not alive. Deterministic: the Stop is triggered by the stop count.
     */
    @Test
    fun `user stop issued during the final exhaustion cleanup runs after it and ends stopped`() =
        runTest {
            lateinit var subject: DefaultLocalRuntimeController
            var stopIssued = false
            val server =
                FakeServer(onStop = { count ->
                    // The second stop() is the exhaustion cleanup; Stop is requested from inside it.
                    if (count == 2 && !stopIssued) {
                        stopIssued = true
                        subject.perform(RuntimeOperation.Stop)
                    }
                })
            val killed = mutableListOf<Int>()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
            try {
                val health = FlagProbe(ok = true)
                subject = controller(server, health, killed, folder.root, RestartPolicy(listOf(1_000L), 120_000L), scope)
                subject.initialize()
                step()
                subject.perform(RuntimeOperation.Start)
                step()
                health.ok = false
                repeat(30) { step(500L) }

                assertTrue("the Stop was issued inside the cleanup", stopIssued)
                assertEquals("Stop wins after cleanup", RuntimeState.Stopped, subject.state.value)
                assertFalse("server must not be alive", server.isAlive())
                assertFalse("busy false once the queue drains", subject.busy.value)
                assertTrue("cleanup and the user Stop both called stop()", server.stops >= 2)
            } finally {
                scope.cancel()
            }
        }

    /** User Stop during the backoff must cancel the pending restart and leave the server stopped. */
    @Test
    fun `user stop during backoff still cancels the restart and stays stopped`() =
        runTest {
            val server = FakeServer()
            val killed = mutableListOf<Int>()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
            try {
                val health = FlagProbe(ok = true)
                val controller = controller(server, health, killed, folder.root, RestartPolicy(listOf(5_000L), 120_000L), scope)
                controller.initialize()
                step()
                controller.perform(RuntimeOperation.Start)
                step()
                val startsBefore = server.starts
                health.ok = false
                step(1_500L) // misses accumulate, recovery is now in its backoff
                controller.perform(RuntimeOperation.Stop)
                step(10_000L)

                assertEquals(RuntimeState.Stopped, controller.state.value)
                assertEquals("no restart after Stop", startsBefore, server.starts)
                assertFalse(server.isAlive())
                assertEquals(false, controller.busy.value)
            } finally {
                scope.cancel()
            }
        }

    /** A restart that fails inside the budget must keep the watchdog-driven recovery alive, not finish. */
    @Test
    fun `failed restart within budget keeps the service busy until the budget is spent`() =
        runTest {
            val server = FakeServer()
            val killed = mutableListOf<Int>()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
            try {
                val health = FlagProbe(ok = true)
                val controller = controller(server, health, killed, folder.root, RestartPolicy(listOf(1_000L), 120_000L), scope)
                controller.initialize()
                step()
                controller.perform(RuntimeOperation.Start)
                step()
                server.failNextStarts = 10
                health.ok = false
                // Three missed probes at 500 ms each: recovery starts only on the third miss (1500 ms).
                repeat(3) { step(500L) }
                assertTrue("recovery must have started after three misses", controller.busy.value)
                // Still inside the 1 s backoff of the single budget step: the recovery job keeps busy.
                step(200L)
                assertTrue("busy while the restart is pending", controller.busy.value)
                step(10_000L)

                assertTrue(controller.state.value is RuntimeState.Failed)
                assertFalse(controller.busy.value)
                assertFalse(server.isAlive())
            } finally {
                scope.cancel()
            }
        }
}
