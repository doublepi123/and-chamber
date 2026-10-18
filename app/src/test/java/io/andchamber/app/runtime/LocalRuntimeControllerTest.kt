package io.andchamber.app.runtime

import io.andchamber.app.runtime.install.InstallReporter
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeInstaller
import io.andchamber.app.runtime.install.RuntimeMetadata
import io.andchamber.app.runtime.process.HealthProbe
import io.andchamber.app.runtime.process.OpenChamberServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeControllerTest {

    @get:Rule val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    /** Runs queued controller work. The watchdog is disabled in these tests, so the scheduler goes idle. */
    private fun TestScope.settle() = advanceUntilIdle()

    @After
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    /** runTest that cancels the controller scopes before it drains the scheduler. */
    private fun controllerTest(body: suspend TestScope.() -> Unit) =
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
        var discarded = 0
        var removed = 0

        override fun recoverInterruptedActivation() {}

        override fun installedRuntime(): InstalledRuntime? = installed

        override suspend fun install(report: InstallReporter): InstalledRuntime {
            report.step(InstallStep.DownloadRootfs, 0.5f, "half")
            if (installFails) error("network down")
            report.log("installed")
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

    private class FakeServer(
        var failOnStart: Boolean = false,
    ) : OpenChamberServer {
        val starts = mutableListOf<String>()
        var alive = false
        var stops = 0

        override fun start(
            runtime: InstalledRuntime,
            uiPassword: String,
            onLog: (String) -> Unit,
        ) {
            if (failOnStart) error("OpenChamber exited during startup")
            starts += uiPassword
            alive = true
            onLog("serving")
        }

        override fun stop() {
            stops++
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }

    /**
     * Every controller gets a fresh scope on the test scheduler. backgroundScope does not run its
     * work when the test body calls advanceUntilIdle, so an explicit scope is used and cancelled
     * after each test to stop the watchdog loop.
     */
    private fun TestScope.controller(
        scope: TestScope,
        installer: FakeInstaller,
        server: FakeServer,
        healthy: Boolean = true,
        abi: String = "arm64-v8a",
        states: MutableList<RuntimeState>? = null,
    ): DefaultLocalRuntimeController {
        val paths = io.andchamber.app.runtime.RuntimePaths(folder.root)
        return DefaultLocalRuntimeController(
            paths = paths,
            abi = abi,
            installer = installer,
            server = server,
            health = HealthProbe { healthy },
            uiPassword = { "ui-secret" },
            listener = RuntimeStateListener { state -> states?.add(state) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it },
            nowMillis = { 42L },
            watchdogPollMillis = 10L,
            watchdogEnabled = false,
        )
    }

    @Test
    fun `fresh device with no install reports NotInstalled`() =
        runTest {
            val controller = controller(this, FakeInstaller(folder.root), FakeServer())
            controller.initialize()
            settle()
            assertEquals(RuntimeState.NotInstalled, controller.state.value)
        }

    @Test
    fun `existing install detected at startup is Stopped with info`() =
        runTest {
            val root = folder.root
            val controller = controller(this, FakeInstaller(root, installed = fakeRuntime(root)), FakeServer())
            controller.initialize()
            settle()
            assertEquals(RuntimeState.Stopped, controller.state.value)
            assertEquals("2.4.0", controller.info.value?.openChamberVersion)
        }

    @Test
    fun `unsupported abi short-circuits to UnsupportedAbi`() =
        runTest {
            val controller = controller(this, FakeInstaller(folder.root), FakeServer(), abi = "armeabi-v7a")
            controller.initialize()
            settle()
            assertEquals(RuntimeState.UnsupportedAbi, controller.state.value)
        }

    @Test
    fun `install goes through Installing to Running and passes the UI password`() =
        runTest {
            val server = FakeServer()
            val states = mutableListOf<RuntimeState>()
            val controller = controller(this, FakeInstaller(folder.root), server, states = states)
            controller.install()
            settle()

            assertTrue(states.any { it is RuntimeState.Installing && it.step == InstallStep.DownloadRootfs })
            assertEquals(RuntimeState.Running("http://127.0.0.1:3000", 3000, 42L), controller.state.value)
            assertEquals(listOf("ui-secret"), server.starts)
            assertEquals("http://127.0.0.1:3000", (controller.state.value as RuntimeState.Running).url)
        }

    @Test
    fun `start failure becomes recoverable Failed with the reason`() =
        runTest {
            val root = folder.root
            val controller =
                controller(this, FakeInstaller(root, installed = fakeRuntime(root)), FakeServer(failOnStart = true))
            controller.initialize()
            settle()
            controller.start()
            settle()

            val failed = controller.state.value as RuntimeState.Failed
            assertTrue(failed.reason.contains("exited during startup"))
            assertTrue(failed.recoverable)
        }

    @Test
    fun `install failure becomes Failed and logs the error`() =
        runTest {
            val controller = controller(this, FakeInstaller(folder.root, installFails = true), FakeServer())
            controller.install()
            settle()

            assertEquals(
                RuntimeState.Failed("network down", recoverable = true, phase = FailurePhase.Install),
                controller.state.value,
            )
            assertTrue(controller.logTail.value.any { it.contains("network down") })
        }

    @Test
    fun `stop ends Stopped and terminates the server`() =
        runTest {
            val root = folder.root
            val server = FakeServer()
            val controller = controller(this, FakeInstaller(root, installed = fakeRuntime(root)), server)
            controller.initialize()
            settle()
            controller.start()
            settle()
            controller.stop()
            settle()

            assertEquals(RuntimeState.Stopped, controller.state.value)
            assertEquals(1, server.stops)
        }

    @Test
    fun `repair discards the environment then reinstalls and runs`() =
        runTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = fakeRuntime(root))
            val controller = controller(this, installer, FakeServer())
            controller.initialize()
            settle()
            controller.repair()
            settle()

            assertEquals(1, installer.discarded)
            assertEquals(RuntimeState.Running("http://127.0.0.1:3000", 3000, 42L), controller.state.value)
        }

    @Test
    fun `uninstall returns to NotInstalled and removes the runtime`() =
        runTest {
            val root = folder.root
            val installer = FakeInstaller(root, installed = fakeRuntime(root))
            val controller = controller(this, installer, FakeServer())
            controller.initialize()
            settle()
            controller.uninstall()
            settle()

            assertEquals(RuntimeState.NotInstalled, controller.state.value)
            assertEquals(1, installer.removed)
        }

}
