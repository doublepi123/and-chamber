package io.andchamber.app.runtime

import java.io.File

/** Every on-disk location of the Linux runtime, rooted at `filesDir/runtime`. */
class RuntimePaths(
    val root: File,
) {
    /** Active environment (rootfs + nothing else). Swapped atomically on install. */
    val environment: File get() = File(root, "environment")
    val rootfs: File get() = File(environment, "rootfs")
    val staging: File get() = File(root, "environment.staging")
    val rollback: File get() = File(root, "environment.rollback")
    val cache: File get() = File(root, "cache")
    val logs: File get() = File(root, "logs")
    val workspace: File get() = File(root, "workspace")
    val prootTmp: File get() = File(root, "proot-tmp")
    val commandSuite: File get() = File(root, "command-suite")
    val metadataFile: File get() = File(root, METADATA_FILE_NAME)
    val uiPasswordFile: File get() = File(root, UI_PASSWORD_FILE_NAME)
    val installLog: File get() = File(logs, "install.log")
    val serverLog: File get() = File(logs, "openchamber.log")

    companion object {
        const val METADATA_FILE_NAME = "metadata.json"
        const val UI_PASSWORD_FILE_NAME = "ui-password"

        fun under(filesDir: File): RuntimePaths = RuntimePaths(File(filesDir, "runtime"))
    }
}
