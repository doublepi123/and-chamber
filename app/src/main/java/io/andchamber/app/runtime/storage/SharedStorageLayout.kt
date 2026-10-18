package io.andchamber.app.runtime.storage

import java.io.File
import java.nio.file.Files

/**
 * Layout of the shared OpenCode config folder and the private exceptions that stay out of it.
 *
 * The whole `opencode/` folder is bound over the guest config folder. A bind of a single file breaks
 * atomic saves (a rename onto a file bind lands on the private placeholder), so the directory is the
 * unit of binding, and the few private exceptions are nested binds on top of it.
 */
object SharedStorageLayout {
    const val PROJECTS = "projects"
    const val OPENCODE = "opencode"
    const val README = "README.txt"

    /** Guest paths. */
    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_PRIVATE_WORKSPACE = "/workspace-private"
    const val GUEST_OPENCODE_CONFIG = "/root/.config/opencode"

    /** Holds a password, so it stays private. OpenCode also writes `<configFile>.tmp` next to it. */
    const val SERVICE_FILE = "service.json"
    const val SERVICE_TMP = "service.json.tmp"

    /** Package installs need symlinks, which shared storage cannot hold, so this stays private. */
    const val NODE_MODULES = "node_modules"

    const val DEFAULT_CONFIG = "{\"\$schema\":\"https://opencode.ai/config.json\"}\n"

    /** Config entry points: at least one must exist in the shared folder, else a default is created. */
    val CONFIG_CANDIDATES = listOf("opencode.jsonc", "opencode.json")

    /** Private names that are never migrated to, or copied from, the shared folder. */
    val PRIVATE_NAMES = setOf(SERVICE_FILE, SERVICE_TMP, NODE_MODULES)

    fun projectsDir(hostRoot: File) = File(hostRoot, PROJECTS)

    fun opencodeDir(hostRoot: File) = File(hostRoot, OPENCODE)

    /** One step of the migration plan: copy [from] (private) to [to] (shared), or skip with a reason. */
    sealed interface Step {
        data class Copy(val from: File, val to: File) : Step

        data class Skip(val from: File, val reason: String) : Step
    }

    /**
     * Plans the one-time migration of private OpenCode config into the shared folder. Rules:
     *  - private secrets and node_modules are never copied;
     *  - a zero-byte private file is a placeholder from the previous version and is skipped when the
     *    shared file already exists (never overwrite, never replace real content with nothing);
     *  - a shared file that already exists is never overwritten;
     *  - directories merge recursively: only the entries missing in shared are planned.
     */
    fun planMigration(
        privateDir: File,
        sharedDir: File,
    ): List<Step> {
        val steps = mutableListOf<Step>()
        privateDir.listFiles().orEmpty().sortedBy { it.name }.forEach { source ->
            planEntry(source, File(sharedDir, source.name), steps = steps, topLevel = true)
        }
        return steps
    }

    private fun planEntry(
        source: File,
        target: File,
        steps: MutableList<Step>,
        topLevel: Boolean,
    ) {
        if (Files.isSymbolicLink(source.toPath())) {
            steps += Step.Skip(source, "symbolic link")
            return
        }
        if (topLevel && source.name in PRIVATE_NAMES) {
            steps += Step.Skip(source, "private exception")
            return
        }
        if (source.isDirectory) {
            if (target.exists() && !target.isDirectory) {
                steps += Step.Skip(source, "shared path is a file")
                return
            }
            source.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                planEntry(child, File(target, child.name), steps, topLevel = false)
            }
            return
        }
        if (target.exists()) {
            steps += Step.Skip(source, "shared file exists")
            return
        }
        if (source.length() == 0L) {
            steps += Step.Skip(source, "empty placeholder")
            return
        }
        steps += Step.Copy(source, target)
    }

    /**
     * Runs the plan. The planner already excluded symbolic links and existing targets, so every
     * [Step.Copy] source is a regular file and `overwrite = false` is a second guard against replacing
     * anything. Returns the copied targets.
     */
    fun applyMigration(steps: List<Step>): List<File> {
        val copied = mutableListOf<File>()
        steps.filterIsInstance<Step.Copy>().forEach { step ->
            step.to.parentFile?.mkdirs()
            if (Files.isSymbolicLink(step.from.toPath()) || step.to.exists()) return@forEach
            step.from.copyTo(step.to, overwrite = false)
            copied += step.to
        }
        return copied
    }

    /**
     * Guarantees a usable config entry point after migration: when the shared folder has neither
     * opencode.jsonc nor opencode.json, creates opencode.jsonc with the default content. Also creates
     * the skills/agents/commands folders so the guest finds them.
     */
    fun ensureMinimal(sharedDir: File): List<File> {
        val created = mutableListOf<File>()
        sharedDir.mkdirs()
        if (CONFIG_CANDIDATES.none { File(sharedDir, it).isFile }) {
            File(sharedDir, CONFIG_CANDIDATES.first()).also {
                it.writeText(DEFAULT_CONFIG)
                created += it
            }
        }
        listOf("skills", "agents", "commands").forEach { name ->
            File(sharedDir, name).takeIf { !it.exists() }?.let {
                it.mkdirs()
                created += it
            }
        }
        return created
    }

    /** The README text. English. Lists the private exceptions explicitly. */
    fun readme(hostRoot: File): String =
        """
        AndChamber shared folder
        ========================

        ${hostRoot.absolutePath}/
          projects/   Your project folders. Opened as the working directory (/workspace)
                      inside the Linux environment when shared storage is active.
          opencode/   The OpenCode global config folder. It is bound over the environment's
                      ~/.config/opencode, so every file here is live config: opencode.jsonc,
                      AGENTS.md, skills/, agents/, commands/, and any other file OpenCode or its
                      plugins read (e.g. oh-my-opencode-slim.json, cli.json). Edits from a file
                      manager, a PC, or inside the app all land here.

        Kept private to the app (never placed here, not readable by other apps):
          - service.json and its .tmp file: they contain a password,
          - node_modules: plugin installs need symlinks, which shared storage cannot hold,
          - OpenCode's session database, auth.json credentials,
          - OpenChamber's data directory and secrets.

        Caveat: shared storage has no symlinks and no executable bits. Projects that rely on
        symlinks, node_modules or executable scripts can misbehave here, and git may report
        mode changes. For those projects use /workspace-private, which is always the app's
        private workspace.
        """.trimIndent() + "\n"
}
