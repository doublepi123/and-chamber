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
    /** Finishes or rolls back an environment swap interrupted by a process death. */
    fun recoverInterruptedActivation()

    /** The active environment, or null when nothing usable is installed. */
    fun installedRuntime(): InstalledRuntime?

    /** Builds a fresh environment in staging and atomically activates it. Throws on failure. */
    suspend fun install(report: InstallReporter): InstalledRuntime

    /** Removes the environment and metadata but keeps the workspace and secrets. */
    fun discardEnvironment()

    /** Removes the whole runtime directory. */
    fun removeAll()
}
