package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.fs.deleteTree
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Verifies the OpenChamber lane's real artifact manifest and installs server+dist as one pair.
 * Paths arrive as `packages/web/server/...` and `packages/web/dist/...`. They are mapped onto the
 * installed package roots `server/` and `dist/`. Hashes are never invented.
 *
 * Every declared path is validated before a single file is written. The npm package is not modified
 * until the request, the target package and both source trees verify. A failure after the first
 * rename restores the previous server/dist pair and the previous marker.
 */
object OpenChamberProviderArtifact {
    const val PACKAGE_NAME = "@openchamber/web"
    const val PACKAGE_VERSION = "2.4.0"
    const val REQUIRED_COMMIT = "6a10ee19a12a1542cad01eee5add14e030ba4ac4"
    const val REQUIRED_PATCH_SHA256 = "2499ecb38f4f59784928aa21a3ca21bb9478a9e82436c32fe6b02f1991d0d952"
    const val SERVER_PREFIX = "packages/web/server/"
    const val DIST_PREFIX = "packages/web/dist/"
    const val MARKER = "provider-artifact.json"

    /**
     * Returned instead of a commit result when the old server/dist pair or marker could not be
     * restored. The backups are left in place and a later deploy refuses to start, so the only old
     * copy is never cleared blindly.
     */
    const val RECOVERY_REQUIRED = "recovery required"

    private val json = Json { ignoreUnknownKeys = false }
    private val packageJson = Json { ignoreUnknownKeys = true }

    @Serializable
    data class LaneManifest(
        @SerialName("package") val packageName: String,
        val version: String,
        val baseCommit: String,
        val sourcePatchSha256: String,
        val server: List<LaneFile> = emptyList(),
        val dist: List<LaneFile> = emptyList(),
    )

    @Serializable
    data class LaneFile(
        val path: String,
        val sha256: String,
        val bytes: Long,
    )

    data class DeployResult(val applied: Boolean, val reason: String)

    fun parse(raw: String): LaneManifest = json.decodeFromString(LaneManifest.serializer(), raw)

    fun guestPath(lanePath: String): String? =
        when {
            lanePath.startsWith(SERVER_PREFIX) -> "server/" + lanePath.removePrefix(SERVER_PREFIX)
            lanePath.startsWith(DIST_PREFIX) -> "dist/" + lanePath.removePrefix(DIST_PREFIX)
            else -> null
        }

    /**
     * Metadata and path checks only: no file writes and no source access. The package, baseline,
     * commit and patch pins must match this build. Each list owns its prefix: a `server` entry must
     * be under [SERVER_PREFIX] and a `dist` entry under [DIST_PREFIX], so a valid file can never be
     * published into the other tree by list placement. Every path must be a canonical relative path;
     * sizes are non-negative and hashes are lowercase SHA-256. Duplicate mapped paths are rejected,
     * so one pin can never overwrite another.
     */
    fun manifestRejection(manifest: LaneManifest): String? {
        if (manifest.packageName != PACKAGE_NAME) return "unexpected package"
        if (manifest.version != PACKAGE_VERSION) return "unexpected base version"
        if (manifest.baseCommit != REQUIRED_COMMIT) return "unexpected base commit"
        if (!SHA256.matches(manifest.sourcePatchSha256) ||
            manifest.sourcePatchSha256 != REQUIRED_PATCH_SHA256
        ) {
            return "unexpected source patch"
        }
        if (manifest.server.isEmpty() || manifest.dist.isEmpty()) return "artifact must include both server and dist"
        val seen = HashSet<String>()
        for ((prefix, pins) in listOf(SERVER_PREFIX to manifest.server, DIST_PREFIX to manifest.dist)) {
            for (pin in pins) {
                if (!pin.path.startsWith(prefix)) return "artifact path is in the wrong group"
                if (!safeRelative(pin.path)) return "artifact path is invalid"
                val mapped = guestPath(pin.path) ?: return "artifact path is outside server/dist"
                if (!safeRelative(mapped)) return "artifact path is invalid"
                if (pin.bytes < 0) return "artifact size is invalid"
                if (!SHA256.matches(pin.sha256)) return "artifact pin is invalid"
                if (!seen.add(mapped)) return "artifact path is duplicated"
            }
        }
        return null
    }

