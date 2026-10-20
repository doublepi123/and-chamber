package io.andchamber.app.runtime.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class OpenChamberProviderArtifactTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `lane paths map onto the installed package and replace both trees`() {
        val source = folder.newFolder("source")
        val module = folder.newFolder("web")
        File(module, "server/index.js").apply { parentFile.mkdirs(); writeText("stock-server") }
        File(module, "dist/index.html").apply { parentFile.mkdirs(); writeText("stock-dist") }
        write(source, "packages/web/server/index.js", "patched-server")
        write(source, "packages/web/dist/index.html", "patched-dist")

        val result = OpenChamberProviderArtifact.deploy(manifest(source), source, module)

        assertTrue(result.applied)
        assertEquals("patched-server", File(module, "server/index.js").readText())
        assertEquals("patched-dist", File(module, "dist/index.html").readText())
        assertTrue(OpenChamberProviderArtifact.isApplied(module))
        assertFalse(File(module.parentFile, ".provider-server-staging").exists())
    }

    @Test
    fun `a hash mismatch leaves the npm package untouched`() {
        val source = folder.newFolder("bad")
        val module = folder.newFolder("stock")
        File(module, "server/index.js").apply { parentFile.mkdirs(); writeText("keep-stock") }
        write(source, "packages/web/server/index.js", "patched")
        write(source, "packages/web/dist/index.html", "dist")
        val broken = manifest(source).copy(
            server = manifest(source).server.map { it.copy(sha256 = "0".repeat(64)) },
        )

        val result = OpenChamberProviderArtifact.deploy(broken, source, module)

        assertFalse(result.applied)
        assertEquals("keep-stock", File(module, "server/index.js").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
    }

    @Test
    fun `frontend only is rejected`() {
        val source = folder.newFolder("frontend")
        write(source, "packages/web/dist/index.html", "only-dist")
        val manifest = manifest(source).copy(server = emptyList())

        assertEquals(
            "artifact must include both server and dist",
            OpenChamberProviderArtifact.rejection(manifest, source),
        )
    }

    private fun manifest(source: File): OpenChamberProviderArtifact.LaneManifest =
        OpenChamberProviderArtifact.LaneManifest(
            packageName = "@openchamber/web",
            version = "2.4.0",
            baseCommit = OpenChamberProviderArtifact.REQUIRED_COMMIT,
            sourcePatchSha256 = OpenChamberProviderArtifact.REQUIRED_PATCH_SHA256,
            server = pins(source, "packages/web/server/"),
            dist = pins(source, "packages/web/dist/"),
        )

    private fun pins(root: File, prefix: String): List<OpenChamberProviderArtifact.LaneFile> =
        root.walkTopDown().filter { it.isFile && it.path.contains(prefix.removeSuffix("/")) }.map { file ->
            val relative = file.relativeTo(root).path
            OpenChamberProviderArtifact.LaneFile(relative, sha(file), file.length())
        }.toList()

    private fun write(root: File, relative: String, content: String) {
        File(root, relative).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
