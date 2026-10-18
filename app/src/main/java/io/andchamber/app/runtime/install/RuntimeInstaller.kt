package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.InstallStep
import kotlinx.serialization.Serializable
import java.io.File

/** Persisted in `runtime/metadata.json`; describes the active environment. */
@Serializable
data class RuntimeMetadata(
    val runtimeVersion: String,
    val alpineVersion: String,
    val abi: String,
    val port: Int,
    val openChamberVersion: String,
    val openCodeVersion: String,
    val nodeVersion: String? = null,
    val installedAt: Long,
)

data class InstalledRuntime(
    val metadata: RuntimeMetadata,
    /** `environment/rootfs` of the active environment. */
    val rootfs: File,
)

/** Receives progress and log lines while [RuntimeInstaller.install] runs. */
interface InstallReporter {
    fun step(
        step: InstallStep,
        progress: Float?,
        detail: String?,
    )

    fun log(line: String)
}

/** Provisions and inspects the Linux environment. Implementations must do blocking I/O on IO. */
interface RuntimeInstaller {
    /**
     * Settles a swap that a process death interrupted. Cheap when nothing is pending (one stat), so
     * it is safe to call at every detection. Returns true when a pre-commit swap was rolled back.
     * Throws only when a rollback itself fails.
     */
    fun recover(): Boolean

    /** The active environment, or null when nothing usable is installed. */
    fun installedRuntime(): InstalledRuntime?

    /**
     * Builds a fresh environment in staging, verifies it, then atomically activates it. The active
     * environment is untouched until that swap, so a failed install or repair keeps the old one.
     * Throws on failure.
     */
    suspend fun install(report: InstallReporter): InstalledRuntime

    /**
     * Removes the environment (rootfs + metadata) but keeps the guest home, workspace and secrets.
     * Not for repair: a repair needs no pre-delete, [install] swaps the environment in place.
     */
    fun discardEnvironment()

    /** Removes the whole runtime directory, including the guest home and workspace. */
    fun removeAll()
}
