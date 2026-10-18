package io.andchamber.app.runtime.install

import io.andchamber.app.runtime.RuntimePaths
import io.andchamber.app.runtime.fs.deleteTree
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/*
 * Environment swap protocol. Directory renames are the only mutations of the environment, so each
 * step is atomic and every crash point has one recoverable state:
 *
 *   0. delete `rollback`           (only garbage can be there: see the guard below)
 *   1. rename `active` -> `rollback`   previous environment set aside   [no active; rollback = old]
 *   2. rename `staging` -> `active`    COMMIT POINT                     [active = new; rollback = old]
 *   3. delete `rollback`               pending cleanup, never a rollback
 *
 * Before step 2 the previous environment is restored (uncommitted rollback). From step 2 on, the
 * new environment is authoritative: a failure while removing the old one leaves it as pending
 * cleanup, retried by [recoverInterruptedRuntimeEnvironment], and never rolls back. The metadata
 * (`metadata.json`) lives inside the environment, so committing the directory commits it too.
 */
internal fun activateRuntimeEnvironment(
    active: File,
    staging: File,
    rollback: File,
    moveDirectory: (File, File) -> Unit = ::moveRuntimeDirectory,
    deleteTree: (File) -> Boolean = ::deleteTree,
) {
    require(staging.isDirectory) { "Runtime staging environment is missing" }
    requireSameParent(active, staging, rollback)
    // A rollback without an active environment is the only copy of the previous install. Refuse to
    // touch it; recovery must restore it first.
    require(!(rollback.exists() && !active.exists())) {
        "An interrupted runtime swap must be recovered before activation"
    }
    // Here `rollback` can only be pending cleanup of an already-committed environment (see the guard).
    if (rollback.exists()) check(deleteTree(rollback)) { "Unable to clear the previous runtime rollback" }
    normalizeRuntimeSymlinks(staging)

    var previousMoved = false
    try {
        if (active.exists()) {
            moveDirectory(active, rollback)
            previousMoved = true
        }
        moveDirectory(staging, active) // commit point
    } catch (error: Throwable) {
        if (previousMoved && !active.exists()) {
            runCatching { moveDirectory(rollback, active) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
        }
        throw error
    }
    // Committed: the new environment is authoritative. Cleanup failures must not undo the commit.
    deleteTree(rollback)
}

/**
 * Makes PRoot's hard-link emulation survive an atomic environment directory move.
 *
 * `--link2symlink` represents hard links as absolute host paths. Packages installed while the
 * rootfs is staged therefore retain `environment.staging` in their link targets after activation.
 * Convert those links to relative paths, and also repair environments created by older releases.
 */
internal fun normalizeRuntimeSymlinks(environment: File) {
    if (!environment.isDirectory) return
    val marker = File(environment, ".internal-symlinks-relative")
    if (marker.isFile) return

    val current = environment.toPath().toAbsolutePath().normalize()
    val possibleRoots =
        listOf(
            current,
            current.resolveSibling("environment.staging"),
            current.resolveSibling("environment.rollback"),
        )
    // A guest rootfs carries directories the app's own uid cannot open - PRoot's bind-mount points
    // come out of the tarball with no permissions at all. Walking with a visitor lets those be
    // skipped; Files.walk would abort the whole traversal with an UncheckedIOException instead.
    Files.walkFileTree(
        current,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                if (attributes.isSymbolicLink) relativizeSymlink(file, current, possibleRoots)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                error: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    marker.writeText("normalized\n")
}

/** Rewrites [link] to point inside [current] when it still targets an older environment root. */
private fun relativizeSymlink(
    link: Path,
    current: Path,
    possibleRoots: List<Path>,
) {
    val target = Files.readSymbolicLink(link)
    if (!target.isAbsolute) return
    val normalizedTarget = target.normalize()
    val oldRoot = possibleRoots.firstOrNull(normalizedTarget::startsWith) ?: return
    val targetInCurrent = current.resolve(oldRoot.relativize(normalizedTarget))
    replaceSymlink(link, link.parent.relativize(targetInCurrent))
}

private fun replaceSymlink(
    link: Path,
    target: Path,
) {
    Files.delete(link)
    Files.createSymbolicLink(link, target)
}

/**
 * Settles a swap that a process death interrupted. Cheap when nothing is pending: one stat.
 *
 * - `rollback` present, `active` absent: the swap stopped before the commit. The previous
 *   environment is moved back. Returns true.
 * - `rollback` and `active` both present: the commit happened; the old environment is pending
 *   cleanup and is removed. Returns false. A cleanup failure is not an error; the next call retries.
 */
internal fun recoverInterruptedRuntimeEnvironment(
    active: File,
    rollback: File,
    moveDirectory: (File, File) -> Unit = ::moveRuntimeDirectory,
    deleteTree: (File) -> Boolean = ::deleteTree,
): Boolean {
    if (!rollback.exists()) return false
    requireSameParent(active, rollback)
    if (active.exists()) {
        deleteTree(rollback)
        return false
    }
    moveDirectory(rollback, active)
    require(File(active, RuntimePaths.METADATA_FILE_NAME).isFile) {
        "Restored runtime environment has no metadata"
    }
    return true
}

private fun moveRuntimeDirectory(
    source: File,
    destination: File,
) {
    require(source.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
        "Atomic runtime directory move requires the same parent"
    }
    require(!destination.exists()) { "Runtime destination already exists: ${destination.name}" }
    require(source.renameTo(destination)) {
        "Unable to move ${source.name} to ${destination.name}"
    }
}

private fun requireSameParent(vararg files: File) {
    val parents = files.mapNotNull { it.parentFile?.canonicalFile }.distinct()
    require(parents.size == 1) { "Runtime activation directories must share one parent" }
}
