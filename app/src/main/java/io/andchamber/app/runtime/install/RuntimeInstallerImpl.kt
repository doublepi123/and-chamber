package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.manifest.RuntimeManifest
import io.andchamber.app.runtime.process.EmbeddedCommandSuite
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [RuntimeInstaller] over the on-disk layout. Reads the active environment's metadata and
 * repairs an interrupted swap on startup. Not thread-safe; the controller serialises calls.
 */
class RuntimeInstallerImpl(
    private val paths: RuntimePaths,
    private val abi: String,
    private val manifest: RuntimeManifest,
    private val suite: () -> EmbeddedCommandSuite.Paths,
    private val downloader: VerifiedRuntimeDownloader,
    private val shimAsset: () -> String,
    private val dnsProvider: io.andchamber.app.runtime.network.SystemDnsProvider =
        io.andchamber.app.runtime.network.SystemDnsProvider { emptyList() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RuntimeInstaller {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val installer by lazy {
        OpenChamberInstaller(paths, abi, manifest, downloader, shimAsset, dnsProvider, nowMillis)
    }

    override fun recoverInterruptedActivation() {
        recoverInterruptedRuntimeEnvironment(
            active = paths.environment,
            rollback = paths.rollback,
            topLevelMetadata = paths.metadataFile,
        )
    }

    override fun installedRuntime(): InstalledRuntime? {
        val metadataFile = paths.metadataFile
        if (!metadataFile.isFile || !paths.rootfs.isDirectory) return null
        normalizeRuntimeSymlinks(paths.environment)
        val metadata =
            runCatching { json.decodeFromString<RuntimeMetadata>(metadataFile.readText()) }
                .getOrNull() ?: return null
        if (metadata.abi != abi) return null
        return InstalledRuntime(metadata, paths.rootfs)
    }

    override suspend fun install(report: InstallReporter): InstalledRuntime {
        paths.root.mkdirs()
        recoverInterruptedActivation()
        val proot = suite()
        return installer.install(report, proot)
    }

    override fun discardEnvironment() {
        paths.environment.deleteRecursively()
        paths.metadataFile.delete()
    }

    override fun removeAll() {
        paths.root.deleteRecursively()
    }

    /** The metadata file exists only after a successful activation. */
    internal fun metadataOrNull(): RuntimeMetadata? =
        runCatching { json.decodeFromString<RuntimeMetadata>(paths.metadataFile.readText()) }.getOrNull()

    internal fun root(): File = paths.root
}
