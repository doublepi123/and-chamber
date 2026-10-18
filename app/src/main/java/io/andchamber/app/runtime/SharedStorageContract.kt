package io.andchamber.app.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for keeping projects and OpenCode configuration in a public folder on shared storage
 * (`/sdcard/AndChamber`), so they can be maintained with a file manager or over USB.
 *
 * Layout on shared storage (host side):
 * ```
 * /sdcard/AndChamber/
 *   projects/          -> guest ~/projects            (home is the working directory)
 *   opencode/          -> guest /root/.config/opencode/{opencode.jsonc,AGENTS.md,skills,agents,commands}
 * ```
 * Private (app-only) storage keeps: guest ~/projects-private, OpenCode's session database, auth.json
 * credentials, plugin node_modules, and OpenChamber's secrets. Shared storage is readable by other
 * apps that hold all-files access, so secrets never go there.
 */
enum class SharedStorageStatus {
    /** The user has not turned the feature on. Guest ~/projects is private storage. */
    Off,

    /** Turned on, but all-files access is not granted (or was revoked). Falls back to private. */
    NeedsPermission,

    /** Turned on and granted: binds are active from the next server start. */
    Active,
}

data class SharedStorageState(
    val status: SharedStorageStatus,
    /** Host path of the shared root, e.g. /storage/emulated/0/AndChamber. */
    val hostRoot: String,
    /** True when the running server was started with a different binding than the current setting. */
    val restartRequired: Boolean,
)

interface SharedStorageController {
    val state: StateFlow<SharedStorageState>

    /** Turn the feature on or off. Takes effect on the next server start (UI offers a restart). */
    fun setEnabled(enabled: Boolean)

    /** Re-check the permission, e.g. after returning from the system settings screen. */
    fun refreshPermission()
}
