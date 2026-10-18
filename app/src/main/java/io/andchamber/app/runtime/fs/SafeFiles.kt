package io.andchamber.app.runtime.fs

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/*
 * Tree operations that never follow symbolic links. The guest rootfs and the user's home contain
 * links, and `File.deleteRecursively()` / `File.walk()` follow directory links, so a link pointing
 * at a directory outside the runtime would have that directory's contents deleted. Here links are
 * unlinked and never traversed, which also rules out loops.
 */

/**
 * Removes [path] and everything below it without following links. A symlink at any depth (including
 * [path] itself) is unlinked and its target is left alone. Returns true when [path] no longer
 * exists (including when it never did); false when any entry could not be removed.
 */
fun deleteTree(path: File): Boolean {
    val root = path.toPath()
    if (!root.existsNoFollow()) return true
    return removeNode(root, retriedPermissions = false) && !root.existsNoFollow()
}

/**
 * Removes one node without following links. Written as an explicit recursion instead of
 * `walkFileTree`: the walker opens a directory before any callback runs, so a directory with no
 * read or execute bit (mode 000) would fail before it could be made accessible.
 *
 * Each directory gets at most one permission-repair retry. Symlinks are unlinked, never entered.
 */
private fun removeNode(
    node: Path,
    retriedPermissions: Boolean,
): Boolean {
    if (!Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) return unlink(node)

    val children = listChildren(node)
    if (children == null) {
        // Unreadable directory (mode 000 or no execute bit): grant owner rwx and list once more.
        if (retriedPermissions || !makeOwnerAccessible(node)) return false
        return removeNode(node, retriedPermissions = true)
    }

    // Emptying a directory needs write+execute on it. A read-only directory (0500) lists fine but
    // refuses to unlink its entries, so give the owner write+exec before touching the children.
    if (!retriedPermissions && !Files.isWritable(node)) makeOwnerAccessible(node)

    var complete = true
    for (child in children) {
        if (!removeNode(child, retriedPermissions = false)) complete = false
    }
    if (!complete) return false
    if (unlink(node)) return true
    // Removing the directory itself was denied (its parent is read-only, or it has no write bit).
    if (retriedPermissions) return false
    return makeParentWritable(node) && removeNode(node, retriedPermissions = true)
}

private fun listChildren(directory: Path): List<Path>? =
    try {
        Files.newDirectoryStream(directory).use { stream -> stream.toList() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun unlink(path: Path): Boolean =
    try {
        Files.deleteIfExists(path)
        !path.existsNoFollow()
    } catch (_: IOException) {
        false
    }

/** Adds owner rwx to a directory we are about to delete. Only adds bits; never follows a link. */
private fun makeOwnerAccessible(directory: Path): Boolean =
    try {
        val current = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(directory, current + OWNER_RWX)
        true
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        directory.toFile().let { it.setReadable(true, true) && it.setWritable(true, true) && it.setExecutable(true, true) }
    }

/**
 * Adds owner write+execute to the parent so its entry can be unlinked. Only adds bits to the
 * existing mode, so no other permission is widened.
 */
private fun makeParentWritable(node: Path): Boolean {
    val parent = node.parent ?: return false
    if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return false
    return try {
        val current = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(parent, current + OWNER_WRITE_EXECUTE)
        true
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        parent.toFile().let { it.setWritable(true, true) && it.setExecutable(true, true) }
    }
}

private val OWNER_WRITE_EXECUTE =
    setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)

private val OWNER_RWX = OWNER_WRITE_EXECUTE + PosixFilePermission.OWNER_READ

/** [deleteTree] that throws, so a caller cannot continue past a leftover tree. */
fun deleteTreeOrThrow(path: File) {
    check(deleteTree(path)) { "Unable to delete ${path.name}" }
}

/**
 * Total size in bytes of the regular files below [path], without following links. Symlinks count as
 * zero, so a link into another volume is not charged to this tree. Unreadable entries are skipped.
 */
fun treeSize(path: File): Long {
    val root = path.toPath()
    if (!root.existsNoFollow()) return 0L
    var total = 0L
    try {
        Files.walkFileTree(
            root,
            emptySet<FileVisitOption>(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isRegularFile) total += attributes.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    } catch (_: IOException) {
        // A partial total is the best available answer for a disk-usage display.
    }
    return total
}

/**
 * Copies the regular files, directories and symlinks below [source] into [destination] without
 * following links. Entries that cannot be copied are skipped; used for the non-essential home skeleton.
 */
fun copyTreeNoFollow(
    source: File,
    destination: File,
) {
    val from = source.toPath()
    if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) return
    val to = destination.toPath()
    Files.createDirectories(to)
    Files.walkFileTree(
        from,
        emptySet<FileVisitOption>(),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                runCatching { Files.createDirectories(to.resolve(from.relativize(dir))) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                val target = to.resolve(from.relativize(file))
                runCatching {
                    if (attributes.isSymbolicLink) {
                        Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                    } else {
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
}

private fun Path.existsNoFollow(): Boolean = Files.exists(this, LinkOption.NOFOLLOW_LINKS)
