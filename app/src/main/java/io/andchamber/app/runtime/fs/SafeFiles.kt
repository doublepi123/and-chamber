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
    var complete = true
    try {
        Files.walkFileTree(
            root,
            emptySet<FileVisitOption>(),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    // Directories from the guest rootfs can lack permissions (PRoot mount points).
                    // They sit inside the tree we are deleting, so make them listable first.
                    dir.toFile().apply {
                        setReadable(true, false)
                        setWritable(true, false)
                        setExecutable(true, false)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (!deleteEntry(file)) complete = false
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    complete = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null || !deleteEntry(dir)) complete = false
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (_: IOException) {
        complete = false
    }
    return complete && !root.existsNoFollow()
}

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

private fun deleteEntry(path: Path): Boolean =
    try {
        Files.deleteIfExists(path)
        !path.existsNoFollow()
    } catch (_: IOException) {
        false
    }

private fun Path.existsNoFollow(): Boolean = Files.exists(this, LinkOption.NOFOLLOW_LINKS)
