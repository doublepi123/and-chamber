package io.andchamber.app.runtime.storage

import java.io.File

/**
 * The effective bind mounts for one server launch. Pure data, turned into PRoot `-b` arguments by
 * ProotCommands.
 *
 * Order matters and is fixed:
 *  1. the persistent private home over /root,
 *  2. the workspace binds (/workspace, /workspace-private),
 *  3. the whole shared OpenCode folder over /root/.config/opencode (Active only),
 *  4. private exceptions nested on top of that folder: service.json, service.json.tmp, node_modules.
 *
 * Step 4 must come after step 3, otherwise the directory bind would hide the private files.
 */
data class GuestBinds(
    /** Persistent private home, bound over /root. Always present. */
    val homeHostPath: File,
    /** Working directory mount at /workspace: shared projects when active, else the private workspace. */
    val workspaceHostPath: File,
    /** The private workspace, always bound at /workspace-private. */
    val privateWorkspaceHostPath: File,
    /** Shared OpenCode config folder, bound over the guest config dir. Null when shared config is off. */
    val sharedOpencodeHostPath: File?,
    /** Private exceptions nested over the shared folder: (host path, guest path). Empty when off. */
    val privateExceptions: List<Pair<File, String>>,
) {
    /** Ordered `host:guest` specs for PRoot `-b`, following the fixed order above. */
    fun specs(): List<String> =
        buildList {
            add("${homeHostPath.absolutePath}:/root")
            add("${workspaceHostPath.absolutePath}:${SharedStorageLayout.GUEST_WORKSPACE}")
            add("${privateWorkspaceHostPath.absolutePath}:${SharedStorageLayout.GUEST_PRIVATE_WORKSPACE}")
            sharedOpencodeHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_OPENCODE_CONFIG}") }
            privateExceptions.forEach { (host, guest) -> add("${host.absolutePath}:$guest") }
        }

    companion object {
        /** Private-only: /workspace is the private workspace, also at /workspace-private, no config bind. */
        fun privateOnly(
            home: File,
            privateWorkspace: File,
        ) = GuestBinds(
            homeHostPath = home,
            workspaceHostPath = privateWorkspace,
            privateWorkspaceHostPath = privateWorkspace,
            sharedOpencodeHostPath = null,
            privateExceptions = emptyList(),
        )

        /**
         * Shared: projects at /workspace, the whole shared opencode folder over the guest config dir, and
         * the private exceptions on top. [privateExceptionsDir] holds the empty placeholder files and the
         * node_modules directory, so renames of service.json and service.json.tmp stay in private storage.
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
                workspaceHostPath = sharedProjects,
                privateWorkspaceHostPath = privateWorkspace,
                sharedOpencodeHostPath = sharedOpencode,
                privateExceptions = exceptions,
            )
        }
    }
}
