package io.andchamber.app.runtime.storage

import java.io.File

/**
 * The effective bind mounts for one server launch. Pure data, turned into PRoot `-b` arguments by
 * ProotCommands. Every mount is under the persistent home (`/root`, i.e. `~`).
 *
 * Order matters and is fixed:
 *  1. the persistent private home over /root,
 *  2. the project binds: `~/projects` and, in Active mode, `~/projects-private`,
 *  3. the whole shared OpenCode folder over `~/.config/opencode` (Active only),
 *  4. private exceptions nested on top of that folder: service.json, service.json.tmp, node_modules.
 *
 * Step 4 must come after step 3, otherwise the directory bind would hide the private files. The server's
 * working directory is always `~` (see ProotCommands).
 */
data class GuestBinds(
    /** Persistent private home, bound over /root. Always present. */
    val homeHostPath: File,
    /** Mounted at ~/projects: shared projects when active, else the private workspace. */
    val projectsHostPath: File,
    /** The private workspace, mounted at ~/projects-private. Only in Active mode (null otherwise). */
    val privateProjectsHostPath: File?,
    /** Shared OpenCode config folder, bound over the guest config dir. Null when shared config is off. */
    val sharedOpencodeHostPath: File?,
    /** Private exceptions nested over the shared folder: (host path, guest path). Empty when off. */
    val privateExceptions: List<Pair<File, String>>,
) {
    /** Ordered `host:guest` specs for PRoot `-b`, following the fixed order above. */
    fun specs(): List<String> =
        buildList {
            add("${homeHostPath.absolutePath}:${SharedStorageLayout.GUEST_HOME}")
            add("${projectsHostPath.absolutePath}:${SharedStorageLayout.GUEST_PROJECTS}")
            privateProjectsHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_PRIVATE_PROJECTS}") }
            sharedOpencodeHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_OPENCODE_CONFIG}") }
            privateExceptions.forEach { (host, guest) -> add("${host.absolutePath}:$guest") }
        }

    companion object {
        /** Private-only: ~/projects is the private workspace. No second mount, no config bind. */
        fun privateOnly(
            home: File,
            privateWorkspace: File,
        ) = GuestBinds(
            homeHostPath = home,
            projectsHostPath = privateWorkspace,
            privateProjectsHostPath = null,
            sharedOpencodeHostPath = null,
            privateExceptions = emptyList(),
        )

        /**
         * Shared: ~/projects is the shared projects folder, ~/projects-private the private workspace, and
         * the shared opencode folder over ~/.config/opencode with the private exceptions on top.
         * [privateExceptionsDir] holds the empty placeholder files and the node_modules directory.
         */
        fun shared(
            home: File,
            privateWorkspace: File,
            sharedProjects: File,
            sharedOpencode: File,
            privateExceptionsDir: File,
        ): GuestBinds {
            val guestConfig = SharedStorageLayout.GUEST_OPENCODE_CONFIG
            val exceptions =
                listOf(SharedStorageLayout.SERVICE_FILE, SharedStorageLayout.SERVICE_TMP).map { name ->
                    File(privateExceptionsDir, name) to "$guestConfig/$name"
                } +
                    (File(privateExceptionsDir, SharedStorageLayout.NODE_MODULES) to
                        "$guestConfig/${SharedStorageLayout.NODE_MODULES}")
            return GuestBinds(
                homeHostPath = home,
                projectsHostPath = sharedProjects,
                privateProjectsHostPath = privateWorkspace,
                sharedOpencodeHostPath = sharedOpencode,
                privateExceptions = exceptions,
            )
        }
    }
}
