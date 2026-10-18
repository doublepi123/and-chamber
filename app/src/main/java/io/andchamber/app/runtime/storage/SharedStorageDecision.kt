package io.andchamber.app.runtime.storage

import io.andchamber.app.runtime.SharedStorageStatus
import java.io.File

/**
 * The status decision and the bind set for one server start. Pure: the caller supplies the facts
 * (enabled, permission, whether the shared folder can be created) and gets back what to do.
 */
object SharedStorageDecision {
    /** Off unless enabled; NeedsPermission when enabled without access; Active only when everything holds. */
    fun status(
        enabled: Boolean,
        permissionGranted: Boolean,
        sharedRootUsable: Boolean,
    ): SharedStorageStatus =
        when {
            !enabled -> SharedStorageStatus.Off
            !permissionGranted || !sharedRootUsable -> SharedStorageStatus.NeedsPermission
            else -> SharedStorageStatus.Active
        }

    /** The binds a server start uses for [status]. Active requires the shared folders to exist. */
    fun binds(
        status: SharedStorageStatus,
        home: File,
        privateWorkspace: File,
        hostRoot: File,
        privateExceptionsDir: File,
    ): GuestBinds =
        when (status) {
            SharedStorageStatus.Active ->
                GuestBinds.shared(
                    home = home,
                    privateWorkspace = privateWorkspace,
                    sharedProjects = SharedStorageLayout.projectsDir(hostRoot),
                    sharedOpencode = SharedStorageLayout.opencodeDir(hostRoot),
                    privateExceptionsDir = privateExceptionsDir,
                )
            SharedStorageStatus.Off, SharedStorageStatus.NeedsPermission ->
                GuestBinds.privateOnly(home = home, privateWorkspace = privateWorkspace)
        }

    /**
     * Private placeholders the Active binds need to exist before the start: the two secret files
     * (empty, so the renames of `service.json.tmp` stay inside private storage) and the node_modules
     * directory. Existing entries are left alone. Returns the paths that were created.
     */
    fun prepareExceptions(privateExceptionsDir: File): List<File> {
        val created = mutableListOf<File>()
        privateExceptionsDir.mkdirs()
        listOf(SharedStorageLayout.SERVICE_FILE, SharedStorageLayout.SERVICE_TMP).forEach { name ->
            File(privateExceptionsDir, name).takeIf { !it.exists() }?.let {
                it.createNewFile()
                created += it
            }
        }
        File(privateExceptionsDir, SharedStorageLayout.NODE_MODULES).takeIf { !it.exists() }?.let {
            it.mkdirs()
            created += it
        }
        return created
    }

    /**
     * True when a running server was started with [started] binds but the current decision is
     * [current]. Compares the status and the exact bind list, so a changed path also counts.
     */
    fun restartRequired(
        started: Pair<SharedStorageStatus, List<String>>?,
        current: Pair<SharedStorageStatus, List<String>>,
    ): Boolean = started != null && started != current
}
