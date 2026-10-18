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
    ): GuestBinds =
        when (status) {
            SharedStorageStatus.Active ->
                GuestBinds.shared(
                    home = home,
                    privateWorkspace = privateWorkspace,
                    sharedProjects = SharedStorageLayout.projectsDir(hostRoot),
                    sharedOpencode = SharedStorageLayout.opencodeDir(hostRoot),
                )
            SharedStorageStatus.Off, SharedStorageStatus.NeedsPermission ->
                GuestBinds.privateOnly(home = home, privateWorkspace = privateWorkspace)
        }

    /**
     * Placeholders that must exist in the private home before a file bind (PRoot cannot bind onto a
     * path that is absent). Returns the files to create; existing files are left alone.
     */
    fun placeholdersNeeded(home: File): List<File> =
        SharedStorageLayout.SHARED_FILES.map { File(home, ".config/opencode/$it") }
            .filter { !it.exists() }

    /**
     * True when a running server was started with [started] binds but the current decision is
     * [current]. Compares the status and the exact bind list, so a changed path also counts.
     */
    fun restartRequired(
        started: Pair<SharedStorageStatus, List<String>>?,
        current: Pair<SharedStorageStatus, List<String>>,
    ): Boolean = started != null && started != current
}
