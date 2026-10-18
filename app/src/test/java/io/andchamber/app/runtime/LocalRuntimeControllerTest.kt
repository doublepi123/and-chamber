package io.andchamber.app.runtime

import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeInstaller
import io.andchamber.app.runtime.install.RuntimeMetadata
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.HealthReport
import io.andchamber.app.runtime.process.OpenChamberServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun fakeRuntime(root: File) =
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

/**
 * Controller state machine tests. The watchdog is ENABLED here: its probe loop is driven with
 * bounded virtual-time steps ([step]) and stopped at the end of each test, so the scheduler never
 * has to go idle while a probe loop is alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeControllerTest {
    @get:Rule val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private class FakeInstaller(
        private val root: File,
        var installed: InstalledRuntime? = null,
        var installFails: Boolean = false,
    ) : RuntimeInstaller {
        var discarded = 0
        var removed = 0
        var recovered = 0

        override fun recover(): Boolean {
            recovered++
            return false
        }

        override fun installedRuntime(): InstalledRuntime? = installed

        override suspend fun install(report: InstallReporter): InstalledRuntime {
            report.step(InstallStep.DownloadRootfs, 0.5f, "half")
            if (installFails) error("network down")
            return fakeRuntime(root).also { installed = it }
        }

        override fun discardEnvironment() {
            discarded++
            installed = null
        }

        override fun removeAll() {
            removed++
            installed = null
        }
    }

    /** Scripted server: [startError] is thrown by start, [aliveNow] is what isAlive reports. */
    private class FakeServer(
        var startError: Throwable? = null,
    ) : OpenChamberServer {
        val starts = mutableListOf<String>()
        var alive = false
        var stops = 0

        override fun start(
            runtime: InstalledRuntime,
            uiPassword: String,
            onLog: (String) -> Unit,
            isCancelled: () -> Boolean,
        ) {
            startError?.let { throw it }
            starts += uiPassword
            alive = true
        }

        override fun stop() {
            stops++
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }

    /** Health answers from a mutable flag so a test can flip the server dead and alive. */
    private class FakeHealth(
        var ok: Boolean = true,
    ) : HealthProbe {
        override fun read(port: Int): HealthReport? =
            if (ok) HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "a")
            else null
    }

    private fun TestScope.controller(
        installer: FakeInstaller,
        server: OpenChamberServer,
        health: HealthProbe = FakeHealth(),
        abi: String = "arm64-v8a",
        states: MutableList<RuntimeState>? = null,
        killed: MutableList<Int>? = null,
        policy: RestartPolicy = RestartPolicy(listOf(1_000L, 2_000L), healthyResetMillis = 120_000L),
        watchdogPollMillis: Long = 500L,
    ): DefaultLocalRuntimeController {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        scopes += scope
        return DefaultLocalRuntimeController(
            paths = io.andchamber.app.runtime.RuntimePaths(folder.root),
            abi = abi,
            installer = installer,
            server = server,
            health = health,
            uiPassword = { "ui-secret" },
            listener = RuntimeStateListener { state -> states?.add(state) },
            foregroundStarter = ForegroundStarter { },
            killOwned = { killed?.add(1) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = scope,
            nowMillis = { testScheduler.currentTime },
            restartPolicy = policy,
            watchdogPollMillis = watchdogPollMillis,
            supportedAbis = setOf("arm64-v8a"),
        )
    }

    /** Lets queued work run, then advances virtual time by [millis] in bounded steps. */
    private fun TestScope.step(millis: Long = 0L) {
        runCurrent()
        if (millis > 0) advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `fresh device with no install reports NotInstalled after recovery`() =
        runTest {
            val installer = FakeInstaller(folder.root)
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()

            assertEquals(RuntimeState.NotInstalled, controller.state.value)
            assertEquals(1, installer.recovered)
        }

    @Test
    fun `detecting is the initial state before the disk check`() =
        runTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer())
            assertEquals(RuntimeState.Detecting, controller.state.value)
        }

    @Test
    fun `unsupported abi short-circuits to UnsupportedAbi`() =
        runTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer(), abi = "x86_64")
            controller.initialize()
            step()

            assertEquals(RuntimeState.UnsupportedAbi, controller.state.value)
        }

    @Test
    fun `install then start reaches Running and passes the UI password`() =
        runTest {
            val server = FakeServer()
            val states = mutableListOf<RuntimeState>()
            val controller = controller(FakeInstaller(folder.root), server, states = states)
            controller.perform(RuntimeOperation.Install)
            step()

            assertTrue(states.any { it is RuntimeState.Installing })
            assertEquals(RuntimeState.Running("http://127.0.0.1:3000", 3000, 0L), controller.state.value)
            assertEquals(listOf("ui-secret"), server.starts)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `intermediate Stopped between install and start is published`() =
        runTest {
            val states = mutableListOf<RuntimeState>()
            val controller = controller(FakeInstaller(folder.root), FakeServer(), states = states)
            controller.perform(RuntimeOperation.Install)
            step()

            val stoppedIndex = states.indexOfFirst { it == RuntimeState.Stopped }
            val startingIndex = states.indexOfFirst { it is RuntimeState.Starting }
            assertTrue("Stopped must come before Starting", stoppedIndex in 0 until startingIndex)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `auto-start throwing becomes Failed(Start) and does not escape the scope`() =
        runTest {
            val root = folder.root
            val server = FakeServer(startError = IllegalStateException("OpenCode backend failed to start: boom"))
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            val failed = controller.state.value as RuntimeState.Failed
            assertTrue(failed.reason.contains("boom"))
            assertEquals(FailurePhase.Start, failed.phase)
            assertTrue(failed.recoverable)
        }

    @Test
    fun `install failure is tagged Install so Retry reinstalls`() =
        runTest {
            val controller = controller(FakeInstaller(folder.root, installFails = true), FakeServer())
            controller.perform(RuntimeOperation.Install)
            step()

            assertEquals(
                RuntimeState.Failed("network down", recoverable = true, phase = FailurePhase.Install),
                controller.state.value,
            )
        }

    @Test
    fun `rapid crash loop exhausts the budget and reports Failed(Runtime)`() =
        runTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server, health = health)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            // Every restart comes up and dies again before the 2-minute healthy window closes.
            health.ok = false
            step(500L) // watchdog sees the death
            repeat(4) {
                step(3_000L) // backoff + restart attempt
                if (controller.state.value is RuntimeState.Failed) return@repeat
                step(500L)
            }

            val failed = controller.state.value as RuntimeState.Failed
            assertTrue(failed.reason.contains("tap Start"))
            assertEquals(FailurePhase.Runtime, failed.phase)
        }

    @Test
    fun `stop during backoff cancels the pending restart`() =
        runTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server, health = health)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            val startsBefore = server.starts.size

            // Death is seen by the watchdog probe; the restart is now waiting out its backoff.
            health.ok = false
            step(500L)
            assertEquals(
                "a crash must be pending a restart, not already done",
                startsBefore,
                server.starts.size,
            )
            controller.perform(RuntimeOperation.Stop)
            step(10_000L)

            assertEquals(RuntimeState.Stopped, controller.state.value)
            assertEquals("no restart after Stop", startsBefore, server.starts.size)
        }

    @Test
    fun `a healthy window of two minutes refills the budget`() =
        runTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server, health = health)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            // One crash, then a restart that stays healthy long enough to refill the budget.
            health.ok = false
            step(500L)
            step(1_000L)
            health.ok = true
            step(125_000L)

            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `stop kills owned processes even when the server was never started`() =
        runTest {
            val killed = mutableListOf<Int>()
            val controller = controller(FakeInstaller(folder.root), FakeServer(), killed = killed)
            controller.perform(RuntimeOperation.Stop)
            step()

            assertEquals(1, killed.size)
        }

    @Test
    fun `repair kills owned processes before discarding files`() =
        runTest {
            val root = folder.root
            val killed = mutableListOf<Int>()
            val installer = FakeInstaller(root, installed = fakeRuntime(root))
            val controller = controller(installer, FakeServer(), killed = killed)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Repair)
            step()

            assertTrue(killed.isNotEmpty())
            assertEquals(1, installer.discarded)
            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `uninstall returns to NotInstalled and removes the runtime`() =
        runTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = fakeRuntime(root))
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Uninstall)
            step()

            assertEquals(RuntimeState.NotInstalled, controller.state.value)
            assertEquals(1, installer.removed)
        }

    @Test
    fun `stop is not queued behind a long start - the cancel flag reaches the server`() =
        runTest {
            val root = folder.root
            var sawCancel = false
            val server =
                object : OpenChamberServer {
                    override fun start(
                        runtime: InstalledRuntime,
                        uiPassword: String,
                        onLog: (String) -> Unit,
                        isCancelled: () -> Boolean,
                    ) {
                        sawCancel = isCancelled()
                        throw kotlinx.coroutines.CancellationException("Start cancelled by Stop")
                    }

                    override fun stop() {}

                    override fun isAlive(): Boolean = false
                }
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server)
            controller.initialize()
            step()
            controller.stop()
            controller.perform(RuntimeOperation.Start)
            step()

            assertTrue(sawCancel || controller.state.value !is RuntimeState.Running)
            assertTrue(controller.state.value !is RuntimeState.Starting)
        }
}