    /**
     * Content checks for a materialized source tree: every file must be a regular file reached
     * without a symlinked ancestor, stay inside [source], and match its pinned byte count and
     * SHA-256. The group prefix rule is re-checked here, so a caller that skips
     * [manifestRejection] still cannot stage a `dist` pin into `server/`.
     */
    fun contentRejection(manifest: LaneManifest, source: File): String? {
        if (!source.isDirectory) return "artifact source is missing"
        for ((prefix, pins) in listOf(SERVER_PREFIX to manifest.server, DIST_PREFIX to manifest.dist)) {
            for (pin in pins) {
                if (!pin.path.startsWith(prefix)) return "artifact path is in the wrong group"
                val file = containedFile(source, pin.path) ?: return "artifact path is unsafe"
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return "artifact file missing"
                if (file.length() != pin.bytes || sha256(file) != pin.sha256) return "artifact hash mismatch"
            }
        }
        return null
    }

    fun rejection(manifest: LaneManifest, source: File): String? =
        manifestRejection(manifest) ?: contentRejection(manifest, source)

    /**
     * Installs into [moduleRoot] by staging `server` and `dist`, then renaming each tree once.
     * [source] contains the lane paths, including the `packages/web/` prefix. [expectedPackageVersion]
     * is the npm version this operation requested: the target `package.json` must name
     * [PACKAGE_NAME] at exactly that version, which must equal the artifact's baseline. Nothing is
     * written before that check passes.
     *
     * After the commit the target package, the full marker pins and the actual server/dist hashes
     * are checked again. Failing that check restores the previous pair and marker.
     */
    fun deploy(
        manifest: LaneManifest,
        source: File,
        moduleRoot: File,
        expectedPackageVersion: String,
    ): DeployResult =
        deploy(manifest, source, moduleRoot, expectedPackageVersion) { from, to -> from.renameTo(to) }

    /**
     * Same deployment with a rename operation for failure-path tests. Production callers use
     * [deploy]; the default is a plain `File.renameTo`. It is not a general file-system abstraction.
     */
    internal fun deployWithRename(
        manifest: LaneManifest,
        source: File,
        moduleRoot: File,
        expectedPackageVersion: String,
        rename: (File, File) -> Boolean,
    ): DeployResult = deploy(manifest, source, moduleRoot, expectedPackageVersion, rename)

    private fun deploy(
        manifest: LaneManifest,
        source: File,
        moduleRoot: File,
        expectedPackageVersion: String,
        rename: (File, File) -> Boolean,
    ): DeployResult {
        manifestRejection(manifest)?.let { return DeployResult(false, it) }
        contentRejection(manifest, source)?.let { return DeployResult(false, it) }
        packageRejection(moduleRoot, expectedPackageVersion, manifest.version)?.let {
            return DeployResult(false, it)
        }
        val parent = moduleRoot.parentFile ?: return DeployResult(false, "package has no parent")
        val serverStaging = File(parent, ".provider-server-staging")
        val distStaging = File(parent, ".provider-dist-staging")
        val serverBackup = File(parent, ".provider-server-backup")
        val distBackup = File(parent, ".provider-dist-backup")
        // A previous attempt ended before it could restore the old pair. Do not clear or replace
        // that backup: it may be the only old copy. Recovery is a separate, explicit step.
        if (exists(serverBackup) || exists(distBackup)) {
            return DeployResult(false, RECOVERY_REQUIRED)
        }
        val marker = File(moduleRoot, MARKER)
        val previousMarker = if (Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            runCatching { marker.readText() }.getOrNull()
        } else {
            null
        }
        if (!clear(serverStaging) || !clear(distStaging)) {
            return DeployResult(false, "unable to clear provider staging")
        }
        try {
            stage(manifest.server, source, serverStaging, "server/")
            stage(manifest.dist, source, distStaging, "dist/")
        } catch (error: Exception) {
            // Nothing was committed, so only our staging is removed. No backup exists (see guard).
            clear(serverStaging)
            clear(distStaging)
            return DeployResult(false, "deploy failed: ${error.javaClass.simpleName}")
        }
        val result =
            commit(
                moduleRoot = moduleRoot,
                parent = parent,
                expectedPackageVersion = expectedPackageVersion,
                manifest = manifest,
                marker = marker,
                previousMarker = previousMarker,
                serverStaging = serverStaging,
                distStaging = distStaging,
                serverBackup = serverBackup,
                distBackup = distBackup,
                rename = rename,
            )
        // Staging is always our new tree; it is never the old copy. Clear it even when recovery is
        // required, but never touch a backup in that case.
        clear(serverStaging)
        clear(distStaging)
        return result
    }

