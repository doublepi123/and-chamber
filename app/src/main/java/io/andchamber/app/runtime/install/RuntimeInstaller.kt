package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.InstallStep
import io.andchamber.app.runtime.MessageKey
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

/** npm versions of the two packages the runtime installs (`@openchamber/web`, `@opencode/cli`). */
data class PackageVersions(
    val openChamber: String,
    val openCode: String,
)

/**
 * Where newer package versions come from (the npm registry in the app). Implementations do network
 * I/O and must be called off the main thread. Throws when the registry cannot be read.
 */
fun interface PackageVersionSource {
    /**
     * The newest stable versions allowed for this app: same major version as [pinned] (the manifest's
     * versions), no pre-releases, OpenCode at least the app's minimum.
     */
    suspend fun latest(pinned: PackageVersions): PackageVersions
}

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
        messageKey: MessageKey? = null,
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
     * [versions] null installs the versions pinned in the app's runtime manifest.
     * Throws on failure.
     */
    suspend fun install(
        report: InstallReporter,
        versions: PackageVersions? = null,
    ): InstalledRuntime

    /**
     * Updates OpenChamber / OpenCode to [versions] without downloading a new Linux base: copies the
     * active environment into staging, reinstalls the npm packages there, rebuilds node-pty, verifies,
     * then swaps it in like [install]. The active environment is untouched until that swap, so a failed
     * update keeps the old one. The server must be stopped first. Throws on failure (a
     * [io.andchamber.app.runtime.UserFacingException] with `UpdateNotEnoughSpace` when the disk is too full).
     */
    suspend fun update(
        report: InstallReporter,
        versions: PackageVersions,
    ): InstalledRuntime

    /**
     * Removes the environment (rootfs + metadata) but keeps the guest home, workspace and secrets.
     * Not for repair: a repair needs no pre-delete, [install] swaps the environment in place.
     */
    fun discardEnvironment()

    /** Removes the whole runtime directory, including the guest home and workspace. */
    fun removeAll()
}
