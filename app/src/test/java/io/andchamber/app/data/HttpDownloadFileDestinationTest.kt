package io.andchamber.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpDownloadFileDestinationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun write(
        pending: PendingDownload,
        text: String,
    ) {
        pending.openStream().use { it.write(text.toByteArray()) }
    }

    @Test
    fun commitPublishesUnderFinalNameWithoutOverwriting() {
        val dir = folder.newFolder("Downloads")
        File(dir, "a.txt").writeText("old")
        val destination = FileDownloadDestination(dir)

        val pending = destination.create("a.txt", "text/plain")
        write(pending, "new")
        val saved = pending.commit()

        assertEquals("a (1).txt", saved)
        assertEquals("old", File(dir, "a.txt").readText())
        assertEquals("new", File(dir, "a (1).txt").readText())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun abortRemovesPartialFile() {
        val dir = folder.newFolder("Partial")
        val destination = FileDownloadDestination(dir)

        val pending = destination.create("b.bin", "application/octet-stream")
        write(pending, "half")
        pending.abort()

        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun abortBeforeAnyWriteIsSafe() {
        val dir = folder.newFolder("Empty")
        FileDownloadDestination(dir).create("c.bin", "application/octet-stream").abort()
        assertEquals(0, dir.listFiles().orEmpty().size)
    }
}
