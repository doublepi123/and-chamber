package io.andchamber.app.browser.tool

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Lexical containment below an app-private root, plus a check that no existing component below that
 * root is a symlink. The root itself may be a symlink and is never inspected or chmod'd.
 */
internal object TrustedPath {
    /**
     * Path of [path] relative to [trustedRoot], using only lexical normalization. Returns null when
     * [path] escapes the root. The empty string names the root itself.
     */
    fun suffixBelow(
        trustedRoot: File,
        path: File,
    ): String? {
        val rootPath = trustedRoot.absoluteFile.normalize().path
        val childPath = path.absoluteFile.normalize().path
        if (childPath == rootPath) return ""
        val prefix = rootPath + File.separator
        if (!childPath.startsWith(prefix)) return null
        val suffix = childPath.removePrefix(prefix)
        val parts = suffix.split(File.separator)
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return suffix
    }

    /** True when no existing component of [suffix] below [trustedRoot] is a symlink. */
    fun noSymlinkComponents(
        trustedRoot: File,
        suffix: String,
    ): Boolean {
        if (suffix.isEmpty()) return true
        var cursor = trustedRoot
        val parts = suffix.split(File.separator)
        for ((index, part) in parts.withIndex()) {
            cursor = File(cursor, part)
            val node = cursor.toPath()
            if (Files.isSymbolicLink(node)) return false
            if (!Files.exists(node, LinkOption.NOFOLLOW_LINKS)) return true
            if (index != parts.lastIndex && !Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) return false
        }
        return true
    }

    /** Creates missing real directories for [suffix]. A symlink anywhere below the root fails. */
    fun ensureDirectories(
        trustedRoot: File,
        suffix: String,
    ) {
        if (suffix.isEmpty()) return
        var cursor = trustedRoot
        for (part in suffix.split(File.separator)) {
            cursor = File(cursor, part)
            val path = cursor.toPath()
            if (Files.isSymbolicLink(path)) error("Refusing to use a symlinked directory")
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        }
    }
}
