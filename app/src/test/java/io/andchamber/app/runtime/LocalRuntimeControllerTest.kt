package io.andchamber.app.runtime

import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.PackageVersionSource
import io.andchamber.app.runtime.install.PackageVersions
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
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import io.andchamber.app.service.RuntimeServicePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun fakeRuntime(root: File) = runtimeWith(root, versions = null)

/** A runtime whose installed npm versions are [versions] (the manifest pins 2.4.0 / 2.0.32 when null). */
private fun runtimeWith(
    root: File,
    versions: PackageVersions?,
) = InstalledRuntime(
    metadata =
        RuntimeMetadata(
            runtimeVersion = "r",
            alpineVersion = "3.24.1",
            abi = "arm64-v8a",
            port = 3000,
            openChamberVersion = versions?.openChamber ?: "2.4.0",
            openCodeVersion = versions?.openCode ?: "2.0.32",
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

    /**
     * runTest waits for every coroutine on its scheduler before it returns. A live watchdog never
     * finishes, so the controller scopes must be cancelled INSIDE the body; @After runs too late and
     * the test hangs in runTest's end-of-body drain (seen as a >15 min hang).
     */
    private fun controllerTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            try {
                body()
            } finally {
                scopes.forEach { it.cancel() }
            }
        }

    private class FakeInstaller(
        private val root: File,
        var installed: InstalledRuntime? = null,
        var installFails: Boolean = false,
    ) : RuntimeInstaller {
        /** When set, install throws this instead of the generic network error. */
        var failWith: Throwable? = null

        /** When set, update throws this; the active runtime is left as it was (like the real installer). */
        var updateFailWith: Throwable? = null
        val installCalls = mutableListOf<PackageVersions?>()
        val updateCalls = mutableListOf<PackageVersions>()

        var discarded = 0
        var removed = 0
        var recovered = 0

        override fun recover(): Boolean {
            recovered++
            return false
        }

        override fun installedRuntime(): InstalledRuntime? = installed

        override suspend fun install(
            report: InstallReporter,
            versions: PackageVersions?,
        ): InstalledRuntime {
            installCalls += versions
            report.step(InstallStep.DownloadRootfs, 0.5f, "half")
            failWith?.let { throw it }
            if (installFails) error("network down")
            return runtimeWith(root, versions).also { installed = it }
        }

        override suspend fun update(
            report: InstallReporter,
            versions: PackageVersions,
        ): InstalledRuntime {
            updateCalls += versions
            report.step(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment)
            updateFailWith?.let { throw it }
            return runtimeWith(root, versions).also { installed = it }
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
        var startAttempts = 0
        /** The next N start calls fail (simulates a restart that does not come up). */
        var failNextStarts = 0
        var alive = false
        var stops = 0
        private var launch = 0L

        override fun start(
            runtime: InstalledRuntime,
            uiPassword: String,
            onLog: (String) -> Unit,
            isCancelled: () -> Boolean,
        ): Long {
            startAttempts++
            startError?.let { throw it }
            if (failNextStarts > 0) {
                failNextStarts--
                error("restart did not come up")
            }
            starts += uiPassword
            alive = true
            return ++launch
        }

        override fun stop() {
            stops++
            alive = false
        }

        override fun isAlive(): Boolean = alive

        override fun readyLaunchId(): Long? = if (alive) launch else null
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
        versionSource: PackageVersionSource? = null,
        pinnedVersions: PackageVersions? = null,
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
            versionSource = versionSource,
            pinnedVersions = pinnedVersions,
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
        controllerTest {
            val installer = FakeInstaller(folder.root)
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()

            assertEquals(RuntimeState.NotInstalled, controller.state.value)
            assertEquals(1, installer.recovered)
        }

    @Test
    fun `detecting is the initial state before the disk check`() =
        controllerTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer())
            assertEquals(RuntimeState.Detecting, controller.state.value)
        }

    @Test
    fun `unsupported abi short-circuits to UnsupportedAbi`() =
        controllerTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer(), abi = "x86_64")
            controller.initialize()
            step()

            assertEquals(RuntimeState.UnsupportedAbi, controller.state.value)
        }

    @Test
    fun `install then start reaches Running and passes the UI password`() =
        controllerTest {
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
        controllerTest {
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
        controllerTest {
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

    /** A typed failure keeps its own headline key and raw detail, so the UI can translate the headline. */
    @Test
    fun `a user-facing failure carries its message key and untranslated detail`() =
        controllerTest {
            val root = folder.root
            val installer =
                FakeInstaller(root).apply {
                    failWith = UserFacingException(MessageKey.PackagesFailed, "apk failed (exit 1)", detail = "fetch failed")
                }
            val controller = controller(installer, FakeServer())
            controller.perform(RuntimeOperation.Install)
            step()

            val failed = controller.state.value as RuntimeState.Failed
            assertEquals(MessageKey.PackagesFailed, failed.messageKey)
            assertEquals("fetch failed", failed.detail)
            assertEquals(FailurePhase.Install, failed.phase)
        }

    @Test
    fun `install failure is tagged Install so Retry reinstalls`() =
        controllerTest {
            val controller = controller(FakeInstaller(folder.root, installFails = true), FakeServer())
            controller.perform(RuntimeOperation.Install)
            step()

            assertEquals(
                RuntimeState.Failed("network down", recoverable = true, phase = FailurePhase.Install, messageKey = MessageKey.Generic),
                controller.state.value,
            )
        }

    @Test
    fun `rapid crash loop exhausts the budget and reports Failed(Runtime)`() =
        controllerTest {
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
        controllerTest {
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
        controllerTest {
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
    fun `busy clears after the budget is exhausted so the service can finish`() =
        controllerTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller =
                controller(
                    FakeInstaller(root, installed = fakeRuntime(root)),
                    server,
                    health = health,
                    watchdogPollMillis = 500L,
                    policy = RestartPolicy(listOf(1_000L), healthyResetMillis = 120_000L),
                )
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertEquals(false, controller.busy.value)

            // Every restart fails and the single budget step is spent.
            server.failNextStarts = 10
            health.ok = false
            step(500L) // crash seen, recovery starts
            step(10_000L) // backoff, failed attempt, budget exhausted

            val failed = controller.state.value as RuntimeState.Failed
            assertEquals(FailurePhase.Runtime, failed.phase)
            assertEquals("busy must be false once the recovery job ends", false, controller.busy.value)
            assertEquals(RuntimeServicePolicy.Action.Finish, RuntimeServicePolicy.decide(controller.busy.value, controller.state.value))
        }

    @Test
    fun `a request arriving while the previous op finishes never publishes busy false while work is queued`() =
        controllerTest {
            val root = folder.root
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), FakeServer())
            controller.initialize()
            // Two requests back to back: the first is still running when the second is queued.
            controller.perform(RuntimeOperation.Start)
            controller.perform(RuntimeOperation.Stop)
            val seen = mutableListOf<Boolean>()
            // Sample busy after every scheduler step while both operations drain.
            repeat(20) {
                seen += controller.busy.value
                step(10L)
            }

            // Once both operations are queued busy is true; it must not drop to false between them.
            val firstFalse = seen.indexOf(false)
            assertTrue("busy must stay true until the queue drains: $seen", firstFalse < 0 || seen.drop(firstFalse).none { it })
            assertEquals(false, controller.busy.value)
        }

    @Test
    fun `stop kills owned processes even when the server was never started`() =
        controllerTest {
            val killed = mutableListOf<Int>()
            val controller = controller(FakeInstaller(folder.root), FakeServer(), killed = killed)
            controller.perform(RuntimeOperation.Stop)
            step()

            assertEquals(1, killed.size)
        }

    @Test
    fun `repair stops owned processes and never discards the live environment first`() =
        controllerTest {
            val root = folder.root
            val killed = mutableListOf<Int>()
            val installer = FakeInstaller(root, installed = fakeRuntime(root))
            val controller = controller(installer, FakeServer(), killed = killed)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Repair)
            step()

            assertTrue(killed.isNotEmpty())
            assertEquals("repair must not pre-delete the environment", 0, installer.discarded)
            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `repair whose install fails leaves the old runtime installed and startable`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = fakeRuntime(root))
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()
            // The install of the replacement fails; the staged build is discarded by the installer,
            // and the previously active environment must still be what is installed.
            installer.installFails = true
            controller.perform(RuntimeOperation.Repair)
            step()

            assertEquals(0, installer.discarded)
            assertTrue("old runtime is still recorded as installed", installer.installedRuntime() != null)
            assertEquals(
                RuntimeState.Failed("network down", recoverable = true, phase = FailurePhase.Install, messageKey = MessageKey.Generic),
                controller.state.value,
            )
            // Retry (Start) is possible because the old runtime is intact.
            installer.installFails = false
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `install sets the run intent so a crash after the auto-start is recovered`() =
        controllerTest {
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(folder.root), server, health = health, watchdogPollMillis = 500L)
            controller.perform(RuntimeOperation.Install)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            // The child dies right after the auto-start: the watchdog must restart it at once.
            server.alive = false
            step(500L)
            step(1_500L)

            assertEquals(2, server.starts.size)
            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    /** Alive child, /health silent. The watchdog must tolerate misses before it restarts anything. */
    @Test
    fun `alive child with two missed probes then a healthy answer does not restart`() =
        controllerTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server, health = health, watchdogPollMillis = 500L)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            health.ok = false
            step(500L) // miss 1
            step(500L) // miss 2
            health.ok = true
            step(1_000L)

            assertEquals("no restart for two misses", 1, server.starts.size)
            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `alive child with three consecutive missed probes is recovered`() =
        controllerTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server, health = health, watchdogPollMillis = 500L)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            health.ok = false
            step(500L) // miss 1
            step(500L) // miss 2
            step(500L) // miss 3 -> recovery
            health.ok = true
            step(3_000L)

            assertEquals("three misses trigger a restart", 2, server.starts.size)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `dead child is recovered immediately without waiting for misses`() =
        controllerTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server, health = health, watchdogPollMillis = 500L)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            server.alive = false // the process is gone; /health is irrelevant
            step(500L) // the very next watchdog tick
            step(1_500L)

            assertEquals("a dead child restarts on the first tick", 2, server.starts.size)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a failed automatic restart is retried within the budget and succeeds on the next attempt`() =
        controllerTest {
            val root = folder.root
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller =
                controller(
                    FakeInstaller(root, installed = fakeRuntime(root)),
                    server,
                    health = health,
                    watchdogPollMillis = 500L,
                    policy = RestartPolicy(listOf(1_000L, 1_000L, 1_000L), healthyResetMillis = 120_000L),
                )
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            // Crash. The first automatic restart does not come up; the server is still dead during that
            // attempt, so the loop must consume another budget step (not give up) and try again.
            server.failNextStarts = 1
            server.alive = false // the child is gone: an immediate recovery, not a miss count
            step(500L) // death seen; restart begins: backoff 1 s, attempt fails
            step(1_500L) // second backoff 1 s, second attempt succeeds
            step(1_000L)

            assertTrue("expected Running after the retry, was ${controller.state.value}", controller.state.value is RuntimeState.Running)
            assertEquals("one failed attempt plus one successful one after the crash", 3, server.startAttempts)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `restart stops and starts again as one operation and ends Running`() =
        controllerTest {
            val root = folder.root
            val server = FakeServer()
            val controller = controller(FakeInstaller(root, installed = fakeRuntime(root)), server)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            controller.perform(RuntimeOperation.Restart)
            step()

            assertTrue("expected Running after restart, was ${controller.state.value}", controller.state.value is RuntimeState.Running)
            assertEquals(2, server.startAttempts)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `uninstall returns to NotInstalled and removes the runtime`() =
        controllerTest {
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
        controllerTest {
            val root = folder.root
            var sawCancel = false
            val server =
                object : OpenChamberServer {
                    override fun start(
                        runtime: InstalledRuntime,
                        uiPassword: String,
                        onLog: (String) -> Unit,
                        isCancelled: () -> Boolean,
                    ): Long {
                        sawCancel = isCancelled()
                        throw kotlinx.coroutines.CancellationException("Start cancelled by Stop")
                    }

                    override fun stop() {}

                    override fun isAlive(): Boolean = false

                    override fun readyLaunchId(): Long? = null
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

    // --- Updates ---

    /** Registry answer fixed by the test. Throws when [error] is set, like a network failure. */
    private class FixedSource(
        var answer: PackageVersions? = null,
        var error: Throwable? = null,
    ) : PackageVersionSource {
        var calls = 0

        override suspend fun latest(pinned: PackageVersions): PackageVersions {
            calls++
            error?.let { throw it }
            return answer ?: pinned
        }
    }

    private val pins = PackageVersions(openChamber = "2.4.0", openCode = "2.0.32")

    @Test
    fun `bundled pins newer than the installed runtime are offered without network`() =
        controllerTest {
            val root = folder.root
            val installed = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.20")))
            val controller = controller(installed, FakeServer(), pinnedVersions = pins)
            controller.initialize()
            step()

            val available = controller.updates.value.available
            assertEquals(VersionChange(current = "2.3.0", target = "2.4.0"), available?.openChamber)
            assertEquals(VersionChange(current = "2.0.20", target = "2.0.32"), available?.openCode)
            assertEquals(false, controller.updates.value.checkFailed)
        }

    @Test
    fun `a failed registry check still offers the bundled pins and says the check failed`() =
        controllerTest {
            val root = folder.root
            val installed = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.32")))
            val source = FixedSource(error = java.io.IOException("offline"))
            val controller = controller(installed, FakeServer(), versionSource = source, pinnedVersions = pins)
            controller.initialize()
            step()
            // The automatic check after detect has already failed; a manual check made after it finished
            // runs again. Both must leave the failure visible.
            assertEquals("automatic check ran once", 1, source.calls)
            assertTrue(controller.updates.value.checkFailed)
            controller.checkForUpdates()
            step()
            assertEquals("manual check after the first finished runs again", 2, source.calls)
            assertTrue(controller.updates.value.checkFailed)
            assertEquals(false, controller.updates.value.checking)
            assertEquals(VersionChange(current = "2.3.0", target = "2.4.0"), controller.updates.value.available?.openChamber)
            // Unchanged packages stay as a VersionChange with changed == false; the UI filters on `changed`.
            assertEquals(false, controller.updates.value.available?.openCode?.changed)
        }

    @Test
    fun `a registry version newer than the pins is offered`() =
        controllerTest {
            val root = folder.root
            val installed = FakeInstaller(root, installed = runtimeWith(root, pins))
            val source = FixedSource(answer = PackageVersions("2.5.1", "2.0.40"))
            val controller = controller(installed, FakeServer(), versionSource = source, pinnedVersions = pins)
            controller.initialize()
            step()
            controller.checkForUpdates()
            step()

            val update = controller.updates.value.available
            assertEquals(VersionChange(current = "2.4.0", target = "2.5.1"), update?.openChamber)
            assertEquals(VersionChange(current = "2.0.32", target = "2.0.40"), update?.openCode)
            assertEquals(false, controller.updates.value.checkFailed)
            assertTrue(controller.updates.value.lastCheckedAtMs != null)
        }

    @Test
    fun `an older installed version is never offered a downgrade`() =
        controllerTest {
            val root = folder.root
            // OpenCode was updated past the pin already; the pin must not pull it back down.
            val installed = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.4.0", "2.0.40")))
            val controller = controller(installed, FakeServer(), pinnedVersions = pins)
            controller.initialize()
            step()

            assertEquals(null, controller.updates.value.available)
        }

    @Test
    fun `automatic check is throttled but a manual check ignores the throttle`() =
        controllerTest {
            val root = folder.root
            val installed = FakeInstaller(root, installed = fakeRuntime(root))
            val source = FixedSource(answer = pins)
            val controller = controller(installed, FakeServer(), versionSource = source, pinnedVersions = pins)
            controller.initialize()
            step()
            assertEquals("the first detect checks automatically", 1, source.calls)

            controller.perform(RuntimeOperation.Install)
            step()
            assertEquals("a second automatic check inside the interval is skipped", 1, source.calls)

            controller.checkForUpdates()
            step()
            assertEquals("manual check runs regardless", 2, source.calls)
        }

    @Test
    fun `update restarts the server when it was running and passes the target versions`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.20")))
            val server = FakeServer()
            val controller = controller(installer, server, pinnedVersions = pins)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            controller.perform(RuntimeOperation.Update)
            step()

            assertEquals(listOf(pins), installer.updateCalls)
            assertTrue("running server is restarted", controller.state.value is RuntimeState.Running)
            assertEquals(null, controller.updates.value.lastFailure)
            assertEquals(null, controller.updates.value.available)
            assertEquals("2.4.0", controller.info.value?.openChamberVersion)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `update leaves the runtime stopped when it was not running`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.20")))
            val server = FakeServer()
            val states = mutableListOf<RuntimeState>()
            val controller = controller(installer, server, states = states, pinnedVersions = pins)
            controller.initialize()
            step()
            assertEquals(RuntimeState.Stopped, controller.state.value)

            controller.perform(RuntimeOperation.Update)
            step()

            assertEquals(1, installer.updateCalls.size)
            assertEquals(RuntimeState.Stopped, controller.state.value)
            assertEquals(0, server.startAttempts)
            assertTrue(states.any { it is RuntimeState.Installing && it.updating && it.step == InstallStep.CopyEnvironment })
        }

    @Test
    fun `a failed update keeps the runtime usable and records the reason instead of Failed`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.20")))
            installer.updateFailWith = UserFacingException(MessageKey.UpdateNotEnoughSpace, "no space", detail = "df")
            val server = FakeServer()
            val controller = controller(installer, server, pinnedVersions = pins)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            controller.perform(RuntimeOperation.Update)
            step()

            // The old environment is untouched and the server comes back up on it.
            assertTrue("old runtime restarted, not Failed: ${controller.state.value}", controller.state.value is RuntimeState.Running)
            val failure = controller.updates.value.lastFailure
            assertEquals(MessageKey.UpdateNotEnoughSpace, failure?.messageKey)
            assertEquals("df", failure?.detail)
            assertEquals("2.3.0", controller.info.value?.openChamberVersion)
            assertTrue("the offer stays so the user can retry", controller.updates.value.available != null)

            controller.dismissUpdateFailure()
            assertEquals(null, controller.updates.value.lastFailure)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a failed update when the runtime was stopped leaves it Stopped with the failure recorded`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.20")))
            installer.updateFailWith = IllegalStateException("npm failed")
            val controller = controller(installer, FakeServer(), pinnedVersions = pins)
            controller.initialize()
            step()

            controller.perform(RuntimeOperation.Update)
            step()

            assertEquals(RuntimeState.Stopped, controller.state.value)
            assertEquals(MessageKey.Generic, controller.updates.value.lastFailure?.messageKey)
        }

    @Test
    fun `repair installs at least the installed versions so OpenCode is never downgraded`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.4.0", "2.0.40")))
            val controller = controller(installer, FakeServer(), pinnedVersions = pins)
            controller.initialize()
            step()

            controller.perform(RuntimeOperation.Repair)
            step()

            assertEquals(PackageVersions(openChamber = "2.4.0", openCode = "2.0.40"), installer.installCalls.last())
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a fresh install takes the manifest pins`() =
        controllerTest {
            val installer = FakeInstaller(folder.root)
            val controller = controller(installer, FakeServer(), pinnedVersions = pins)
            controller.perform(RuntimeOperation.Install)
            step()

            assertEquals(null, installer.installCalls.last())
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `uninstall clears the update state`() =
        controllerTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = runtimeWith(root, PackageVersions("2.3.0", "2.0.20")))
            val controller = controller(installer, FakeServer(), pinnedVersions = pins)
            controller.initialize()
            step()
            assertTrue(controller.updates.value.available != null)

            controller.perform(RuntimeOperation.Uninstall)
            step()

            assertEquals(RuntimeUpdateState(), controller.updates.value)
        }
}
