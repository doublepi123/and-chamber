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