    private fun commit(
        moduleRoot: File,
        parent: File,
        expectedPackageVersion: String,
        manifest: LaneManifest,
        marker: File,
        previousMarker: String?,
        serverStaging: File,
        distStaging: File,
        serverBackup: File,
        distBackup: File,
        rename: (File, File) -> Boolean,
    ): DeployResult {
        val serverLive = File(moduleRoot, "server")
        val distLive = File(moduleRoot, "dist")
        var serverCommitted = false
        var distCommitted = false
        var markerAttempted = false
        try {
            swapTree(serverLive, serverStaging, serverBackup, rename)
            serverCommitted = true
            swapTree(distLive, distStaging, distBackup, rename)
            distCommitted = true
            markerAttempted = true
            marker.writeText(json.encodeToString(LaneManifest.serializer(), manifest))
            val post = postCommitRejection(moduleRoot, manifest, expectedPackageVersion)
            check(post == null) { post ?: "post-commit verification failed" }
        } catch (error: Exception) {
            var recoveryFailed = error is RecoveryRequiredException
            if (distCommitted &&
                !restoreTree(distLive, distBackup, File(parent, ".provider-dist-failed"), rename)
            ) {
                recoveryFailed = true
            }
            if (serverCommitted &&
                !restoreTree(serverLive, serverBackup, File(parent, ".provider-server-failed"), rename)
            ) {
                recoveryFailed = true
            }
            if (!recoveryFailed && markerAttempted) {
                recoveryFailed = !restoreMarker(marker, previousMarker)
            }
            if (recoveryFailed) {
                // Keep both backups and whatever marker is on disk; the reason blocks the next
                // deploy until this module root is recovered or inspected.
                return DeployResult(false, RECOVERY_REQUIRED)
            }
            clear(serverBackup)
            clear(distBackup)
            return DeployResult(false, "deploy failed: ${error.javaClass.simpleName}")
        }
        clear(serverBackup)
        clear(distBackup)
        return DeployResult(true, "applied")
    }

    /** Thrown when the old tree was moved to its backup but could not be moved back. */
    private class RecoveryRequiredException(message: String) : Exception(message)

    /**
     * Moves the live tree aside, then the staged tree into place. On a failed commit the old tree is
     * moved back. If that restore also fails, the backup is left untouched and recovery is required.
     */
    private fun swapTree(live: File, staging: File, backup: File, rename: (File, File) -> Boolean) {
        if (exists(live)) {
            check(rename(live, backup)) { "unable to move live tree aside" }
        }
        if (rename(staging, live)) return
        if (exists(backup)) {
            if (!rename(backup, live)) {
                throw RecoveryRequiredException("unable to restore previous tree")
            }
        }
        error("unable to commit staged tree")
    }

    /**
     * Restores one committed tree from its backup. Returns false without clearing the backup when
     * the new tree cannot be moved aside or the backup cannot be moved back.
     */
    private fun restoreTree(live: File, backup: File, failed: File, rename: (File, File) -> Boolean): Boolean {
        return try {
            clear(failed)
            if (exists(live) && !rename(live, failed)) return false
            if (exists(backup) && !rename(backup, live)) return false
            clear(failed)
            true
        } catch (error: Exception) {
            false
        }
    }

    /** Restores the previous marker; false means recovery is required. */
    private fun restoreMarker(marker: File, previous: String?): Boolean {
        return try {
            if (previous == null) {
                if (!deleteTree(marker)) return false
            } else {
                marker.writeText(previous)
            }
            true
        } catch (error: Exception) {
            false
        }
    }

    /**
     * Whether the package currently looks like the provisioned build. This is the startup-cheap
     * check: target `package.json` name/version plus the complete marker pins (package, version,
     * base commit, patch, non-empty server/dist lists). It does not re-hash the payload; see
     * [verifyInstalled] for that, used after deployment and by tests.
     */
    fun isApplied(moduleRoot: File, expectedVersion: String = PACKAGE_VERSION): Boolean {
        if (packageRejection(moduleRoot, expectedVersion, PACKAGE_VERSION) != null) return false
        val marker = File(moduleRoot, MARKER)
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val stored = runCatching { parse(marker.readText()) }.getOrNull() ?: return false
        if (manifestRejection(stored) != null) return false
        return stored.packageName == PACKAGE_NAME &&
            stored.version == PACKAGE_VERSION &&
            stored.baseCommit == REQUIRED_COMMIT &&
            stored.sourcePatchSha256 == REQUIRED_PATCH_SHA256
    }

