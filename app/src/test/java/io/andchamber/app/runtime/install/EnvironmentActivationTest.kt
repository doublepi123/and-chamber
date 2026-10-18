package io.andchamber.app.runtime.install

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

    @Test
    fun `activation converts proot hard link symlinks to relative paths`() {
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

        val activatedExecutable = active.resolve("rootfs/usr/bin/unzip")
        assertFalse(Files.readSymbolicLink(activatedExecutable.toPath()).isAbsolute)
        assertEquals("binary", activatedExecutable.readText())
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

        assertFalse(Files.readSymbolicLink(executable.toPath()).isAbsolute)
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

        assertFalse(Files.readSymbolicLink(executable.toPath()).isAbsolute)
        assertEquals("binary", executable.readText())
        assertTrue(active.resolve(".internal-symlinks-relative").isFile)
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
