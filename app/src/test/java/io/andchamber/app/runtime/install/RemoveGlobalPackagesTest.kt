package io.andchamber.app.runtime.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class RemoveGlobalPackagesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun rootfs(): File = temporaryFolder.newFolder("rootfs")

    @Test
    fun `removes both global package trees and the bin links`() {
        val rootfs = rootfs()
        rootfs.resolve("usr/local/lib/node_modules/@openchamber/web/dist").mkdirs()
        rootfs.resolve("usr/local/lib/node_modules/@openchamber/web/dist/index.html").writeText("x")
        rootfs.resolve("usr/local/lib/node_modules/@opencode/cli/bin").mkdirs()
        rootfs.resolve("usr/local/lib/node_modules/@opencode/cli/bin/opencode").writeText("x")
        rootfs.resolve("usr/local/bin").mkdirs()
        Files.createSymbolicLink(
            rootfs.resolve("usr/local/bin/opencode").toPath(),
            Paths.get("../lib/node_modules/@opencode/cli/bin/opencode"),
        )

        OpenChamberInstaller.removeGlobalPackages(rootfs)

        assertFalse(rootfs.resolve("usr/local/lib/node_modules/@openchamber/web").exists())
        assertFalse(rootfs.resolve("usr/local/lib/node_modules/@opencode/cli").exists())
        assertFalse(Files.exists(rootfs.resolve("usr/local/bin/opencode").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a bin link into an outside file is unlinked and the outside file survives`() {
        val rootfs = rootfs()
        val outside = temporaryFolder.newFolder("outside").resolve("keep.txt").apply { writeText("precious") }
        rootfs.resolve("usr/local/bin").mkdirs()
        Files.createSymbolicLink(rootfs.resolve("usr/local/bin/openchamber").toPath(), outside.toPath().toAbsolutePath())

        OpenChamberInstaller.removeGlobalPackages(rootfs)

        assertFalse(Files.exists(rootfs.resolve("usr/local/bin/openchamber").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(outside.isFile)
        assertEquals("precious", outside.readText())
    }

    @Test
    fun `a package directory that is itself a link to outside is unlinked and its target survives`() {
        val rootfs = rootfs()
        val outside = temporaryFolder.newFolder("outside-pkg").apply { resolve("pkg.json").writeText("keep") }
        rootfs.resolve("usr/local/lib/node_modules/@openchamber").mkdirs()
        Files.createSymbolicLink(
            rootfs.resolve("usr/local/lib/node_modules/@openchamber/web").toPath(),
            outside.toPath().toAbsolutePath(),
        )

        OpenChamberInstaller.removeGlobalPackages(rootfs)

        assertFalse(Files.exists(rootfs.resolve("usr/local/lib/node_modules/@openchamber/web").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", outside.resolve("pkg.json").readText())
    }

    @Test
    fun `missing entries are a no-op`() {
        val rootfs = rootfs()
        OpenChamberInstaller.removeGlobalPackages(rootfs)
        assertTrue(rootfs.isDirectory)
    }

    @Test
    fun `unrelated files in the rootfs are kept`() {
        val rootfs = rootfs()
        rootfs.resolve("usr/local/bin").mkdirs()
        rootfs.resolve("usr/local/bin/other-tool").writeText("keep")
        rootfs.resolve("usr/local/lib/node_modules/left-pad").mkdirs()

        OpenChamberInstaller.removeGlobalPackages(rootfs)

        assertEquals("keep", rootfs.resolve("usr/local/bin/other-tool").readText())
        assertTrue(rootfs.resolve("usr/local/lib/node_modules/left-pad").isDirectory)
    }
}
