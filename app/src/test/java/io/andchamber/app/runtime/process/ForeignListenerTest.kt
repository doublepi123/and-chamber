package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

/**
 * Port identity. A foreign process may hold the OpenChamber port. Trust order in readiness is:
 * our child's own output (listen line) first, then /health. These tests prove a foreign answer is
 * never trusted and the password is never sent.
 */
class ForeignListenerTest {
    @get:Rule val folder = TemporaryFolder()

    private val port = 3000

    private fun runtime() =
        InstalledRuntime(
            metadata =
                RuntimeMetadata(
                    runtimeVersion = "r",
                    alpineVersion = "3.24.1",
                    abi = "arm64-v8a",
                    port = port,
                    openChamberVersion = "2.4.0",
                    openCodeVersion = "2.0.32",
                    installedAt = 1L,
                ),
            rootfs = File(folder.root, "rootfs"),
        )

    /** A stand-in for our PRoot child: fixed output, then an exit code. */
    private class FakeChild(
        private val output: String,
        private val exitCode: Int,
        private val lifetimeMillis: Long = 0L,
    ) : Process() {
        private val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        private val startedAt = System.nanoTime()

        init {
            // Exits after its lifetime; checked lazily so isAlive reflects the schedule.
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (isAlive) Thread.sleep(5)
            return exitValue()
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() {
            alive.set(false)
        }

        override fun isAlive(): Boolean {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsed >= lifetimeMillis) alive.set(false)
            return alive.get()
        }
    }

    /** Health probe that records every call; a foreign server answering counts as a call. */
    private class RecordingHealth(
        private val report: HealthReport?,
    ) : HealthProbe {
        var calls = 0

        override fun read(port: Int): HealthReport? {
            calls++
            return report
        }
    }

    private fun foreignOk() = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "foreign")

    private fun server(
        child: Process,
        health: HealthProbe,
        spawned: MutableList<Boolean>,
        precheckFree: Boolean = true,
    ) = ProotOpenChamberServer(
        paths = RuntimePaths(folder.root),
        suite = {
            EmbeddedCommandSuite.Paths(
                home = folder.root,
                tmp = folder.root,
                nativeLibraryDirectory = folder.root,
                proot = File(folder.root, "libandchamber_android_proot.so"),
                loader = folder.root,
                loader32 = folder.root,
            )
        },
        health = health,
        scanner = ProcessScanner(folder.newFolder("proc"), myUid = 1, myPid = 2L),
        cleanBeforeStart = { _, _, _ -> precheckFree },
        readyTimeoutMillis = 2_000L,
        pollMillis = 1L,
        sleep = { Thread.sleep(1) },
        spawn = { _, _, _ ->
            spawned += true
            child
        },
    )

    @Test
    fun `port taken after the precheck - our child exits with EADDRINUSE and no password is sent`() {
        ServerSocket(0).use { foreign ->
            // Precheck passed (port looked free), a foreign listener binds, then our child fails.
            val child = FakeChild(output = "Error: listen EADDRINUSE: address already in use 127.0.0.1:$port\n", exitCode = 1)
            val health = RecordingHealth(foreignOk())
            val spawned = mutableListOf<Boolean>()

            val error =
                runCatching {
                    server(child, health, spawned).start(runtime(), "ui-secret", {}, { false })
                }.exceptionOrNull()

            assertTrue("expected PortInUseException, got $error", error is PortInUseException)
            assertEquals("Port 3000 is in use by another app", error?.message)
            assertEquals(1, spawned.size)
            assertTrue("foreign /health must never be trusted without our listen line", health.calls == 0)
            assertTrue(foreign.isBound)
        }
    }

    @Test
    fun `a foreign server answering ok is not trusted when our child printed no listen line`() {
        // Our child is alive but silent (never printed the listen line); a foreign /health answers ok.
        val child = FakeChild(output = "", exitCode = 0, lifetimeMillis = 10_000L)
        val health = RecordingHealth(foreignOk())
        val spawned = mutableListOf<Boolean>()

        val error =
            runCatching {
                server(child, health, spawned).start(runtime(), "ui-secret", {}, { false })
            }.exceptionOrNull()

        assertTrue("start must fail, got $error", error != null)
        assertEquals("/health must not be consulted before our listen line", 0, health.calls)
    }

    @Test
    fun `our child listening and healthy is trusted and returns a launch id`() {
        val child = FakeChild(output = "OpenChamber server listening on 127.0.0.1:$port\n", exitCode = 0, lifetimeMillis = 10_000L)
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val spawned = mutableListOf<Boolean>()

        val launchId = server(child, RecordingHealth(ok), spawned).start(runtime(), "ui-secret", {}, { false })

        assertTrue(launchId > 0)
    }

    @Test
    fun `readyLaunchId is null as soon as our child is dead, without any watchdog tick`() {
        // Ready: the listen line and a healthy answer. Then the child exits and nothing has polled yet.
        val child = FakeChild(output = "OpenChamber server listening on 127.0.0.1:$port\n", exitCode = 0, lifetimeMillis = 300L)
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val srv = server(child, RecordingHealth(ok), mutableListOf())

        val id = srv.start(runtime(), "ui-secret", {}, { false })
        assertEquals(id, srv.readyLaunchId())

        // Wait for the fake child to exit. No readyLaunchId() poll happens in between.
        while (child.isAlive) Thread.sleep(5)

        assertEquals(null, srv.readyLaunchId())
    }

    /**
     * Android throws InterruptedIOException from the child's stream when stop closes it mid-read. The
     * output thread must absorb that: an uncaught exception there crashes the whole app.
     */
    @Test
    fun `output pump survives a stream closed mid-read without an uncaught exception`() {
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error) }
        try {
            val closing =
                object : Process() {
                    private val stream =
                        object : java.io.InputStream() {
                            private var reads = 0

                            override fun read(): Int {
                                if (reads++ == 0) return 'O'.code
                                throw java.io.InterruptedIOException("read interrupted by close() on another thread")
                            }
                        }

                    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

                    override fun getInputStream(): InputStream = stream

                    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

                    override fun waitFor(): Int = 0

                    override fun exitValue(): Int = 0

                    override fun destroy() {}

                    override fun isAlive(): Boolean = false
                }
            val srv = server(closing, RecordingHealth(null), mutableListOf())
            runCatching { srv.start(runtime(), "ui-secret", {}, { false }) }
            Thread.sleep(200)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals("no uncaught exception may escape the output thread", null, uncaught.get())
    }

    @Test
    fun `the listen marker must match our port`() {
        assertTrue(Readiness.childListening(listOf("OpenChamber server listening on 127.0.0.1:3000"), 3000))
        assertTrue(!Readiness.childListening(listOf("OpenChamber server listening on 127.0.0.1:3001"), 3000))
    }

    @Test
    fun `precheck failure never spawns the child`() {
        val spawned = mutableListOf<Boolean>()
        val error =
            runCatching {
                server(FakeChild("", 0), RecordingHealth(null), spawned, precheckFree = false)
                    .start(runtime(), "ui-secret", {}, { false })
            }.exceptionOrNull()

        assertTrue(error is PortInUseException)
        assertEquals(0, spawned.size)
    }
}
