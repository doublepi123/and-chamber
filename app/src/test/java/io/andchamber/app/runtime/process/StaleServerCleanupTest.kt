package io.andchamber.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StaleServerCleanupTest {
    @get:Rule val folder = TemporaryFolder()

    private val lib = "/data/app/x/lib/arm64/libandchamber_android_proot.so"
    private val root = "/data/user/0/io.andchamber.app/files/runtime"

    @Test
    fun `pid file name matches OpenChamber's run dir convention`() {
        assertEquals("openchamber-3000.pid", StaleServerCleanup.pidFileName(3000))
    }

    @Test
    fun `only the stale pid file for the requested port is selected`() {
        val run = folder.newFolder("run")
        val stale = run.resolve("openchamber-3000.pid").apply { writeText("6837") }
        val other = run.resolve("openchamber-4000.pid").apply { writeText("1") }

        val selected = StaleServerCleanup.staleFiles(run, 3000)

        assertEquals(listOf(stale), selected)
        assertTrue(other.exists())
    }

    @Test
    fun `no run dir means nothing to delete`() {
        assertTrue(StaleServerCleanup.staleFiles(File(folder.root, "missing"), 3000).isEmpty())
    }

    @Test
    fun `a process is ours when its command line runs our proot or works in our root`() {
        assertTrue(StaleServerCleanup.belongsToRuntime("$lib\u0000-r\u0000$root/environment/rootfs", lib, root))
        assertTrue(StaleServerCleanup.belongsToRuntime("sh\u0000-c\u0000$root/x", lib, root))
    }

    @Test
    fun `unrelated processes are never matched`() {
        assertFalse(StaleServerCleanup.belongsToRuntime("node\u0000cli.js\u0000serve", lib, root))
        assertFalse(StaleServerCleanup.belongsToRuntime("/system/bin/app_process", lib, root))
    }

    @Test
    fun `own runtime pids are found under proc and others are ignored`() {
        val proc = folder.newFolder("proc")
        proc.resolve("100").mkdirs()
        proc.resolve("100/cmdline").writeText("$lib\u0000-r\u0000$root/environment/rootfs")
        proc.resolve("200").mkdirs()
        proc.resolve("200/cmdline").writeText("unrelated")
        proc.resolve("abc").mkdirs()

        assertEquals(listOf(100L), StaleServerCleanup.ownRuntimePids(proc, lib, root))
    }

    @Test
    fun `port wait returns as soon as the port frees`() {
        var checks = 0
        var slept = 0L
        val free =
            StaleServerCleanup.awaitPortFree(
                port = 3000,
                timeoutMillis = 5_000L,
                isPortFree = { checks++ >= 2 },
                sleep = { slept += it },
                now = { slept },
            )

        assertTrue(free)
        assertEquals(3, checks)
    }

    @Test
    fun `port wait gives up after the timeout`() {
        var clock = 0L
        val free =
            StaleServerCleanup.awaitPortFree(
                port = 3000,
                timeoutMillis = 1_000L,
                isPortFree = { false },
                sleep = { clock += it },
                now = { clock },
            )

        assertFalse(free)
        assertTrue(clock >= 1_000L)
    }
}
