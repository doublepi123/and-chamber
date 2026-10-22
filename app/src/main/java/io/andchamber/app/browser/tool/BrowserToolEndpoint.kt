package io.andchamber.app.browser.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * The one endpoint file the App writes for a local launch. It names a loopback port and the launch
 * token; it is never in the process environment, a config overlay, or a log.
 *
 * The file is owner-only (`0600`) inside an owner-only directory (`0700`), written atomically (temp
 * file plus rename) and never through a symlink.
 */
object BrowserToolEndpoint {
    const val VERSION = 1
    const val TOOL_PATH = "/v1/tool"
    const val CANCEL_PATH = "/v1/cancel"

    /** Guest path, relative to the private home bound over `/root`. */
    const val RELATIVE = ".local/state/andchamber/browser-tool.json"
    const val GUEST_FILE = "/root/.local/state/andchamber/browser-tool.json"

    const val TOKEN_BYTES = 32

    private const val PRIVATE_DIR_MODE = "rwx------"
    private val privateDirectories =
        listOf(
            ".local",
            ".local/state",
            ".local/state/andchamber",
        )

    private val PRIVATE_FILE_MODE = PosixFilePermissions.fromString("rw-------")

    private val TOKEN_PATTERN = Regex("^[0-9a-f]{64}$")
    private val json = Json { encodeDefaults = true }

    /** Host path of the endpoint file under [home] (`runtime/home`, the guest `/root`). */
    fun hostFile(home: File): File = File(home, RELATIVE)

    /** A fresh 32-byte token, lowercase hex, for one launch. */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Exact endpoint document. Key order is fixed so it is stable and testable. */
    fun content(
        port: Int,
        token: String,
    ): String {
        require(port in 1..65535) { "port must be ephemeral loopback" }
        require(TOKEN_PATTERN.matches(token)) { "token must be 32 bytes of lowercase hex" }
        val base = "http://127.0.0.1:$port"
        return buildJsonObject {
            put("version", VERSION)
            put("url", base + TOOL_PATH)
            put("cancelUrl", base + CANCEL_PATH)
            put("token", token)
        }.let { json.encodeToString(JsonObject.serializer(), it) }
    }

    /**
     * Writes the endpoint atomically with mode `0600` after the tool server is listening. [home] is
     * the private home below [trustedRoot]; only the `.local/state/andchamber` chain is chmod'd.
     * A symlink in that chain fails closed, and an existing symlink at the leaf is replaced by a real
     * file (its target is never written through).
     */
    fun write(
        file: File,
        home: File,
        trustedRoot: File,
        port: Int,
        token: String,
    ) {
        val parentSuffix = TrustedPath.suffixBelow(trustedRoot, file.parentFile)
            ?: error("Endpoint file is outside the trusted root")
        val homeSuffix = TrustedPath.suffixBelow(trustedRoot, home)
            ?: error("Browser home is outside the trusted root")
        check(TrustedPath.noSymlinkComponents(trustedRoot, parentSuffix)) {
            "Refusing a symlinked endpoint parent"
        }
        TrustedPath.ensureDirectories(trustedRoot, homeSuffix)
        privateDirectories.forEach { ensurePrivateDirectory(home, it) }
        val target = file.toPath()
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(target)
        ) {
            error("Endpoint path is not a regular file")
        }
        val temp = Files.createTempFile(file.parentFile.toPath(), ".browser-tool", ".tmp")
        try {
            Files.write(temp, content(port, token).toByteArray(Charsets.UTF_8))
            Files.setPosixFilePermissions(temp, PRIVATE_FILE_MODE)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Removes the endpoint file. A missing file is success. A symlink leaf is unlinked without
     * following it; a symlink in the parent chain fails closed and deletes nothing.
     */
    fun delete(
        file: File,
        trustedRoot: File,
    ): Boolean {
        val parentSuffix = TrustedPath.suffixBelow(trustedRoot, file.parentFile) ?: return false
        if (!TrustedPath.noSymlinkComponents(trustedRoot, parentSuffix)) return false
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.deleteIfExists(path)
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.io.IOException) {
            false
        }
    }

    private fun ensurePrivateDirectory(
        home: File,
        relative: String,
    ) {
        var cursor = home
        for (part in relative.split('/')) {
            cursor = File(cursor, part)
            val path = cursor.toPath()
            if (Files.isSymbolicLink(path)) error("Refusing a symlinked endpoint directory")
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    error("Endpoint parent is not a directory")
                }
                Files.createDirectory(path)
            }
            val current = runCatching { Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            val next = current?.let { (it - GROUP_AND_OTHER) + OWNER_RWX } ?: PosixFilePermissions.fromString(PRIVATE_DIR_MODE)
            Files.setPosixFilePermissions(path, next)
        }
    }

    private val OWNER_RWX =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

    private val GROUP_AND_OTHER =
        setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
}
