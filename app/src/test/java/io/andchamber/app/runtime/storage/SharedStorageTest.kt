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
    private val exceptions = File("/data/runtime/home-private/opencode")
    private val hostRoot = File("/storage/emulated/0/AndChamber")

    /** The `host:guest` binds we add. The fixed /dev /proc /sys /system mounts have no colon and are skipped. */
    private fun List<String>.bindPairs(): List<String> =
        zipWithNext().filter { it.first == "-b" && it.second.contains(':') }.map { it.second }

    private fun activeBinds() =
        GuestBinds.shared(
            home = home,
            privateWorkspace = privateWs,
            sharedProjects = File(hostRoot, "projects"),
            sharedOpencode = File(hostRoot, "opencode"),
            privateExceptionsDir = exceptions,
        )

    // --- GuestBinds order and content ---

    @Test
    fun `off binds the private workspace only at projects, home first, with no second mount`() {
        val cmd = ProotCommands.serverCommand(proot, rootfs, GuestBinds.privateOnly(home, privateWs), emptyList(), 3000)

        assertEquals(
            listOf(
                "/data/runtime/home:/root",
                "/data/runtime/workspace:/root/projects",
            ),
            cmd.bindPairs(),
        )
        assertEquals("/root", cmd[cmd.indexOf("-w") + 1])
    }

    @Test
    fun `active binds in the fixed order - home, projects, projects-private, whole opencode dir, then the private exceptions`() {
        val cmd = ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList(), 3000)

        assertEquals(
            listOf(
                "/data/runtime/home:/root",
                "/storage/emulated/0/AndChamber/projects:/root/projects",
                "/data/runtime/workspace:/root/projects-private",
                "/storage/emulated/0/AndChamber/opencode:/root/.config/opencode",
                "/data/runtime/home-private/opencode/service.json:/root/.config/opencode/service.json",
                "/data/runtime/home-private/opencode/service.json.tmp:/root/.config/opencode/service.json.tmp",
                "/data/runtime/home-private/opencode/node_modules:/root/.config/opencode/node_modules",
            ),
            cmd.bindPairs(),
        )
        assertEquals("/root", cmd[cmd.indexOf("-w") + 1])
    }

    @Test
    fun `no bind targets the old workspace mount points`() {
        val all =
            ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList(), 3000).bindPairs() +
                ProotCommands.serverCommand(proot, rootfs, GuestBinds.privateOnly(home, privateWs), emptyList(), 3000).bindPairs()

        assertFalse(all.any { it.endsWith(":/workspace") || it.endsWith(":/workspace-private") })
    }

    @Test
    fun `shared projects mount at home projects and the private workspace at home projects-private`() {
        val pairs = ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList(), 3000).bindPairs()

        assertTrue(pairs.contains("/storage/emulated/0/AndChamber/projects:/root/projects"))
        assertTrue(pairs.contains("/data/runtime/workspace:/root/projects-private"))
    }

    @Test
    fun `the mount points under the home are the ones the binds target`() {
        val targets = activeBinds().specs().map { it.substringAfter(':') }.filter { it.startsWith("/root/") }
        val homeRelative = targets.map { it.removePrefix("/root/") }

        assertEquals(
            SharedStorageLayout.MOUNT_POINTS.toSet(),
            homeRelative.filter { !it.contains("service.json") && !it.contains("node_modules") }.toSet(),
        )
    }

    @Test
    fun `off mode creates only projects in the home, not projects-private or the config dir`() {
        val h = folder.newFolder("home-off")
        val binds = GuestBinds.privateOnly(h, File(h, "unused-ws"))

        val mountPoints = SharedStorageLayout.mountPointsFor(h, binds)
        mountPoints.forEach { it.mkdirs() }

        assertTrue(File(h, "projects").isDirectory)
        assertFalse(File(h, "projects-private").exists())
        assertFalse(File(h, ".config/opencode").exists())
    }

    @Test
    fun `active mode creates projects, projects-private and the opencode config dir in the home`() {
        val h = folder.newFolder("home-active")
        val binds = GuestBinds.shared(h, File(h, "unused-ws"), File("/sdcard/p"), File("/sdcard/o"), File(h, "priv"))

        SharedStorageLayout.mountPointsFor(h, binds).forEach { it.mkdirs() }

        assertTrue(File(h, "projects").isDirectory)
        assertTrue(File(h, "projects-private").isDirectory)
        assertTrue(File(h, ".config/opencode").isDirectory)
    }

    @Test
    fun `the private exceptions come after the directory bind so they are not hidden by it`() {
        val pairs = ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList(), 3000).bindPairs()
        val dirIndex = pairs.indexOf("/storage/emulated/0/AndChamber/opencode:/root/.config/opencode")
        val serviceIndex = pairs.indexOfFirst { it.endsWith(":/root/.config/opencode/service.json") }

        assertTrue(dirIndex in 0 until serviceIndex)
    }

    @Test
    fun `no single-file bind is made for any shared config item`() {
        val pairs = ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList(), 3000).bindPairs()
        val shared = pairs.filter { it.startsWith("/storage/emulated/0/AndChamber/opencode") }

        assertEquals(listOf("/storage/emulated/0/AndChamber/opencode:/root/.config/opencode"), shared)
    }

    // --- Migration matrix ---

    @Test
    fun `migration copies regular config files and skips secrets and node_modules`() {
        val priv = folder.newFolder("priv1")
        File(priv, "oh-my-opencode-slim.json").writeText("{\"a\":1}")
        File(priv, "service.json").writeText("{\"password\":\"x\"}")
        File(priv, "service.json.tmp").writeText("{\"password\":\"y\"}")
        File(priv, "node_modules/pkg").apply { mkdirs(); resolve("index.js").writeText("m") }
        val shared = File(folder.root, "shared1")

        val copied = SharedStorageLayout.applyMigration(SharedStorageLayout.planMigration(priv, shared))

        assertEquals("{\"a\":1}", File(shared, "oh-my-opencode-slim.json").readText())
        assertFalse(File(shared, "service.json").exists())
        assertFalse(File(shared, "service.json.tmp").exists())
        assertFalse(File(shared, "node_modules").exists())
        assertEquals(listOf(File(shared, "oh-my-opencode-slim.json")), copied)
    }

    @Test
    fun `migration never overwrites an existing shared file`() {
        val priv = folder.newFolder("priv2")
        File(priv, "opencode.jsonc").writeText("private")
        val shared = folder.newFolder("shared2")
        File(shared, "opencode.jsonc").writeText("user")

        SharedStorageLayout.applyMigration(SharedStorageLayout.planMigration(priv, shared))

        assertEquals("user", File(shared, "opencode.jsonc").readText())
    }

    @Test
    fun `a zero-byte private placeholder is skipped when the shared file exists`() {
        val priv = folder.newFolder("priv3")
        File(priv, "AGENTS.md").writeText("")
        val shared = folder.newFolder("shared3")
        File(shared, "AGENTS.md").writeText("real rules")

        SharedStorageLayout.applyMigration(SharedStorageLayout.planMigration(priv, shared))

        assertEquals("real rules", File(shared, "AGENTS.md").readText())
    }

    @Test
    fun `a zero-byte private placeholder with no shared counterpart is also not copied`() {
        val priv = folder.newFolder("priv4")
        File(priv, "empty.json").writeText("")
        val shared = File(folder.root, "shared4")

        val steps = SharedStorageLayout.planMigration(priv, shared)

        assertTrue(steps.any { it is SharedStorageLayout.Step.Skip && it.reason == "empty placeholder" })
        assertFalse(File(shared, "empty.json").exists())
    }

    @Test
    fun `directories merge recursively without overwriting existing files`() {
        val priv = folder.newFolder("priv5")
        File(priv, "skills/a").apply { mkdirs(); resolve("SKILL.md").writeText("private a") }
        File(priv, "skills/b").apply { mkdirs(); resolve("SKILL.md").writeText("private b") }
        val shared = folder.newFolder("shared5")
        File(shared, "skills/a").apply { mkdirs(); resolve("SKILL.md").writeText("user a") }

        SharedStorageLayout.applyMigration(SharedStorageLayout.planMigration(priv, shared))

        assertEquals("user a", File(shared, "skills/a/SKILL.md").readText())
        assertEquals("private b", File(shared, "skills/b/SKILL.md").readText())
    }

    @Test
    fun `symbolic links in the private folder are skipped, never followed`() {
        val priv = folder.newFolder("priv6")
        val target = folder.newFile("outside.txt").apply { writeText("outside") }
        java.nio.file.Files.createSymbolicLink(File(priv, "link.json").toPath(), target.toPath())
        val shared = File(folder.root, "shared6")

        val steps = SharedStorageLayout.planMigration(priv, shared)

        assertTrue(steps.any { it is SharedStorageLayout.Step.Skip && it.reason == "symbolic link" })
        assertFalse(File(shared, "link.json").exists())
    }

    @Test
    fun `ensureMinimal adds a default config only when no opencode json or jsonc exists`() {
        val shared = folder.newFolder("shared7")
        File(shared, "opencode.json").writeText("{}")

        SharedStorageLayout.ensureMinimal(shared)

        assertFalse("an existing opencode.json means no default is created", File(shared, "opencode.jsonc").exists())
        listOf("skills", "agents", "commands").forEach { assertTrue(File(shared, it).isDirectory) }

        val empty = folder.newFolder("shared8")
        SharedStorageLayout.ensureMinimal(empty)
        assertEquals(SharedStorageLayout.DEFAULT_CONFIG, File(empty, "opencode.jsonc").readText())
    }

    // --- Private placeholders ---

    @Test
    fun `prepareExceptions creates empty service json and tmp, and a node_modules dir, keeping existing content`() {
        val dir = folder.newFolder("home-private")
        File(dir, "service.json").writeText("{\"password\":\"keep\"}")

        SharedStorageDecision.prepareExceptions(dir)

        assertEquals("{\"password\":\"keep\"}", File(dir, "service.json").readText())
        assertTrue(File(dir, "service.json.tmp").isFile)
        assertEquals(0L, File(dir, "service.json.tmp").length())
        assertTrue(File(dir, "node_modules").isDirectory)
    }

    @Test
    fun `prepareExceptions adopts the existing private service json instead of an empty placeholder`() {
        val dir = folder.newFolder("home-private-2")
        val legacy = folder.newFolder("legacy-config")
        File(legacy, "service.json").writeText("{\"password\":\"real\"}")
        // An empty placeholder from an earlier start must be replaced by the real file.
        File(dir, "service.json").createNewFile()

        SharedStorageDecision.prepareExceptions(dir, legacy)

        assertEquals("{\"password\":\"real\"}", File(dir, "service.json").readText())
        assertEquals(0L, File(dir, "service.json.tmp").length())

        // Once it holds content it is never replaced again.
        File(dir, "service.json").writeText("{\"password\":\"rotated\"}")
        SharedStorageDecision.prepareExceptions(dir, legacy)
        assertEquals("{\"password\":\"rotated\"}", File(dir, "service.json").readText())
    }

    // --- README ---

    @Test
    fun `README describes the whole folder as global config and lists every private exception`() {
        val text = SharedStorageLayout.readme(File("/storage/emulated/0/AndChamber"))

        assertTrue(text.contains("global config folder"))
        assertTrue(text.contains("oh-my-opencode-slim.json"))
        assertTrue(text.contains("service.json"))
        assertTrue(text.contains("node_modules"))
        assertTrue(text.contains("auth.json"))
        assertTrue(text.contains("session"))
        assertTrue(text.contains("OpenChamber"))
        assertTrue(text.contains("~/projects-private"))
        assertTrue(text.contains("~/projects"))
        assertFalse(text.contains("/workspace"))
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

    /** An existing Active user (started on the per-item binds) must be asked to restart on the new bind list. */
    @Test
    fun `a server started on the old per-item bind list needs a restart under the new whole-dir list`() {
        val oldSpecs =
            listOf(
                "/data/runtime/home:/root",
                "/storage/emulated/0/AndChamber/projects:/workspace",
                "/data/runtime/workspace:/workspace-private",
                "/storage/emulated/0/AndChamber/opencode/opencode.jsonc:/root/.config/opencode/opencode.jsonc",
            )
        val current = SharedStorageStatus.Active to activeBinds().specs()

        assertTrue(SharedStorageDecision.restartRequired(SharedStorageStatus.Active to oldSpecs, current))
    }

    private class MemPrefs(var on: Boolean) : SharedStoragePrefs {
        override fun enabled() = on

        override fun setEnabled(enabled: Boolean) {
            on = enabled
        }
    }

    /** End to end on temp dirs: toggling on, activation migrates, then toggling after a start needs a restart. */
    @Test
    fun `controller reports restartRequired after toggling on while a server runs`() {
        val paths = io.andchamber.app.runtime.RuntimePaths(folder.newFolder("runtime-root"))
        val sdcard = folder.newFolder("sdcard-AndChamber")
        var granted = true
        val prefs = MemPrefs(on = false)
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = prefs,
                permission = SharedStoragePermission { granted },
            )

        controller.recordStarted(controller.prepareForStart())
        assertEquals(SharedStorageStatus.Off, controller.state.value.status)
        assertFalse(controller.state.value.restartRequired)

        controller.setEnabled(true)
        assertTrue(controller.state.value.restartRequired)

        controller.recordStarted(controller.prepareForStart())
        assertEquals(SharedStorageStatus.Active, controller.state.value.status)
        assertFalse(controller.state.value.restartRequired)
        assertTrue(File(sdcard, "README.txt").isFile)
        assertTrue(File(sdcard, "opencode/opencode.jsonc").isFile)
        assertTrue("the private exception placeholder exists", File(paths.root, "home-private/opencode/service.json.tmp").isFile)

        granted = false
        controller.refreshPermission()
        assertEquals(SharedStorageStatus.NeedsPermission, controller.state.value.status)
        assertTrue(controller.state.value.restartRequired)

        controller.recordStopped()
        assertFalse(controller.state.value.restartRequired)
    }
}
