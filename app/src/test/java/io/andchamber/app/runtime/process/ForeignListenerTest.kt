package io.andchamber.app.runtime.process

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.install.InstalledRuntime
import io.andchamber.app.runtime.install.RuntimeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /**
     * A child whose stdout stays open until [destroy]. A byte-array stream would EOF while the
     * process is still alive, so it cannot represent a running server.
     */
    private class OpenOutputChild(
        output: String,
    ) : Process() {
        private val pipe = java.io.PipedInputStream()
        private val writer = java.io.PipedOutputStream(pipe)
        private val alive = java.util.concurrent.atomic.AtomicBoolean(true)

        init {
            writer.write(output.toByteArray())
            writer.flush()
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = pipe

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (isAlive) Thread.sleep(5)
            return 0
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            alive.set(false)
            writer.close()
        }

        /** Ends stdout without marking the process dead, for a late pump after a replacement launch. */
        fun endOutput() {
            writer.close()
        }

        override fun isAlive(): Boolean = alive.get()
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
        maxLogBytes: Long = 1_048_576L,
        notificationSink: (String) -> Unit = {},
        browserMcpAsset: () -> String = { "browser-mcp-fixture" },
        invalidateBrowserEndpoint: (File, File) -> Boolean = { home, root ->
            BrowserToolAssets.invalidateEndpoint(home, root)
        },
        onBrowserLaunchInvalidated: (Long) -> Unit = {},
        onBrowserUnavailable: (String) -> Unit = {},
        spawnOverride: (() -> Process)? = null,
        capturedEnv: MutableList<Map<String, String>> = mutableListOf(),
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
        maxLogBytes = maxLogBytes,
        notificationSink = notificationSink,
        browserMcpAsset = browserMcpAsset,
        browserTrustedRoot = folder.root,
        invalidateBrowserEndpoint = invalidateBrowserEndpoint,
        onBrowserLaunchInvalidated = onBrowserLaunchInvalidated,
        onBrowserUnavailable = onBrowserUnavailable,
        sleep = { Thread.sleep(1) },
        spawn = { _, env, _ ->
            capturedEnv += env
            spawned += true
            spawnOverride?.invoke() ?: child
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
    fun `start deletes the old credential and syncs the adapter before spawn, without putting a token in env`() {
        val home = File(folder.root, "home")
        val credential = BrowserToolAssets.endpointHostFile(home)
        credential.parentFile.mkdirs()
        credential.writeText("""{"token":"old-lease","endpoint":"http://127.0.0.1/"}""")
        val child = FakeChild(
            output = "OpenChamber server listening on 127.0.0.1:$port\n",
            exitCode = 0,
            lifetimeMillis = 10_000L,
        )
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val spawned = mutableListOf<Boolean>()
        val captured = mutableListOf<Map<String, String>>()
        val order = mutableListOf<String>()

        server(
            child,
            RecordingHealth(ok),
            spawned,
            browserMcpAsset = {
                order += "sync"
                "latest-mcp"
            },
            invalidateBrowserEndpoint = { target, root ->
                order += "invalidate"
                BrowserToolAssets.invalidateEndpoint(target, root)
            },
            capturedEnv = captured,
        ).start(runtime(), "ui-secret", {}, { false })

        assertEquals(listOf("invalidate", "sync"), order)
        assertFalse(java.nio.file.Files.exists(credential.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("latest-mcp", File(home, BrowserToolAssets.SCRIPT_RELATIVE).readText())
        assertEquals(BrowserToolAssets.GUEST_ENDPOINT_FILE, captured.single()[BrowserToolAssets.ENDPOINT_ENV])
        assertFalse(captured.single().values.any { it.contains("old-lease") })
        assertEquals("ui-secret", captured.single()["OPENCHAMBER_UI_PASSWORD"])
    }

    @Test
    fun `browser prepare failure still starts OpenChamber and injects no browser config`() {
        val child = FakeChild(
            output = "OpenChamber server listening on 127.0.0.1:$port\n",
            exitCode = 0,
            lifetimeMillis = 10_000L,
        )
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val captured = mutableListOf<Map<String, String>>()
        val reasons = mutableListOf<String>()

        val launchId = server(
            child,
            RecordingHealth(ok),
            mutableListOf(),
            browserMcpAsset = { error("asset missing") },
            invalidateBrowserEndpoint = { _, _ -> false },
            capturedEnv = captured,
            onBrowserUnavailable = { reasons += it },
        ).start(runtime(), "ui-secret", {}, { false })

        assertTrue(launchId > 0)
        assertEquals(1, reasons.size)
        assertFalse(reasons.single().contains("asset missing"))
        assertFalse(captured.single().containsKey(BrowserToolAssets.ENDPOINT_ENV))
        assertFalse(captured.single().containsKey(BrowserToolAssets.CONFIG_ENV))
    }

    @Test
    fun `stop revokes the current lease and a dead previous pump does not revoke the next launch`() {
        val first = OpenOutputChild("OpenChamber server listening on 127.0.0.1:$port\n")
        val second = OpenOutputChild("OpenChamber server listening on 127.0.0.1:$port\n")
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val revoked = mutableListOf<Long>()
        val children = ArrayDeque<Process>()
        children.add(first)
        children.add(second)
        val created = server(
            first,
            RecordingHealth(ok),
            mutableListOf(),
            onBrowserLaunchInvalidated = { revoked += it },
            spawnOverride = { children.removeFirst() },
        )
        val launchA = created.start(runtime(), "ui-secret", {}, { false })
        assertTrue(first.isAlive)
        assertFalse(revoked.contains(launchA))

        created.stop()
        val stopped = revoked.toList()
        assertEquals(listOf(launchA), stopped)

        val launchB = created.start(runtime(), "ui-secret", {}, { false })
        first.endOutput()
        Thread.sleep(50)

        assertFalse(revoked.contains(launchB))
        assertEquals(launchB, created.readyLaunchId())
        assertEquals(1, revoked.count { it == launchA })
        assertFalse(revoked.drop(stopped.size).contains(launchA))
        second.destroy()
    }

    @Test
    fun `readyLaunchId is a pure getter and a late revoke for A does not revoke B`() {
        val first = FakeChild(
            output = "OpenChamber server listening on 127.0.0.1:$port\n",
            exitCode = 0,
            lifetimeMillis = 10_000L,
        )
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val revoked = mutableListOf<Long>()
        val created = server(
            first,
            RecordingHealth(ok),
            mutableListOf(),
            onBrowserLaunchInvalidated = { revoked += it },
        )
        val launchA = created.start(runtime(), "ui-secret", {}, { false })
        val callbacksBeforeRead = revoked.toList()
        val seen = created.readyLaunchId()
        assertEquals(launchA, seen)
        assertEquals(callbacksBeforeRead, revoked)

        created.stop()
        val afterStop = revoked.toList()
        created.readyLaunchId()
        assertEquals(afterStop, revoked)
        assertTrue(afterStop.contains(launchA))
        assertFalse(afterStop.contains(launchA + 1))
    }

    @Test
    fun `launch invalidation runs before the credential delete and a later enable is not revoked`() {
        val child = FakeChild(
            output = "OpenChamber server listening on 127.0.0.1:$port\n",
            exitCode = 0,
            lifetimeMillis = 10_000L,
        )
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        var enabledAfter = false
        var revoked = 0

        server(
            child,
            RecordingHealth(ok),
            mutableListOf(),
            browserMcpAsset = { "fixture" },
            invalidateBrowserEndpoint = { _, _ ->
                enabledAfter = true
                true
            },
            onBrowserLaunchInvalidated = { _ ->
                revoked += 1
                check(!enabledAfter) { "revocation ran after a newer credential could exist" }
            },
        ).start(runtime(), "ui-secret", {}, { false })

        assertEquals(1, revoked)
        assertTrue(enabledAfter)
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

    /**
     * Desktop-notify lines go to the sink and nowhere else: not the log callback, not the diagnostic
     * tail. Other lines still reach the log callback.
     */
    @Test
    fun `desktop-notify lines are routed to the sink and kept out of the log and the tail`() {
        val notifyLine = "[OpenChamberDesktopNotify] {\"kind\":\"ready\",\"title\":\"t\",\"tag\":\"ready-ses_1\"}"
        val child =
            FakeChild(
                output = "booting\n$notifyLine\nOpenChamber server listening on 127.0.0.1:$port\n",
                exitCode = 0,
                lifetimeMillis = 10_000L,
            )
        val sunk = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val ok = HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours")
        val srv = serverWithSink(child, RecordingHealth(ok), sunk)

        srv.start(runtime(), "ui-secret", { logged += it }, { false })
        Thread.sleep(100)

        assertEquals(listOf(notifyLine), sunk)
        assertFalse("the raw notify line must not be logged", logged.any { it.startsWith("[OpenChamberDesktopNotify]") })
        assertTrue(logged.contains("booting"))
    }

    @Test
    fun `output pump bounds persisted log while routing notification lines away`() {
        val notifyLine = "[OpenChamberDesktopNotify] private notification payload"
        val lastLine = "PERSISTED-LAST-MARKER"
        val child = FakeChild(
            output = buildString {
                append("OpenChamber server listening on 127.0.0.1:$port\n")
                repeat(12) { append("ordinary-output-line-$it\n") }
                append("$notifyLine\n")
                append("$lastLine\n")
            },
            exitCode = 0,
            lifetimeMillis = 10_000L,
        )
        val health = RecordingHealth(
            HealthReport("ok", isOpenCodeReady = true, openCodeRunning = true, lastOpenCodeError = null, serverId = "ours"),
        )
        val notifySeen = CountDownLatch(1)
        val finalLogged = CountDownLatch(1)
        val logLines = mutableListOf<String>()
        val server = server(
            child,
            health,
            mutableListOf(),
            maxLogBytes = 96,
            notificationSink = { if (it == notifyLine) notifySeen.countDown() },
        )
        try {
            server.start(runtime(), "ui-secret", {
                synchronized(logLines) { logLines += it }
                if (it == lastLine) finalLogged.countDown()
            }, { false })
            assertTrue("output pump did not log final line", finalLogged.await(2, TimeUnit.SECONDS))
            assertTrue("notification line was not routed", notifySeen.await(2, TimeUnit.SECONDS))

            val log = RuntimePaths(folder.root).serverLog
            assertTrue("server log exceeded cap: ${log.length()}", log.length() <= 96L)
            val persisted = log.readText()
            assertTrue(persisted.contains(lastLine))
            assertFalse(persisted.contains("OpenChamberDesktopNotify"))
            assertFalse(synchronized(logLines) { logLines.any { it.startsWith("[OpenChamberDesktopNotify]") } })
        } finally {
            server.stop()
        }
    }

    private fun serverWithSink(
        child: Process,
        health: HealthProbe,
        sunk: MutableList<String>,
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
        scanner = ProcessScanner(folder.newFolder("proc-sink"), myUid = 1, myPid = 2L),
        cleanBeforeStart = { _, _, _ -> true },
        readyTimeoutMillis = 2_000L,
        pollMillis = 1L,
        sleep = { Thread.sleep(1) },
        spawn = { _, _, _ -> child },
        notificationSink = { sunk += it },
        browserMcpAsset = { "browser-mcp-fixture" },
        browserTrustedRoot = folder.root,
    )

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