    /** [isApplied] plus a hash check of every file the marker lists under server/ and dist/. */
    fun verifyInstalled(moduleRoot: File, expectedVersion: String = PACKAGE_VERSION): Boolean {
        if (!isApplied(moduleRoot, expectedVersion)) return false
        val stored = runCatching { parse(File(moduleRoot, MARKER).readText()) }.getOrNull() ?: return false
        return payloadRejection(moduleRoot, stored) == null
    }

    private fun postCommitRejection(
        moduleRoot: File,
        manifest: LaneManifest,
        expectedPackageVersion: String,
    ): String? {
        packageRejection(moduleRoot, expectedPackageVersion, manifest.version)?.let { return it }
        val marker = File(moduleRoot, MARKER)
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return "marker is missing"
        val stored = runCatching { parse(marker.readText()) }.getOrNull() ?: return "marker is invalid"
        manifestRejection(stored)?.let { return "marker is invalid" }
        if (stored != manifest) return "marker differs from artifact"
        return payloadRejection(moduleRoot, manifest)
    }

    /** Strict read of the target `package.json`; a missing or unparsable file is a rejection. */
    private fun packageRejection(
        moduleRoot: File,
        expectedPackageVersion: String,
        artifactVersion: String,
    ): String? {
        val file = File(moduleRoot, "package.json")
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return "package.json is missing"
        }
        val parsed = runCatching { packageJson.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return "package.json is invalid"
        val nameElement = parsed["name"] ?: return "package.json has no name"
        val versionElement = parsed["version"] ?: return "package.json has no version"
        val name = (nameElement as? JsonPrimitive)?.contentOrNull ?: return "package.json name is invalid"
        val version = (versionElement as? JsonPrimitive)?.contentOrNull ?: return "package.json version is invalid"
        if (name != PACKAGE_NAME) return "package.json names an unexpected package"
        if (version != artifactVersion) return "package.json version differs from the artifact"
        if (version != expectedPackageVersion) return "package.json version differs from the request"
        return null
    }

    private fun payloadRejection(moduleRoot: File, manifest: LaneManifest): String? {
        for (pin in manifest.server + manifest.dist) {
            val relative = guestPath(pin.path) ?: return "artifact path is outside server/dist"
            val file = containedFile(moduleRoot, relative) ?: return "installed artifact path is unsafe"
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return "installed artifact file is missing"
            if (file.length() != pin.bytes || sha256(file) != pin.sha256) return "installed artifact hash mismatch"
        }
        return null
    }

    private fun stage(files: List<LaneFile>, source: File, staging: File, prefix: String) {
        files.forEach { pin ->
            val relative = guestPath(pin.path)!!.removePrefix(prefix)
            val sourceFile = containedFile(source, pin.path) ?: error("artifact path is unsafe")
            check(sha256(sourceFile) == pin.sha256) { "source artifact hash changed" }
            val target = File(staging, relative)
            target.parentFile?.mkdirs()
            check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                "staged path escapes the staging root"
            }
            Files.copy(sourceFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(sha256(target) == pin.sha256) { "staged copy hash changed" }
            check(sha256(sourceFile) == pin.sha256) { "source artifact hash changed during copy" }
        }
    }

    private fun exists(path: File): Boolean = path.exists() || Files.isSymbolicLink(path.toPath())

    /**
     * A file under [root] reached only through real directories. Returns null when the relative
     * path is not canonical, an ancestor is a symlink, or the resolved path leaves [root].
     */
    fun containedFile(root: File, relative: String): File? {
        if (!safeRelative(relative)) return null
        if (Files.isSymbolicLink(root.toPath())) return null
        val rootPath = root.canonicalFile.toPath()
        val segments = relative.split('/')
        var parent = root
        for (segment in segments.dropLast(1)) {
            parent = File(parent, segment)
            if (Files.isSymbolicLink(parent.toPath())) return null
        }
        val file = File(parent, segments.last())
        if (Files.isSymbolicLink(file.toPath())) return null
        if (!file.canonicalFile.toPath().startsWith(rootPath)) return null
        return file
    }

    private fun clear(path: File): Boolean = deleteTree(path)

    private fun safeRelative(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("\u0000")) return false
        if (path.any { it.code < 0x20 }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val SHA256 = Regex("^[0-9a-f]{64}$")
}
