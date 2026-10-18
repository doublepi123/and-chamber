package io.andchamber.app.runtime.storage

import java.io.File

/**
 * The effective bind mounts for one server launch. Pure data, built from the storage decision and
 * the host paths, and turned into PRoot `-b` arguments by ProotCommands.
 *
 * Order matters: the home bind (`/root`) comes first, the nested OpenCode config binds after it, so
 * the nested mounts sit on top of the persistent home rather than being hidden by it.
 */
data class GuestBinds(
    /** Persistent private home, bound over /root. Always present. */
    val homeHostPath: File,
    /** Working directory mount at /workspace: shared projects when active, else the private workspace. */
    val workspaceHostPath: File,
    /** The private workspace, always bound at /workspace-private. */
    val privateWorkspaceHostPath: File,
    /** Nested OpenCode config binds (host path to guest path). Empty unless shared config is active. */
    val nestedConfig: List<Pair<File, String>>,
) {
    /** Ordered `host:guest` specs for PRoot `-b`, home first. */
    fun specs(): List<String> =
        buildList {
            add("${homeHostPath.absolutePath}:/root")
            add("${workspaceHostPath.absolutePath}:${SharedStorageLayout.GUEST_WORKSPACE}")
            add("${privateWorkspaceHostPath.absolutePath}:${SharedStorageLayout.GUEST_PRIVATE_WORKSPACE}")
            nestedConfig.forEach { (host, guest) -> add("${host.absolutePath}:$guest") }
        }

    companion object {
        /**
         * The private-only binds: /workspace is the private workspace (current behaviour) and it is
         * also at /workspace-private. No nested config binds.
         */
        fun privateOnly(
            home: File,
            privateWorkspace: File,
        ) = GuestBinds(
            homeHostPath = home,
            workspaceHostPath = privateWorkspace,
            privateWorkspaceHostPath = privateWorkspace,
            nestedConfig = emptyList(),
        )

        /**
         * Shared binds: projects at /workspace, the private workspace at /workspace-private, and the
         * shared opencode items individually inside the guest config folder. Each guest target is a
         * single item, never the whole opencode directory.
         */
        fun shared(
            home: File,
            privateWorkspace: File,
            sharedProjects: File,
            sharedOpencode: File,
        ): GuestBinds {
            val files =
                SharedStorageLayout.SHARED_FILES.map { name ->
                    File(sharedOpencode, name) to "${SharedStorageLayout.GUEST_OPENCODE_CONFIG}/$name"
                }
            val dirs =
                SharedStorageLayout.SHARED_DIRS.map { name ->
                    File(sharedOpencode, name) to "${SharedStorageLayout.GUEST_OPENCODE_CONFIG}/$name"
                }
            return GuestBinds(
                homeHostPath = home,
                workspaceHostPath = sharedProjects,
                privateWorkspaceHostPath = privateWorkspace,
                nestedConfig = files + dirs,
            )
        }
    }
}
