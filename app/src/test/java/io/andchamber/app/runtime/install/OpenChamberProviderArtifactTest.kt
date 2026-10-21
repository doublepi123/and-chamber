package io.andchamber.app.runtime.install

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class OpenChamberProviderArtifactTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `lane paths map onto the installed package and replace both trees`() {
        val source = folder.newFolder("source")
        val module = module("web")
        write(source, "packages/web/server/index.js", "patched-server")
        write(source, "packages/web/dist/index.html", "patched-dist")
        val manifest = manifest(source)

        val result = OpenChamberProviderArtifact.deploy(manifest, source, module, "2.4.0")

        assertTrue(result.reason, result.applied)
        assertEquals("patched-server", File(module, "server/index.js").readText())
        assertEquals("patched-dist", File(module, "dist/index.html").readText())
        assertTrue(OpenChamberProviderArtifact.isApplied(module))
        assertTrue(OpenChamberProviderArtifact.verifyInstalled(module))
        assertFalse(File(module.parentFile, ".provider-server-staging").exists())
    }

    @Test
    fun `a hash mismatch leaves the npm package untouched`() {
        val source = folder.newFolder("bad")
        val module = module("stock", server = "keep-stock", dist = "keep-dist")
        write(source, "packages/web/server/index.js", "patched")
        write(source, "packages/web/dist/index.html", "dist")
        val broken = manifest(source).copy(
            server = manifest(source).server.map { it.copy(sha256 = "0".repeat(64)) },
        )

        val result = OpenChamberProviderArtifact.deploy(broken, source, module, "2.4.0")

        assertFalse(result.applied)
        assertEquals("keep-stock", File(module, "server/index.js").readText())
        assertEquals("keep-dist", File(module, "dist/index.html").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
    }

    @Test
    fun `frontend only is rejected`() {
        val source = folder.newFolder("frontend")
        write(source, "packages/web/dist/index.html", "only-dist")
        val manifest = manifest(source).copy(server = emptyList())

        assertEquals(
            "artifact must include both server and dist",
            OpenChamberProviderArtifact.manifestRejection(manifest),
        )
    }

    @Test
    fun `an installed package at another version is rejected before any rename`() {
        val source = folder.newFolder("newer")
        val module = module("newer-install", version = "2.5.0", server = "npm-2.5", dist = "npm-dist")
        write(source, "packages/web/server/index.js", "patched")
        write(source, "packages/web/dist/index.html", "dist")

        val result = OpenChamberProviderArtifact.deploy(manifest(source), source, module, "2.5.0")

        assertFalse(result.applied)
        assertEquals("package.json version differs from the artifact", result.reason)
        assertEquals("npm-2.5", File(module, "server/index.js").readText())
        assertEquals("npm-dist", File(module, "dist/index.html").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
        assertFalse(File(module.parentFile, ".provider-server-staging").exists())
    }

    @Test
    fun `a target package without a package json or with an invalid one is rejected before any rename`() {
        val source = folder.newFolder("missing-package")
        write(source, "packages/web/server/index.js", "patched")
        write(source, "packages/web/dist/index.html", "dist")
        val missing = folder.newFolder("missing-module")
        File(missing, "server/index.js").apply { parentFile!!.mkdirs(); writeText("npm") }

        val missingResult = OpenChamberProviderArtifact.deploy(manifest(source), source, missing, "2.4.0")
        assertEquals("package.json is missing", missingResult.reason)
        assertEquals("npm", File(missing, "server/index.js").readText())

        val invalid = module("invalid-module", server = "npm", dist = "npm")
        File(invalid, "package.json").writeText("{not json")
        val invalidResult = OpenChamberProviderArtifact.deploy(manifest(source), source, invalid, "2.4.0")
        assertEquals("package.json is invalid", invalidResult.reason)
        assertEquals("npm", File(invalid, "server/index.js").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(invalid))
    }

    @Test
    fun `each manifest list must keep its own prefix even when the file hashes are valid`() {
        val source = folder.newFolder("group-mismatch")
        write(source, "packages/web/server/index.js", "server")
        write(source, "packages/web/dist/index.html", "dist")
        val base = manifest(source)
        assertNull(OpenChamberProviderArtifact.manifestRejection(base))

        val distInServerList = base.copy(server = listOf(base.dist.single()))
        assertEquals(
            "artifact path is in the wrong group",
            OpenChamberProviderArtifact.manifestRejection(distInServerList),
        )
        assertEquals(
            "artifact path is in the wrong group",
            OpenChamberProviderArtifact.contentRejection(distInServerList, source),
        )
        val serverInDistList = base.copy(dist = listOf(base.server.single()))
        assertEquals(
            "artifact path is in the wrong group",
            OpenChamberProviderArtifact.manifestRejection(serverInDistList),
        )

        val module = module("group-target", server = "old-server", dist = "old-dist")
        val result = OpenChamberProviderArtifact.deploy(distInServerList, source, module, "2.4.0")
        assertFalse(result.applied)
        assertEquals("artifact path is in the wrong group", result.reason)
        assertEquals("old-server", File(module, "server/index.js").readText())
        assertEquals("old-dist", File(module, "dist/index.html").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
        assertFalse(File(module.parentFile, ".provider-server-staging").exists())
    }

    @Test
    fun `a failed dist commit with a successful restore keeps the old pair and marker`() {
        val originalSource = folder.newFolder("restore-original")
        write(originalSource, "packages/web/server/index.js", "old-server")
        write(originalSource, "packages/web/dist/index.html", "old-dist")
        val module = module("restore-ok")
        assertTrue(OpenChamberProviderArtifact.deploy(manifest(originalSource), originalSource, module, "2.4.0").applied)
        val markerBefore = File(module, OpenChamberProviderArtifact.MARKER).readText()

        val replacement = folder.newFolder("restore-replacement")
        write(replacement, "packages/web/server/index.js", "new-server")
        write(replacement, "packages/web/dist/index.html", "new-dist")
        val result =
            OpenChamberProviderArtifact.deployWithRename(manifest(replacement), replacement, module, "2.4.0") { from, to ->
                if (from.name == ".provider-dist-staging" && to.name == "dist") false else from.renameTo(to)
            }

        assertFalse(result.applied)
        assertTrue(result.reason.startsWith("deploy failed:"))
        assertEquals("old-server", File(module, "server/index.js").readText())
        assertEquals("old-dist", File(module, "dist/index.html").readText())
        assertEquals(markerBefore, File(module, OpenChamberProviderArtifact.MARKER).readText())
        assertTrue(OpenChamberProviderArtifact.verifyInstalled(module))
        assertFalse(File(module.parentFile, ".provider-server-backup").exists())
        assertFalse(File(module.parentFile, ".provider-dist-backup").exists())
    }

    @Test
    fun `a failed restore preserves the only old backup and blocks the next deploy`() {
        val source = folder.newFolder("restore-failure")
        write(source, "packages/web/server/index.js", "new-server")
        write(source, "packages/web/dist/index.html", "new-dist")
        val manifest = manifest(source)
        val module = module("restore-failure-module", server = "old-server", dist = "old-dist")
        val parent = module.parentFile
        val serverBackup = File(parent, ".provider-server-backup")

        val result =
            OpenChamberProviderArtifact.deployWithRename(manifest, source, module, "2.4.0") { from, to ->
                when {
                    from.name == ".provider-dist-staging" && to.name == "dist" -> false
                    from.name == ".provider-server-backup" && to.name == "server" -> false
                    else -> from.renameTo(to)
                }
            }

        assertEquals(OpenChamberProviderArtifact.RECOVERY_REQUIRED, result.reason)
        assertFalse(result.applied)
        assertTrue("the old server tree must stay in its backup", serverBackup.isDirectory)
        assertEquals("old-server", File(serverBackup, "index.js").readText())
        assertEquals("old-dist", File(module, "dist/index.html").readText())

        val second = OpenChamberProviderArtifact.deploy(manifest, source, module, "2.4.0")

        assertEquals(OpenChamberProviderArtifact.RECOVERY_REQUIRED, second.reason)
        assertTrue("a later deploy must not clear the backup", serverBackup.isDirectory)
        assertEquals("old-server", File(serverBackup, "index.js").readText())
    }

    @Test
    fun `a non string package json name is a typed rejection before any write`() {
        val source = folder.newFolder("package-name-object")
        write(source, "packages/web/server/index.js", "new-server")
        write(source, "packages/web/dist/index.html", "new-dist")
        val module = module("package-name-object-module", server = "old-server", dist = "old-dist")
        File(module, "package.json").writeText("""{"name":{"unexpected":true},"version":"2.4.0"}""")

        val result = OpenChamberProviderArtifact.deploy(manifest(source), source, module, "2.4.0")

        assertEquals("package.json name is invalid", result.reason)
        assertEquals("old-server", File(module, "server/index.js").readText())
        assertEquals("old-dist", File(module, "dist/index.html").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
    }

    @Test
    fun `a marker write failure restores both trees and removes the marker`() {
        val source = folder.newFolder("marker-failure")
        val module = module("rollback", server = "old-server", dist = "old-dist")
        write(source, "packages/web/server/index.js", "new-server")
        write(source, "packages/web/dist/index.html", "new-dist")
        // A directory at the marker path makes the post-swap marker write fail.
        File(module, OpenChamberProviderArtifact.MARKER).mkdirs()

        val result = OpenChamberProviderArtifact.deploy(manifest(source), source, module, "2.4.0")

        assertFalse(result.applied)
        assertEquals("old-server", File(module, "server/index.js").readText())
        assertEquals("old-dist", File(module, "dist/index.html").readText())
        assertFalse(File(module, OpenChamberProviderArtifact.MARKER).exists())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
    }

    @Test
    fun `paths outside the artifact prefixes and non canonical paths are rejected`() {
        val source = folder.newFolder("paths")
        write(source, "packages/web/server/index.js", "server")
        write(source, "packages/web/dist/index.html", "dist")
        val base = manifest(source)

        listOf(
            "packages/web/server/../../escape.js" to "artifact path is invalid",
            "/packages/web/server/index.js" to "artifact path is in the wrong group",
            "packages/web/server/a\\b.js" to "artifact path is invalid",
            "packages/web/server/./index.js" to "artifact path is invalid",
            "packages/web/server//index.js" to "artifact path is invalid",
            "packages/web/other/index.js" to "artifact path is in the wrong group",
        ).forEach { (path, expected) ->
            val manifest = base.copy(server = listOf(pin(path, "server")))
            assertEquals(expected, OpenChamberProviderArtifact.manifestRejection(manifest))
        }
    }

    @Test
    fun `duplicate mapped paths, negative sizes and bad hashes are rejected`() {
        val source = folder.newFolder("pins")
        write(source, "packages/web/server/index.js", "server")
        write(source, "packages/web/dist/index.html", "dist")
        val base = manifest(source)

        val duplicate = base.copy(server = listOf(base.server.single(), base.server.single()))
        assertEquals("artifact path is duplicated", OpenChamberProviderArtifact.manifestRejection(duplicate))

        val negative = base.copy(server = listOf(base.server.single().copy(bytes = -1)))
        assertEquals("artifact size is invalid", OpenChamberProviderArtifact.manifestRejection(negative))

        val badHash = base.copy(server = listOf(base.server.single().copy(sha256 = "not-a-hash")))
        assertEquals("artifact pin is invalid", OpenChamberProviderArtifact.manifestRejection(badHash))
    }

    @Test
    fun `a declared file with a symlinked ancestor is rejected without writing outside`() {
        val source = folder.newFolder("symlinked-source")
        val outside = folder.newFolder("outside")
        File(outside, "index.js").writeText("outside-server")
        File(source, "packages/web").mkdirs()
        Files.createSymbolicLink(
            File(source, "packages/web/server").toPath(),
            outside.toPath(),
        )
        write(source, "packages/web/dist/index.html", "dist")
        val manifest = manifest(source).copy(
            server = listOf(pin("packages/web/server/index.js", "outside-server")),
        )
        val module = module("symlink-target", server = "old", dist = "old")

        assertEquals("artifact path is unsafe", OpenChamberProviderArtifact.contentRejection(manifest, source))
        val result = OpenChamberProviderArtifact.deploy(manifest, source, module, "2.4.0")

        assertFalse(result.applied)
        assertEquals("old", File(module, "server/index.js").readText())
        assertEquals("outside-server", File(outside, "index.js").readText())
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
    }

    @Test
    fun `a declared but missing file is invalid, never absent`() {
        val source = folder.newFolder("missing-file")
        write(source, "packages/web/server/index.js", "server")
        write(source, "packages/web/dist/index.html", "dist")
        val manifest = manifest(source)
        File(source, "packages/web/dist/index.html").delete()

        assertEquals("artifact file missing", OpenChamberProviderArtifact.contentRejection(manifest, source))
    }

    @Test
    fun `isApplied rejects a wrong package and a marker with another patch pin`() {
        val source = folder.newFolder("marker-pins")
        val module = module("marker-module")
        write(source, "packages/web/server/index.js", "server")
        write(source, "packages/web/dist/index.html", "dist")
        val manifest = manifest(source)
        assertTrue(OpenChamberProviderArtifact.deploy(manifest, source, module, "2.4.0").applied)

        val wrongPackage = module("wrong-package")
        File(wrongPackage, "server/index.js").apply { parentFile!!.mkdirs(); writeText("server") }
        File(wrongPackage, "dist/index.html").apply { parentFile!!.mkdirs(); writeText("dist") }
        File(wrongPackage, "package.json").writeText("""{"name":"@openchamber/other","version":"2.4.0"}""")
        File(wrongPackage, OpenChamberProviderArtifact.MARKER).writeText(markerText(manifest))
        assertFalse(OpenChamberProviderArtifact.isApplied(wrongPackage))

        File(module, OpenChamberProviderArtifact.MARKER).writeText(
            markerText(manifest.copy(sourcePatchSha256 = "0".repeat(64))),
        )
        assertFalse(OpenChamberProviderArtifact.isApplied(module))

        File(module, OpenChamberProviderArtifact.MARKER).writeText(
            markerText(manifest.copy(baseCommit = "0".repeat(40))),
        )
        assertFalse(OpenChamberProviderArtifact.isApplied(module))
    }

    @Test
    fun `verifyInstalled notices a changed payload even when the marker pins still match`() {
        val source = folder.newFolder("payload")
        val module = module("payload-module")
        write(source, "packages/web/server/index.js", "server")
        write(source, "packages/web/dist/index.html", "dist")
        val manifest = manifest(source)
        assertTrue(OpenChamberProviderArtifact.deploy(manifest, source, module, "2.4.0").applied)

        File(module, "dist/index.html").writeText("tampered")

        assertTrue(OpenChamberProviderArtifact.isApplied(module))
        assertFalse(OpenChamberProviderArtifact.verifyInstalled(module))
    }

    private fun module(
        name: String,
        version: String = "2.4.0",
        server: String = "stock-server",
        dist: String = "stock-dist",
    ): File {
        val module = folder.newFolder(name)
        File(module, "package.json").writeText("""{"name":"@openchamber/web","version":"$version"}""")
        File(module, "server/index.js").apply { parentFile.mkdirs(); writeText(server) }
        File(module, "dist/index.html").apply { parentFile.mkdirs(); writeText(dist) }
        return module
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

    private fun markerText(manifest: OpenChamberProviderArtifact.LaneManifest): String =
        Json.encodeToString(OpenChamberProviderArtifact.LaneManifest.serializer(), manifest)

    private fun pins(root: File, prefix: String): List<OpenChamberProviderArtifact.LaneFile> =
        root.walkTopDown().filter { it.isFile && it.path.contains(prefix.removeSuffix("/")) }.map { file ->
            val relative = file.relativeTo(root).path
            OpenChamberProviderArtifact.LaneFile(relative, sha(file), file.length())
        }.toList()

    private fun pin(path: String, content: String): OpenChamberProviderArtifact.LaneFile =
        OpenChamberProviderArtifact.LaneFile(path, sha(content.toByteArray()), content.toByteArray().size.toLong())

    private fun write(root: File, relative: String, content: String) {
        File(root, relative).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun sha(file: File): String = sha(file.readBytes())

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
