package io.andchamber.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

class OpenChamberPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun rootfs() = temporaryFolder.newFolder("rootfs")

    @Test
    fun `writes the exact pinned OpenCode policy and creates the parent directories`() {
        val rootfs = rootfs()

        OpenChamberPolicy.write(rootfs)

        val file = rootfs.resolve("etc/openchamber/policy.json")
        assertTrue(file.isFile)
        assertEquals("{\"opencodeBinary\":\"/usr/local/bin/opencode\"}\n", file.readText())
    }

    @Test
    fun `the file parses as JSON and has only the opencodeBinary key`() {
        val rootfs = rootfs()
        OpenChamberPolicy.write(rootfs)

        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(
            rootfs.resolve("etc/openchamber/policy.json").readText(),
        ) as kotlinx.serialization.json.JsonObject

        assertEquals(setOf("opencodeBinary"), parsed.keys)
        assertEquals("/usr/local/bin/opencode", (parsed["opencodeBinary"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `replaces a symlink at the target without writing through to its destination`() {
        val rootfs = rootfs()
        val dir = rootfs.resolve("etc/openchamber").apply { mkdirs() }
        val outside = temporaryFolder.newFolder("outside").resolve("keep.json").apply { writeText("precious") }
        Files.createSymbolicLink(dir.resolve("policy.json").toPath(), outside.toPath().toAbsolutePath())

        OpenChamberPolicy.write(rootfs)

        val target = dir.resolve("policy.json").toPath()
        assertFalse(Files.isSymbolicLink(target))
        assertEquals("{\"opencodeBinary\":\"/usr/local/bin/opencode\"}\n", dir.resolve("policy.json").readText())
        assertEquals("precious", outside.readText())
        assertTrue(Files.exists(outside.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a second write with identical content leaves the file untouched`() {
        val rootfs = rootfs()
        OpenChamberPolicy.write(rootfs)
        val file = rootfs.resolve("etc/openchamber/policy.json").toPath()
        // Mark the mtime in the past: an identical rewrite would reset it to now, so a kept mtime shows the skip.
        val marked = java.nio.file.attribute.FileTime.fromMillis(1_000_000L)
        Files.setLastModifiedTime(file, marked)

        OpenChamberPolicy.write(rootfs)

        assertEquals(marked, Files.getLastModifiedTime(file))
        assertEquals(OpenChamberPolicy.CONTENT, rootfs.resolve("etc/openchamber/policy.json").readText())
    }

    @Test
    fun `a stale content is replaced with the exact pinned content`() {
        val rootfs = rootfs()
        rootfs.resolve("etc/openchamber").mkdirs()
        rootfs.resolve("etc/openchamber/policy.json").writeText("{\"enterpriseMode\":true}")

        OpenChamberPolicy.write(rootfs)

        assertEquals(OpenChamberPolicy.CONTENT, rootfs.resolve("etc/openchamber/policy.json").readText())
    }

    @Test
    fun `the relative path is the OpenChamber policy location`() {
        assertEquals(Paths.get("etc/openchamber/policy.json"), Paths.get(OpenChamberPolicy.RELATIVE_PATH))
    }
}
