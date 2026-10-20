package io.andchamber.app.browser

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Atomic credential file for the host runtime. Integration passes a file under
 * `<runtime home>/.local/state/andchamber/browser-control.json`. This writer refuses a symlink
 * anywhere on the parent chain and never follows one. It does not log the token.
 */
internal class BrowserCredentialFile(
    private val target: File,
    private val trustedRoot: File? = null,
    private val json: Json = Json { encodeDefaults = true },
) {
    fun publish(credential: BrowserCredential) {
        val path = target.toPath().toAbsolutePath()
        assertUnderTrustedRoot(path)
        assertNoSymlinkChain(path.parent ?: error("credential file has no parent"))
        createPrivateParents(path.parent)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "refusing to replace a non-regular credential path"
            }
        }
        val tmp = Files.createTempFile(path.parent, ".browser-control-", ".tmp")
        try {
            Files.write(tmp, json.encodeToString(credential).toByteArray(Charsets.UTF_8))
            restrictOwnerRead(tmp)
            Files.move(
                tmp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            restrictOwnerRead(path)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun delete() {
        val path = target.toPath().toAbsolutePath()
        assertUnderTrustedRoot(path)
        val parent = path.parent
        if (parent != null && chainHasSymlink(parent)) return
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.delete(path)
    }

    private fun assertUnderTrustedRoot(path: Path) {
        val root = trustedRoot ?: return
        val canonicalRoot = root.toPath().toAbsolutePath().normalize()
        val relative = canonicalRoot.relativize(path.normalize())
        check(!relative.startsWith("..") && !relative.isAbsolute) { "credential path escapes the trusted root" }
    }

    private fun assertNoSymlinkChain(start: Path) {
        check(!chainHasSymlink(start)) { "refusing a credential path with a symlink in its parent chain" }
    }

    private fun chainHasSymlink(start: Path): Boolean {
        val root = trustedRoot?.toPath()?.toAbsolutePath()?.normalize()
        var current: Path? = start.toAbsolutePath()
        while (current != null && (root == null || current.startsWith(root) || current == root)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true
            if (root != null && current == root) break
            current = current.parent
        }
        return false
    }

    private fun createPrivateParents(dir: Path) {
        val root = trustedRoot?.toPath()?.toAbsolutePath()?.normalize()
        val missing = mutableListOf<Path>()
        var current: Path? = dir
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current)
            if (root != null && current == root) break
            current = current.parent
        }
        missing.asReversed().forEach { created ->
            Files.createDirectory(created)
            restrictDirectory(created)
        }
        restrictDirectory(dir)
    }

    private fun restrictDirectory(dir: Path) {
        try {
            Files.setPosixFilePermissions(
                dir,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            File(dir.toString()).setReadable(false, false)
            File(dir.toString()).setWritable(false, false)
            File(dir.toString()).setExecutable(false, false)
            File(dir.toString()).setReadable(true, true)
            File(dir.toString()).setWritable(true, true)
            File(dir.toString()).setExecutable(true, true)
        }
    }

    private fun restrictOwnerRead(file: Path) {
        try {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            val io = File(file.toString())
            io.setReadable(false, false)
            io.setWritable(false, false)
            io.setExecutable(false, false)
            io.setReadable(true, true)
            io.setWritable(true, true)
        }
    }
}

@Serializable
internal data class BrowserCredential(
    val version: Int = BrowserLimits.CREDENTIAL_VERSION,
    val endpoint: String,
    val token: String,
    val sessionId: String,
    val expiresAt: Long,
)
