package io.andchamber.app.runtime.install

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * The npm package is not modified until both staged trees verify. A failure before the first
 * rename leaves it untouched. A failure after that rename moves the backup back.
 */
object OpenChamberProviderArtifact {
    const val PACKAGE_NAME = "@openchamber/web"
    const val PACKAGE_VERSION = "2.4.0"
    const val REQUIRED_COMMIT = "6a10ee19a12a1542cad01eee5add14e030ba4ac4"
    const val REQUIRED_PATCH_SHA256 = "cff9bb64af8113fa18abd605dc8bd13960fcf4668617c4f2a4674d2c2ffbada1"
    const val SERVER_PREFIX = "packages/web/server/"
    const val DIST_PREFIX = "packages/web/dist/"
    const val MARKER = "provider-artifact.json"

    private val json = Json { ignoreUnknownKeys = false }

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

    fun rejection(manifest: LaneManifest, source: File): String? {
        if (manifest.packageName != PACKAGE_NAME) return "unexpected package"
        if (manifest.version != PACKAGE_VERSION) return "unexpected base version"
        if (manifest.baseCommit != REQUIRED_COMMIT) return "unexpected base commit"
        if (manifest.sourcePatchSha256 != REQUIRED_PATCH_SHA256) return "unexpected source patch"
        if (manifest.server.isEmpty() || manifest.dist.isEmpty()) return "artifact must include both server and dist"
        return (manifest.server + manifest.dist).firstNotNullOfOrNull { pin ->
            val relative = guestPath(pin.path) ?: return@firstNotNullOfOrNull "artifact path is outside server/dist"
            if (!safeRelative(relative) || !pin.sha256.matches(SHA256)) return@firstNotNullOfOrNull "artifact pin is invalid"
            val file = File(source, pin.path)
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@firstNotNullOfOrNull "artifact file missing"
            if (file.length() != pin.bytes || sha256(file) != pin.sha256) return@firstNotNullOfOrNull "artifact hash mismatch"
            null
        }
    }

    /**
     * Installs into [moduleRoot] by staging `server` and `dist`, then renaming each tree once.
     * [source] contains the lane paths, including the `packages/web/` prefix.
     */
    fun deploy(manifest: LaneManifest, source: File, moduleRoot: File): DeployResult {
        rejection(manifest, source)?.let { return DeployResult(false, it) }
        val parent = moduleRoot.parentFile ?: return DeployResult(false, "package has no parent")
        val serverStaging = File(parent, ".provider-server-staging")
        val distStaging = File(parent, ".provider-dist-staging")
        if (!clear(serverStaging) || !clear(distStaging)) return DeployResult(false, "unable to clear provider staging")
        return try {
            stage(manifest.server, source, serverStaging, "server/")
            stage(manifest.dist, source, distStaging, "dist/")
            swap(File(moduleRoot, "server"), serverStaging, File(parent, ".provider-server-backup"))
            try {
                swap(File(moduleRoot, "dist"), distStaging, File(parent, ".provider-dist-backup"))
            } catch (error: Exception) {
                restore(File(moduleRoot, "server"), File(parent, ".provider-server-backup"))
                throw error
            }
            clear(File(parent, ".provider-server-backup"))
            clear(File(parent, ".provider-dist-backup"))
            File(moduleRoot, MARKER).writeText(marker(manifest))
            DeployResult(true, "applied")
        } catch (error: Exception) {
            clear(serverStaging)
            clear(distStaging)
            DeployResult(false, "deploy failed before commit: ${error.javaClass.simpleName}")
        }
    }

    fun isApplied(moduleRoot: File, expectedCommit: String = REQUIRED_COMMIT): Boolean {
        val marker = File(moduleRoot, MARKER)
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching { parse(marker.readText()).baseCommit == expectedCommit }.getOrDefault(false)
    }

    private fun stage(files: List<LaneFile>, source: File, staging: File, prefix: String) {
        files.forEach { pin ->
            val relative = guestPath(pin.path)!!.removePrefix(prefix)
            val target = File(staging, relative)
            target.parentFile?.mkdirs()
            Files.copy(File(source, pin.path).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(sha256(target) == pin.sha256) { "staged copy hash changed" }
        }
    }

    private fun swap(live: File, staging: File, backup: File) {
        clear(backup)
        if (live.exists()) {
            check(live.renameTo(backup)) { "unable to move live tree aside" }
        }
        if (!staging.renameTo(live)) {
            backup.renameTo(live)
            error("unable to commit staged tree")
        }
    }

    private fun restore(live: File, backup: File) {
        if (backup.exists()) {
            if (live.exists()) live.renameTo(File(live.parentFile, live.name + ".failed"))
            backup.renameTo(live)
        }
    }

    private fun marker(manifest: LaneManifest): String =
        json.encodeToString(
            LaneManifest.serializer(),
            manifest.copy(server = emptyList(), dist = emptyList()),
        )

    private fun clear(path: File): Boolean = !path.exists() || path.deleteRecursively()

    private fun safeRelative(path: String): Boolean {
        if (path.startsWith("/") || path.contains("\\") || path.contains("\u0000")) return false
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
