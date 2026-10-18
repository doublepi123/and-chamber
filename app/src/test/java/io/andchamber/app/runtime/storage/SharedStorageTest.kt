package io.andchamber.app.runtime.storage

import io.andchamber.app.runtime.SharedStorageStatus
import io.andchamber.app.runtime.process.ProotCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharedStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val proot = File("/native/libandchamber_proot.so")
    private val rootfs = File("/data/runtime/environment/rootfs")
    private val home = File("/data/runtime/home")
    private val privateWs = File("/data/runtime/workspace")
    private val hostRoot = File("/storage/emulated/0/AndChamber")

    /** The `host:guest` binds we add. The fixed /dev /proc /sys /system mounts have no colon and are skipped. */
    private fun List<String>.bindPairs(): List<String> =
        zipWithNext().filter { it.first == "-b" && it.second.contains(':') }.map { it.second }

    // --- ProotCommands binds ---

    @Test
    fun `off binds the private workspace at workspace and at workspace-private, home first`() {
        val binds = GuestBinds.privateOnly(home, privateWs)
        val cmd = ProotCommands.serverCommand(proot, rootfs, binds, emptyList(), 3000)

        assertEquals(
            listOf(
                "/data/runtime/home:/root",
                "/data/runtime/workspace:/workspace",
                "/data/runtime/workspace:/workspace-private",
            ),
            cmd.bindPairs(),
        )
        assertEquals("/workspace", cmd[cmd.indexOf("-w") + 1])
    }

    @Test
    fun `active binds projects at workspace and each shared opencode item individually after the home bind`() {
        val binds =
            GuestBinds.shared(
                home = home,
                privateWorkspace = privateWs,
                sharedProjects = File(hostRoot, "projects"),
                sharedOpencode = File(hostRoot, "opencode"),
            )
        val pairs = ProotCommands.serverCommand(proot, rootfs, binds, emptyList(), 3000).bindPairs()

        assertEquals("home is the first bind, so nested binds sit on top of it", "/data/runtime/home:/root", pairs.first())
        assertEquals("/storage/emulated/0/AndChamber/projects:/workspace", pairs[1])
        assertEquals("/data/runtime/workspace:/workspace-private", pairs[2])
        val nested = pairs.drop(3)
        assertTrue(nested.contains("/storage/emulated/0/AndChamber/opencode/opencode.jsonc:/root/.config/opencode/opencode.jsonc"))
        assertTrue(nested.contains("/storage/emulated/0/AndChamber/opencode/AGENTS.md:/root/.config/opencode/AGENTS.md"))
        assertTrue(nested.contains("/storage/emulated/0/AndChamber/opencode/skills:/root/.config/opencode/skills"))
        assertTrue(nested.contains("/storage/emulated/0/AndChamber/opencode/agents:/root/.config/opencode/agents"))
        assertTrue(nested.contains("/storage/emulated/0/AndChamber/opencode/commands:/root/.config/opencode/commands"))
    }

    @Test
    fun `the whole opencode directory is never bound, so node_modules and plugins stay private`() {
        val binds =
            GuestBinds.shared(home, privateWs, File(hostRoot, "projects"), File(hostRoot, "opencode"))
        val pairs = ProotCommands.serverCommand(proot, rootfs, binds, emptyList(), 3000).bindPairs()

        assertFalse(pairs.any { it.endsWith(":/root/.config/opencode") })
        assertFalse(pairs.any { it.contains("node_modules") || it.contains("plugins") })
    }

    @Test
    fun `nested config binds come after the home bind in the final argv`() {
        val binds = GuestBinds.shared(home, privateWs, File(hostRoot, "projects"), File(hostRoot, "opencode"))
        val pairs = ProotCommands.serverCommand(proot, rootfs, binds, emptyList(), 3000).bindPairs()
        val homeIndex = pairs.indexOf("/data/runtime/home:/root")
        val firstNested = pairs.indexOfFirst { it.endsWith(":/root/.config/opencode/opencode.jsonc") }

        assertTrue(homeIndex in 0 until firstNested)
    }

    // --- Seeding ---

    @Test
    fun `first activation creates the shared files and dirs with defaults when private has none`() {
        val shared = folder.newFolder("shared-opencode")
        val privateDir = folder.newFolder("private-opencode")
        // the shared folder is created by seeding if missing
        val sharedNew = File(shared, "fresh")

        SharedStorageLayout.seedShared(sharedNew, privateDir)

        assertEquals(SharedStorageLayout.DEFAULT_CONFIG, File(sharedNew, "opencode.jsonc").readText())
        assertEquals("", File(sharedNew, "AGENTS.md").readText())
        listOf("skills", "agents", "commands").forEach { assertTrue(File(sharedNew, it).isDirectory) }
    }

    @Test
    fun `first activation copies the private config, agents and dir contents`() {
        val privateDir = folder.newFolder("private2")
        File(privateDir, "opencode.jsonc").writeText("{\"model\":\"private\"}")
        File(privateDir, "AGENTS.md").writeText("be terse")
        File(privateDir, "skills/review").apply { mkdirs(); resolve("SKILL.md").writeText("skill") }
        val sharedDir = File(folder.root, "shared2")

        SharedStorageLayout.seedShared(sharedDir, privateDir)

        assertEquals("{\"model\":\"private\"}", File(sharedDir, "opencode.jsonc").readText())
        assertEquals("be terse", File(sharedDir, "AGENTS.md").readText())
        assertEquals("skill", File(sharedDir, "skills/review/SKILL.md").readText())
    }

    @Test
    fun `seeding never overwrites an existing shared file`() {
        val privateDir = folder.newFolder("private3")
        File(privateDir, "opencode.jsonc").writeText("{\"model\":\"private\"}")
        File(privateDir, "AGENTS.md").writeText("private agents")
        val sharedDir = folder.newFolder("shared3")
        File(sharedDir, "opencode.jsonc").writeText("{\"model\":\"user\"}")
        File(sharedDir, "AGENTS.md").writeText("user agents")

        val created = SharedStorageLayout.seedShared(sharedDir, privateDir)

        assertEquals("{\"model\":\"user\"}", File(sharedDir, "opencode.jsonc").readText())
        assertEquals("user agents", File(sharedDir, "AGENTS.md").readText())
        assertTrue("nothing that existed is reported created", created.none { it.name == "opencode.jsonc" || it.name == "AGENTS.md" })
    }

    @Test
    fun `dir contents are copied only when the shared dir is empty, never merged into a non-empty one`() {
        val privateDir = folder.newFolder("private4")
        File(privateDir, "commands").apply { mkdirs(); resolve("private.md").writeText("p") }
        val sharedDir = folder.newFolder("shared4")
        File(sharedDir, "commands").apply { mkdirs(); resolve("mine.md").writeText("m") }

        SharedStorageLayout.seedShared(sharedDir, privateDir)

        assertFalse(File(sharedDir, "commands/private.md").exists())
        assertEquals("m", File(sharedDir, "commands/mine.md").readText())
    }

    @Test
    fun `README describes the layout and recommends workspace-private for symlink-heavy projects`() {
        val text = SharedStorageLayout.readme(File("/storage/emulated/0/AndChamber"))

        assertTrue(text.contains("/workspace-private"))
        assertTrue(text.contains("symlinks"))
        assertTrue(text.contains("auth.json"))
    }

    // --- Decision and restartRequired ---

    @Test
    fun `status is Off unless enabled, NeedsPermission when enabled without access, Active when all hold`() {
        assertEquals(SharedStorageStatus.Off, SharedStorageDecision.status(false, true, true))
        assertEquals(SharedStorageStatus.NeedsPermission, SharedStorageDecision.status(true, false, true))
        assertEquals(SharedStorageStatus.NeedsPermission, SharedStorageDecision.status(true, true, false))
        assertEquals(SharedStorageStatus.Active, SharedStorageDecision.status(true, true, true))
    }

    @Test
    fun `restartRequired only when a running server was started with a different bind set`() {
        val off = SharedStorageStatus.Off to listOf("a")
        val active = SharedStorageStatus.Active to listOf("b")

        assertFalse("no server started: nothing to restart", SharedStorageDecision.restartRequired(null, off))
        assertFalse("same binds: no restart", SharedStorageDecision.restartRequired(off, off))
        assertTrue("toggled on since start: restart", SharedStorageDecision.restartRequired(off, active))
        assertTrue("same status but different path: restart", SharedStorageDecision.restartRequired(active, SharedStorageStatus.Active to listOf("c")))
    }

    private class MemPrefs(var on: Boolean) : io.andchamber.app.runtime.storage.SharedStoragePrefs {
        override fun enabled() = on

        override fun setEnabled(enabled: Boolean) {
            on = enabled
        }
    }

    /** End to end on temp dirs: toggling on flips to NeedsPermission, then Active after a start; a toggle
     * after the start asks for a restart; stopping clears it. */
    @Test
    fun `controller reports restartRequired after toggling on while a server runs`() {
        val rootDir = folder.newFolder("runtime-root")
        val paths = io.andchamber.app.runtime.RuntimePaths(rootDir)
        val hostRoot = folder.newFolder("sdcard-AndChamber")
        var granted = true
        val prefs = MemPrefs(on = false)
        val controller =
            io.andchamber.app.runtime.storage.SharedStorageControllerImpl(
                paths = paths,
                hostRoot = hostRoot,
                prefs = prefs,
                permission = io.andchamber.app.runtime.storage.SharedStoragePermission { granted },
            )

        // Off: start with private binds.
        val offBinds = controller.prepareForStart()
        controller.recordStarted(offBinds)
        assertEquals(SharedStorageStatus.Off, controller.state.value.status)
        assertFalse(controller.state.value.restartRequired)

        // Toggle on while running: active on the next start, so a restart is required.
        controller.setEnabled(true)
        assertTrue(controller.state.value.restartRequired)

        // Restart with the active binds: the requirement clears.
        val activeBinds = controller.prepareForStart()
        controller.recordStarted(activeBinds)
        assertEquals(SharedStorageStatus.Active, controller.state.value.status)
        assertFalse(controller.state.value.restartRequired)
        assertTrue("activation writes the README", File(hostRoot, "README.txt").isFile)
        assertTrue(File(hostRoot, "opencode/opencode.jsonc").isFile)
        assertTrue("the private home placeholder exists before the file bind", File(paths.home, ".config/opencode/opencode.jsonc").exists())

        // Permission revoked: falls back to NeedsPermission without silently keeping the old binds.
        granted = false
        controller.refreshPermission()
        assertEquals(SharedStorageStatus.NeedsPermission, controller.state.value.status)
        assertTrue(controller.state.value.restartRequired)

        // Stop clears the requirement.
        controller.recordStopped()
        assertFalse(controller.state.value.restartRequired)
    }

    @Test
    fun `placeholders are created only for missing config files, and existing ones are kept`() {
        val home = folder.newFolder("home")
        File(home, ".config/opencode").mkdirs()
        File(home, ".config/opencode/AGENTS.md").writeText("keep me")

        val needed = SharedStorageDecision.placeholdersNeeded(home)

        assertEquals(listOf("opencode.jsonc"), needed.map { it.name })
    }
}
