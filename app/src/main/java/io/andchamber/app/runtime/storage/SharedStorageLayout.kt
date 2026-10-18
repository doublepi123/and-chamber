package io.andchamber.app.runtime.storage

import java.io.File

/**
 * Pure description of the shared-storage layout and the seeding rules. No Android APIs here, so the
 * rules are unit-tested against temporary directories.
 */
object SharedStorageLayout {
    const val PROJECTS = "projects"
    const val OPENCODE = "opencode"
    const val README = "README.txt"

    /** Guest paths. */
    const val GUEST_WORKSPACE = "/workspace"
    const val GUEST_PRIVATE_WORKSPACE = "/workspace-private"
    const val GUEST_OPENCODE_CONFIG = "/root/.config/opencode"

    const val CONFIG_FILE = "opencode.jsonc"
    const val AGENTS_FILE = "AGENTS.md"
    const val DEFAULT_CONFIG = "{\"\$schema\":\"https://opencode.ai/config.json\"}\n"

    /** Individual items bound from the shared opencode folder. Nothing else from it is shared. */
    val SHARED_FILES = listOf(CONFIG_FILE, AGENTS_FILE)
    val SHARED_DIRS = listOf("skills", "agents", "commands")

    /** The shared root for [hostRoot]: `<hostRoot>/projects`, `<hostRoot>/opencode`, README. */
    fun projectsDir(hostRoot: File) = File(hostRoot, PROJECTS)

    fun opencodeDir(hostRoot: File) = File(hostRoot, OPENCODE)

    /**
     * Seeds the shared opencode folder from the private one. Rules (see the feature brief):
     *  - never overwrite an existing shared file;
     *  - opencode.jsonc: copy the private one if present, else the default content;
     *  - AGENTS.md: copy the private one if present, else create it empty;
     *  - skills/agents/commands: create the directory; copy the private contents only when the shared
     *    directory is empty (first activation).
     * Returns the list of paths that were created, so callers and tests can see what changed.
     */
    fun seedShared(
        sharedDir: File,
        privateDir: File,
    ): List<File> {
        val created = mutableListOf<File>()
        sharedDir.mkdirs()

        val config = File(sharedDir, CONFIG_FILE)
        if (!config.exists()) {
            val source = File(privateDir, CONFIG_FILE)
            config.writeText(if (source.isFile) source.readText() else DEFAULT_CONFIG)
            created += config
        }

        val agents = File(sharedDir, AGENTS_FILE)
        if (!agents.exists()) {
            val source = File(privateDir, AGENTS_FILE)
            agents.writeText(if (source.isFile) source.readText() else "")
            created += agents
        }

        SHARED_DIRS.forEach { name ->
            val target = File(sharedDir, name)
            if (!target.exists()) {
                target.mkdirs()
                created += target
            }
            val isEmpty = target.list().isNullOrEmpty()
            val source = File(privateDir, name)
            if (isEmpty && source.isDirectory) {
                source.copyRecursively(target, overwrite = false)
                created += target
            }
        }
        return created
    }

    /** The README text written at activation. English, as requested. */
    fun readme(hostRoot: File): String =
        """
        AndChamber shared folder
        ========================

        ${hostRoot.absolutePath}/
          projects/   Your project folders. Opened as the working directory (/workspace)
                      inside the Linux environment when shared storage is active.
          opencode/   OpenCode configuration: opencode.jsonc, AGENTS.md, skills/, agents/,
                      commands/. Bound into the environment's OpenCode config folder.

        Stays private to the app and is never placed here:
          - credentials (auth.json) and OpenCode's session database,
          - plugin files and node_modules,
          - OpenChamber's data and secrets.

        Caveat: shared storage has no symlinks and no executable bits. Projects that rely on
        symlinks, node_modules or executable scripts can misbehave here, and git may report
        mode changes. For those projects use /workspace-private, which is always the app's
        private workspace.
        """.trimIndent() + "\n"
}
