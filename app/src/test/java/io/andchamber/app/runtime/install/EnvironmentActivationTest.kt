package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.fs.copyTreeStrict
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LocalRuntimeEnvironmentActivationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Fixture: `environment` (active), `environment.staging` and optional `environment.rollback`. */
    private class Layout(val root: File) {
        val active = root.resolve("environment")
        val staging = root.resolve("environment.staging")
        val rollback = root.resolve("environment.rollback")
    }

    private fun layout(name: String) = Layout(temporaryFolder.newFolder(name))

    private fun File.withMarker(value: String): File =
        apply {
            mkdirs()
            resolve("marker.txt").writeText(value)
        }

    @Test
    fun `commit failure restores the previous environment`() {
        val l = layout("commit-fails")
        l.active.withMarker("old")
        l.staging.withMarker("new")

        val error =
            runCatching {
                activateRuntimeEnvironment(
                    active = l.active,
                    staging = l.staging,
                    rollback = l.rollback,
                    moveDirectory = { source, destination ->
                        if (source.name == "environment.staging") error("simulated commit failure")
                        require(source.renameTo(destination))
                    },
                )
            }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("simulated"))
        assertEquals("old", l.active.resolve("marker.txt").readText())
        assertFalse(l.rollback.exists())
        assertEquals("new", l.staging.resolve("marker.txt").readText())
    }

    @Test
    fun `successful activation removes the previous environment after the commit`() {
        val l = layout("success")
        l.active.withMarker("old")
        l.staging.withMarker("new")

        activateRuntimeEnvironment(active = l.active, staging = l.staging, rollback = l.rollback)

        assertEquals("new", l.active.resolve("marker.txt").readText())
        assertFalse(l.rollback.exists())
        assertFalse(l.staging.exists())
    }

    @Test
    fun `interrupted before commit restores the previous environment`() {
        val l = layout("interrupted-before-commit")
        // Crash after `active` was moved aside but before staging became active.
        l.rollback.withMarker("old").resolve("metadata.json").writeText(json.encodeToString(metadata("1.18.3")))

        assertTrue(recoverInterruptedRuntimeEnvironment(active = l.active, rollback = l.rollback))

        assertEquals("old", l.active.resolve("marker.txt").readText())
        assertEquals(
            "1.18.3",
            json.decodeFromString<RuntimeMetadata>(l.active.resolve("metadata.json").readText()).openCodeVersion,
        )
        assertFalse(l.rollback.exists())
    }

    @Test
    fun `interrupted after commit only removes the old environment and never rolls back`() {
        val l = layout("interrupted-after-commit")
        l.active.withMarker("new").resolve("metadata.json").writeText(json.encodeToString(metadata("1.19.0")))
        l.rollback.withMarker("old")
        var moved = false

        val recovered =
            recoverInterruptedRuntimeEnvironment(
                active = l.active,
                rollback = l.rollback,
                moveDirectory = { _, _ -> moved = true },
            )

        assertFalse(recovered)
        assertFalse(moved)
        assertEquals("new", l.active.resolve("marker.txt").readText())
        assertFalse(l.rollback.exists())
    }

    @Test
    fun `interrupted during the rollback move itself is retried on the next recovery`() {
        val l = layout("interrupted-during-rollback")
        l.rollback.withMarker("old").resolve("metadata.json").writeText(json.encodeToString(metadata("1.18.3")))
        var failNext = true

        val first =
            runCatching {
                recoverInterruptedRuntimeEnvironment(
                    active = l.active,
                    rollback = l.rollback,
                    moveDirectory = { source, destination ->
                        if (failNext) error("simulated crash during rollback")
                        require(source.renameTo(destination))
                    },
                )
            }.exceptionOrNull()
        assertTrue(first != null)
        assertTrue(l.rollback.isDirectory)

        failNext = false
        assertTrue(recoverInterruptedRuntimeEnvironment(active = l.active, rollback = l.rollback))
        assertEquals("old", l.active.resolve("marker.txt").readText())
    }

    @Test
    fun `cleanup failure after commit keeps the new environment and is retried by recovery`() {
        val l = layout("cleanup-fails")
        l.active.withMarker("old")
        l.staging.withMarker("new")

        activateRuntimeEnvironment(
            active = l.active,
            staging = l.staging,
            rollback = l.rollback,
            deleteTree = { false }, // The old environment cannot be removed.
        )

        assertEquals("new", l.active.resolve("marker.txt").readText())
        assertFalse(l.staging.exists())
        assertTrue(l.rollback.exists())

        // Pending cleanup: recovery must not roll back to the old one, and it removes the leftover.
        assertFalse(recoverInterruptedRuntimeEnvironment(l.active, l.rollback))
        assertFalse(l.rollback.exists())
        assertEquals("new", l.active.resolve("marker.txt").readText())
    }

    @Test
    fun `activation refuses to run over an unrecovered rollback`() {
        val l = layout("refuse")
        l.rollback.mkdirs()
        l.staging.mkdirs()

        val error = runCatching { activateRuntimeEnvironment(l.active, l.staging, l.rollback) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("recovered"))
        assertTrue(l.rollback.isDirectory)
    }

    @Test
    fun `activation keeps the new environment at the moment the staging directory is renamed`() {
        // Interruption right after the commit rename: `active` = new, `rollback` = old, no staging.
        val l = layout("after-commit-rename")
        l.active.withMarker("old")
        l.staging.withMarker("new")
        activateRuntimeEnvironment(
            active = l.active,
            staging = l.staging,
            rollback = l.rollback,
            deleteTree = { false },
        )
        assertEquals("new", l.active.resolve("marker.txt").readText())
        assertTrue(l.rollback.exists())
    }

    @Test
    fun `successful recovery leaves no rollback and no backup`() {
        val l = layout("no-leftovers")
        l.rollback.withMarker("old").resolve("metadata.json").writeText(json.encodeToString(metadata("1.18.3")))
        recoverInterruptedRuntimeEnvironment(l.active, l.rollback)
        assertFalse(l.rollback.exists())
        assertFalse(File(l.root, "environment.failed").exists())
    }

    /**
     * Activation retargets staging links to the final environment path, as absolute links. PRoot resolves
     * its link2symlink intermediates only through absolute targets, so relative ones break the terminal.
     */
    @Test
    fun `activation retargets proot hard link symlinks from staging to the final environment path as absolute`() {
        val root = temporaryFolder.newFolder("runtime-links")
        val active = root.resolve("environment")
        val staging = root.resolve("environment.staging").apply { mkdirs() }
        val bin = staging.resolve("rootfs/usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.unzip.0002").apply { writeText("binary") }
        val intermediary = bin.resolve(".l2s.unzip")
        val executable = bin.resolve("unzip")
        Files.createSymbolicLink(intermediary.toPath(), backing.toPath().toAbsolutePath())
        Files.createSymbolicLink(executable.toPath(), intermediary.toPath().toAbsolutePath())

        activateRuntimeEnvironment(
            active = active,
            staging = staging,
            rollback = root.resolve("environment.rollback"),
        )

        val activatedExecutable = active.resolve("rootfs/usr/bin/unzip").toPath()
        val expected = active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.unzip")
        assertEquals(expected, Files.readSymbolicLink(activatedExecutable))
        assertEquals("binary", active.resolve("rootfs/usr/bin/unzip").readText())
        assertTrue(active.resolve(SYMLINK_MARKER_NAME).isFile)
        assertFalse(staging.exists())
    }

    @Test
    fun `activation never leaves a staging link pointing at the staging path`() {
        val root = temporaryFolder.newFolder("runtime-no-staging-target")
        val active = root.resolve("environment")
        val staging = root.resolve("environment.staging").apply { mkdirs() }
        val bin = staging.resolve("rootfs/usr/bin").apply { mkdirs() }
        bin.resolve(".l2s.node.0002").writeText("binary")
        Files.createSymbolicLink(
            bin.resolve("node").toPath(),
            staging.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.node.0002"),
        )

        activateRuntimeEnvironment(active = active, staging = staging, rollback = root.resolve("environment.rollback"))

        val target = Files.readSymbolicLink(active.resolve("rootfs/usr/bin/node").toPath())
        assertTrue(target.startsWith(active.toPath().toAbsolutePath().normalize()))
        assertEquals("binary", active.resolve("rootfs/usr/bin/node").readText())
    }

    @Test
    fun `a relative proot backing link from an older release becomes absolute under the environment`() {
        val root = temporaryFolder.newFolder("runtime-v2-relative")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        bin.resolve(".l2s.pty.node.0002").writeText("binary")
        val intermediate = bin.resolve(".l2s.pty.node.0001")
        Files.createSymbolicLink(intermediate.toPath(), java.nio.file.Paths.get(".l2s.pty.node.0002"))
        val executable = bin.resolve("pty.node")
        Files.createSymbolicLink(executable.toPath(), java.nio.file.Paths.get(".l2s.pty.node.0001"))

        normalizeRuntimeSymlinks(active)

        assertEquals(
            active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.pty.node.0001"),
            Files.readSymbolicLink(executable.toPath()),
        )
        assertEquals(
            active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.pty.node.0002"),
            Files.readSymbolicLink(intermediate.toPath()),
        )
        assertEquals("binary", executable.readText())
    }

    @Test
    fun `npm style relative links are left relative`() {
        val root = temporaryFolder.newFolder("runtime-npm-bin")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/local/bin").apply { mkdirs() }
        val lib = active.resolve("rootfs/usr/local/lib/node_modules/@opencode/cli/bin").apply { mkdirs() }
        lib.resolve("opencode").writeText("#!/usr/bin/env node\n")
        val link = bin.resolve("opencode")
        Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("../lib/node_modules/@opencode/cli/bin/opencode"))

        normalizeRuntimeSymlinks(active)

        assertEquals(java.nio.file.Paths.get("../lib/node_modules/@opencode/cli/bin/opencode"), Files.readSymbolicLink(link.toPath()))
    }

    @Test
    fun `guest absolute links are left alone`() {
        val root = temporaryFolder.newFolder("runtime-guest-absolute")
        val active = root.resolve("environment").apply { mkdirs() }
        val lib = active.resolve("rootfs/usr/lib").apply { mkdirs() }
        val link = lib.resolve("libx.so")
        Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("/usr/lib/libx.so.1"))

        normalizeRuntimeSymlinks(active)

        assertEquals(java.nio.file.Paths.get("/usr/lib/libx.so.1"), Files.readSymbolicLink(link.toPath()))
    }

    @Test
    fun `an absolute link under the rollback root is retargeted into the environment`() {
        val root = temporaryFolder.newFolder("runtime-rollback-link")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        val link = bin.resolve("tool")
        Files.createSymbolicLink(
            link.toPath(),
            root.resolve("environment.rollback").toPath().toAbsolutePath().resolve("rootfs/usr/bin/.l2s.tool.0002"),
        )

        normalizeRuntimeSymlinks(active)

        assertEquals(
            active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.tool.0002"),
            Files.readSymbolicLink(link.toPath()),
        )
    }

    @Test
    fun `copy then normalize on staging retargets the copied links from environment to staging`() {
        val root = temporaryFolder.newFolder("runtime-copy-update")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        bin.resolve(".l2s.node.0002").writeText("binary")
        val activeLink = bin.resolve("node")
        val activeAbsolute = active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.node.0002")
        Files.createSymbolicLink(activeLink.toPath(), activeAbsolute)
        active.resolve(SYMLINK_MARKER_NAME).writeText("absolute-l2s-v3\n")

        val staging = root.resolve("environment.staging")
        copyTreeStrict(active, staging)
        clearSymlinkMarker(staging)
        normalizeRuntimeSymlinks(staging, writeMarker = false)

        val copied = Files.readSymbolicLink(staging.resolve("rootfs/usr/bin/node").toPath())
        assertEquals(staging.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.node.0002"), copied)
        assertFalse(staging.resolve(SYMLINK_MARKER_NAME).exists())
        // The live link is untouched by the copy-side rewrite.
        assertEquals(activeAbsolute, Files.readSymbolicLink(activeLink.toPath()))
    }

    @Test
    fun `normalization without a marker write leaves the next post-commit pass to run`() {
        val root = temporaryFolder.newFolder("runtime-no-marker")
        val staging = root.resolve("environment.staging").apply { mkdirs() }
        normalizeRuntimeSymlinks(staging, writeMarker = false)

        assertFalse(staging.resolve(SYMLINK_MARKER_NAME).exists())
    }

    @Test
    fun `android app data path spellings are treated as the same root`() {
        // Seen on device: filesDir is /data/user/0/<pkg>, PRoot records /data/data/<pkg> link targets.
        val user = java.nio.file.Path.of("/data/user/0/io.andchamber.app/files/runtime/environment.staging")
        val data = java.nio.file.Path.of("/data/data/io.andchamber.app/files/runtime/environment.staging")
        assertTrue(data in appDataAliases(user))
        assertTrue(user in appDataAliases(data))
        assertEquals(
            listOf(java.nio.file.Path.of("/tmp/x")),
            appDataAliases(java.nio.file.Path.of("/tmp/x")),
        )
    }

    @Test
    fun `normalization repairs links left behind by an older staging activation`() {
        val root = temporaryFolder.newFolder("runtime-stale-links")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.unzip.0002").apply { writeText("binary") }
        val staleRoot = root.resolve("environment.staging").toPath().toAbsolutePath()
        val executable = bin.resolve("unzip")
        Files.createSymbolicLink(
            executable.toPath(),
            staleRoot.resolve("rootfs/usr/bin/${backing.name}"),
        )

        normalizeRuntimeSymlinks(active)

        assertEquals(
            active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.unzip.0002"),
            Files.readSymbolicLink(executable.toPath()),
        )
        assertEquals("binary", executable.readText())
        assertTrue(active.resolve(".internal-symlinks-relative").isFile)
    }

    @Test
    fun `normalization skips directories it cannot open`() {
        val root = temporaryFolder.newFolder("runtime-unreadable")
        val active = root.resolve("environment").apply { mkdirs() }
        val rootfs = active.resolve("rootfs").apply { mkdirs() }
        // PRoot's bind-mount points come out of the guest tarball with no permissions at all, and
        // the app's own uid cannot open them either. Walking must skip them, not give up.
        val mountPoint = rootfs.resolve("system").apply { mkdirs() }
        assumeTrue(mountPoint.setReadable(false, false) && !mountPoint.canRead())
        val bin = rootfs.resolve("usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.unzip.0002").apply { writeText("binary") }
        val executable = bin.resolve("unzip")
        Files.createSymbolicLink(
            executable.toPath(),
            root.resolve("environment.staging").toPath().toAbsolutePath().resolve("rootfs/usr/bin/${backing.name}"),
        )

        try {
            normalizeRuntimeSymlinks(active)
        } finally {
            mountPoint.setReadable(true, false)
        }

        assertEquals(
            active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.unzip.0002"),
            Files.readSymbolicLink(executable.toPath()),
        )
        assertEquals("binary", executable.readText())
        assertTrue(active.resolve(".internal-symlinks-relative").isFile)
    }

    /**
     * An update copies the active environment (its links point at the active root) into staging. Clearing
     * the copied marker makes activation rewrite those links, including ones that name `environment`.
     */
    @Test
    fun `clearing the copied marker makes normalization rewrite links that name the active environment`() {
        val root = temporaryFolder.newFolder("runtime-update-links")
        val staging = root.resolve("environment.staging").apply { mkdirs() }
        val bin = staging.resolve("rootfs/usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.node.0001").apply { writeText("binary") }
        staging.resolve(".internal-symlinks-relative").writeText("normalized-v2\n")
        val link = bin.resolve("node")
        Files.createSymbolicLink(
            link.toPath(),
            root.resolve("environment").toPath().toAbsolutePath().resolve("rootfs/usr/bin/${backing.name}"),
        )

        clearSymlinkMarker(staging)
        normalizeRuntimeSymlinks(staging)

        // Retargeted from the live environment to this copy, and kept absolute.
        assertEquals(
            staging.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.node.0001"),
            Files.readSymbolicLink(link.toPath()),
        )
        assertEquals("binary", link.readText())
    }

    @Test
    fun `prootHostSpelling canonicalizes user 0 to the data-data spelling PRoot records`() {
        val user0 = java.nio.file.Path.of("/data/user/0/io.andchamber.app/files/runtime/environment")
        assertEquals(
            java.nio.file.Path.of("/data/data/io.andchamber.app/files/runtime/environment"),
            prootHostSpelling(user0),
        )
    }

    @Test
    fun `prootHostSpelling leaves other users unchanged because they have no data-data alias`() {
        val user10 = java.nio.file.Path.of("/data/user/10/io.andchamber.app/files/runtime/environment")
        assertEquals(user10, prootHostSpelling(user10))
    }

    @Test
    fun `prootHostSpelling leaves paths outside the app data prefix unchanged`() {
        val temp = java.nio.file.Path.of("/tmp/junit/environment")
        assertEquals(temp, prootHostSpelling(temp))
        val dataData = java.nio.file.Path.of("/data/data/io.andchamber.app/files/runtime/environment")
        assertEquals(dataData, prootHostSpelling(dataData))
    }

    /**
     * A link already at the user-0 spelling of the final location must be rewritten to the PRoot spelling
     * (the early "already equal" return must compare the new target with the link text, not the base).
     * Temp dirs are not under /data, so this drives the retarget through the injected root list.
     */
    @Test
    fun `retarget rewrites a link that uses the other spelling of the final location`() {
        val root = temporaryFolder.newFolder("runtime-other-spelling")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        bin.resolve(".l2s.opencode.0002").writeText("binary")
        val link = bin.resolve("opencode")
        // Written in the user-0 spelling of the same final location; the base is the PRoot spelling.
        val userZeroSpelling = java.nio.file.Path.of("/data/user/0/io.andchamber.app/files/runtime/environment")
        val base = java.nio.file.Path.of("/data/data/io.andchamber.app/files/runtime/environment")
        Files.createSymbolicLink(link.toPath(), userZeroSpelling.resolve("rootfs/usr/bin/.l2s.opencode.0002"))

        val rewritten = retargetSymlink(link.toPath(), base, listOf(base, userZeroSpelling))

        assertTrue(rewritten)
        assertEquals(
            base.resolve("rootfs/usr/bin/.l2s.opencode.0002"),
            Files.readSymbolicLink(link.toPath()),
        )
        // Already in the PRoot spelling: a second pass leaves it alone.
        assertFalse(retargetSymlink(link.toPath(), base, listOf(base, userZeroSpelling)))
    }

    @Test
    fun `a link already in the PRoot spelling is not rewritten`() {
        val root = temporaryFolder.newFolder("runtime-proot-spelling")
        val bin = root.resolve("rootfs/usr/bin").apply { mkdirs() }
        val base = java.nio.file.Path.of("/data/data/io.andchamber.app/files/runtime/environment")
        val link = bin.resolve("node")
        Files.createSymbolicLink(link.toPath(), base.resolve("rootfs/usr/bin/.l2s.node.0002"))

        assertFalse(retargetSymlink(link.toPath(), base, listOf(base)))
        assertEquals(base.resolve("rootfs/usr/bin/.l2s.node.0002"), Files.readSymbolicLink(link.toPath()))
    }

    /** A v3 marker (user-0 spelling) must be repaired to the v4 PRoot spelling. */
    @Test
    fun `a v3 marker is repaired again to the v4 spelling`() {
        val root = temporaryFolder.newFolder("runtime-v3-marker")
        val active = root.resolve("environment").apply { mkdirs() }
        active.resolve(SYMLINK_MARKER_NAME).writeText("absolute-l2s-v3\n")
        normalizeRuntimeSymlinks(active)
        assertEquals("absolute-l2s-v4\n", active.resolve(SYMLINK_MARKER_NAME).readText())
    }

    /** A v2 marker (relative links era) must not stop the repair from running. */
    @Test
    fun `an environment marked by the old v2 normalization is repaired again`() {
        val root = temporaryFolder.newFolder("runtime-v2-marker")
        val active = root.resolve("environment").apply { mkdirs() }
        active.resolve(SYMLINK_MARKER_NAME).writeText("normalized-v2\n")
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        bin.resolve(".l2s.tool.0002").writeText("binary")
        val link = bin.resolve("tool")
        Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(".l2s.tool.0002"))

        normalizeRuntimeSymlinks(active)

        assertEquals(
            active.toPath().toAbsolutePath().normalize().resolve("rootfs/usr/bin/.l2s.tool.0002"),
            Files.readSymbolicLink(link.toPath()),
        )
        assertEquals("absolute-l2s-v4\n", active.resolve(SYMLINK_MARKER_NAME).readText())
    }

    @Test
    fun `a marked environment is not normalized again`() {
        val root = temporaryFolder.newFolder("runtime-marked")
        val active = root.resolve("environment").apply { mkdirs() }
        active.resolve(SYMLINK_MARKER_NAME).writeText("absolute-l2s-v4\n")
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        val link = bin.resolve("tool")
        val absolute = root.resolve("environment.staging").toPath().toAbsolutePath().resolve("rootfs/x")
        Files.createSymbolicLink(link.toPath(), absolute)

        normalizeRuntimeSymlinks(active)

        assertEquals(absolute, Files.readSymbolicLink(link.toPath()))
    }

    private fun metadata(version: String) =
        RuntimeMetadata(
            runtimeVersion = "2026.10.18.1",
            alpineVersion = "3.24.1",
            abi = "arm64-v8a",
            port = 3000,
            openChamberVersion = "2.4.0",
            openCodeVersion = version,
            installedAt = 123,
        )
}
