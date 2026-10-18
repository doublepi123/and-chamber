package io.andchamber.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessTreeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `process tree termination order is children before parent`() {
        val children =
            mapOf(
                10L to listOf(11L, 12L),
                11L to listOf(13L),
                12L to emptyList(),
                13L to emptyList(),
            )

        assertEquals(listOf(13L, 11L, 12L, 10L), processTreePostOrder(10L) { children[it].orEmpty() })
    }

    @Test
    fun `finds managed proot roots by runtime directory marker only`() {
        val procRoot = folder.newFolder("proc")
        val runtimeDirectory = folder.newFolder("runtime")
        procRoot.resolve("100").mkdirs()
        procRoot.resolve("100/cmdline").writeText(
            "${EmbeddedCommandSuite.PROOT_LIBRARY_NAME}\u0000-r\u0000${runtimeDirectory.resolve("environment/rootfs").path}",
        )
        procRoot.resolve("101").mkdirs()
        procRoot.resolve("101/cmdline").writeText("node\u0000cli.js")
        procRoot.resolve("200").mkdirs()
        procRoot.resolve("200/cmdline").writeText("unrelated")

        assertEquals(listOf(100L), findManagedRuntimeRootPids(runtimeDirectory, procRoot))
    }

    @Test
    fun `direct children are read from proc stat ignoring spaces in the command name`() {
        val procRoot = folder.newFolder("proc2")
        procRoot.resolve("50").mkdirs()
        procRoot.resolve("50/stat").writeText("50 (proot launcher) S 42 50 50 0 -1 0")
        procRoot.resolve("51").mkdirs()
        procRoot.resolve("51/stat").writeText("51 (node) S 99 51 51 0 -1 0")

        assertEquals(listOf(50L), readDirectChildPids(42L, procRoot))
        assertEquals(42L, parentPidOf(procRoot.resolve("50/stat")))
    }

    @Test
    fun `log truncation keeps recent content when file exceeds max bytes`() {
        val logFile = folder.newFile("openchamber.log")
        val line = "abcdefghijklmnopqrstuvwxyz\n"
        logFile.writeText(List(20) { "$it$line" }.joinToString(""))

        truncateLogFile(logFile, 100L)

        val remaining = logFile.readText()
        assertTrue("truncated log is too large: ${remaining.length}", remaining.length <= 50 + 30)
        assertTrue(remaining.contains("19$line") || remaining.contains("18$line"))
    }
}
