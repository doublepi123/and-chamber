package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.MessageKey
import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.UserFacingException
import io.andchamber.app.runtime.fs.deleteTree
import io.andchamber.app.runtime.fs.deleteTreeOrThrow
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
    private val providerArtifactSource: () -> Pair<OpenChamberProviderArtifact.LaneManifest, java.io.File>? = { null },
) : RuntimeInstaller {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val installer by lazy {
        OpenChamberInstaller(
            paths,
            abi,
            manifest,
            downloader,
            shimAsset,
            dnsProvider,
            nowMillis,
        )
    }

    override fun recover(): Boolean =
        recoverInterruptedRuntimeEnvironment(
            active = paths.environment,
            rollback = paths.rollback,
        )

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

    override suspend fun install(
        report: InstallReporter,
        versions: PackageVersions?,
    ): InstalledRuntime {
        paths.root.mkdirs()
        recover()
        val artifact = loadProviderArtifact()
        try {
            artifact?.let {
                val requested = versions ?: PackageVersions(manifest.openChamberVersion, manifest.openCodeVersion)
                ProviderArtifactPolicy.requireDeployable(it.manifest, requested.openChamber)
            }
            val proot = suite()
            return installer.install(report, proot, versions, artifact)
        } finally {
            artifact?.close()
        }
    }

    override suspend fun update(
        report: InstallReporter,
        versions: PackageVersions,
    ): InstalledRuntime {
        paths.root.mkdirs()
        recover()
        val artifact = loadProviderArtifact()
        try {
            artifact?.let { ProviderArtifactPolicy.requireDeployable(it.manifest, versions.openChamber) }
            val proot = suite()
            return installer.update(report, proot, versions, artifact)
        } finally {
            artifact?.close()
        }
    }

    /**
     * Materializes the provisioned artifact once per operation. A missing asset returns null (stock
     * npm, unchanged). A declared artifact that cannot be read or whose metadata/pins/paths are bad
     * throws: it is never treated as absent and never silently falls back to stock or legacy.
     */
    private fun loadProviderArtifact(): LoadedProviderArtifact? {
        val loaded = try {
            providerArtifactSource()
        } catch (error: UserFacingException) {
            throw error
        } catch (error: Exception) {
            throw UserFacingException(
                MessageKey.OpenChamberInstallFailed,
                "The bundled OpenChamber provider artifact could not be read",
                detail = error.javaClass.simpleName,
            )
        }
        if (loaded == null) return null
        val (manifest, source) = loaded
        return try {
            OpenChamberProviderArtifact.manifestRejection(manifest)?.let { reason ->
                throw UserFacingException(
                    MessageKey.OpenChamberInstallFailed,
                    "The bundled OpenChamber provider artifact is invalid: $reason",
                )
            }
            LoadedProviderArtifact(manifest, source)
        } catch (error: Throwable) {
            deleteTree(source)
            throw error
        }
    }

    override fun discardEnvironment() {
        // Only the environment; /root (home) and the workspace are user data and are kept.
        deleteTreeOrThrow(paths.environment)
        deleteTreeOrThrow(paths.rollback)
    }

    override fun removeAll() {
        deleteTreeOrThrow(paths.root)
    }

    internal fun root(): File = paths.root
}
