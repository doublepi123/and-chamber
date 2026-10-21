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
    // Absolute targets under staging do not survive the rename, so staging is not normalized here. The
    // marker must be absent before the commit so the post-commit pass runs on the new environment.
    clearSymlinkMarker(staging)

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
    // Committed: the new environment is authoritative. Cleanup and normalization failures must not undo
    // the commit. A failed normalization leaves no marker, so installedRuntime() runs it again.
    deleteTree(rollback)
    runCatching { normalizeRuntimeSymlinks(active) }
}

/**
 * Makes PRoot's hard-link emulation (`--link2symlink`) work inside the environment's final path.
 *
 * PRoot writes a faked hard link as `file -> <dir>/.l2s.NAME0001` (intermediate), and the intermediate
 * as `-> .l2s.NAME0001.0002` (backing file). PRoot resolves the intermediate with plain `readlink(2)`
 * on the path as written, which only works when the recorded target is an **absolute** host path. A
 * relative target fails inside PRoot and the guest sees an ordinary symlink, whose realpath then ends in
 * `.0002`, so Node loads the native module as JavaScript.
 *
 * So, for every symlink in [environment]:
 * - an absolute target under a sibling root (`environment`, `environment.staging`, `environment.rollback`,
 *   including the `/data/data` <-> `/data/user/N` spellings) is retargeted to the same relative location
 *   under [environment]'s **PRoot host spelling** ([prootHostSpelling]). The target stays absolute. The
 *   spelling matters: inside the app process `/data/user/0` is a symlink to `/data/data`, and the links
 *   that PRoot itself records use `/data/data/<pkg>`. A link written with `/data/user/0/` fails to resolve
 *   for the real server, so user 0 is canonicalized to `/data/data/`. A link already in that spelling is
 *   kept as-is.
 * - a relative target whose last component starts with `.l2s.` (written by older releases) is made
 *   absolute under the same base. It is kept only when it stays inside [environment].
 * - every other link is left alone: npm's relative bin links stay relative, and guest-absolute targets
 *   such as `/usr/lib/...` are not host paths.
 *
 * Versioned by the marker [SYMLINK_MARKER_NAME] ([NORMALIZATION_VERSION]). The marker is written only at
 * the end of a full pass. With [writeMarker] false (staging), no marker is written, so the post-commit
 * pass still runs.
 */
internal fun normalizeRuntimeSymlinks(
    environment: File,
    writeMarker: Boolean = true,
) {
    if (!environment.isDirectory) return
    val marker = File(environment, SYMLINK_MARKER_NAME)
    if (marker.isFile && marker.readText().trim() == NORMALIZATION_VERSION) return

    // The base is the PRoot spelling of the final path; the roots list still matches every spelling.
    val current = prootHostSpelling(environment.toPath().toAbsolutePath().normalize())
    val possibleRoots = siblingRoots(current).flatMap(::appDataAliases).distinct()
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
                if (attributes.isSymbolicLink) retargetSymlink(file, current, possibleRoots)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                error: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    if (writeMarker) marker.writeText("$NORMALIZATION_VERSION\n")
}

/**
 * Versioned: v2 wrote relative links, which PRoot cannot resolve. v3 wrote absolute links under the
 * final path, but in the `/data/user/0` spelling the server cannot resolve. v4 writes the PRoot spelling.
 */
private const val NORMALIZATION_VERSION = "absolute-l2s-v4"

/** Marker file written after [normalizeRuntimeSymlinks] succeeds. Its name is part of the on-disk format. */
internal const val SYMLINK_MARKER_NAME = ".internal-symlinks-relative"

/** Forces the next normalization of [environment] to run again (the copy an update makes, for example). */
internal fun clearSymlinkMarker(environment: File) {
    File(environment, SYMLINK_MARKER_NAME).delete()
}

/**
 * Environment roots that absolute link targets may still name. A copy made for an update is staged
 * while the active `environment` holds the links PRoot wrote, so that root counts as well.
 */
private fun siblingRoots(current: Path): List<Path> =
    listOf(
        current,
        current.resolveSibling("environment"),
        current.resolveSibling("environment.staging"),
        current.resolveSibling("environment.rollback"),
    )

/**
 * The spelling PRoot records for an app data path, and the one that links must use to resolve inside the
 * app process. `/data/user/0/<pkg>` is a symlink to `/data/data/<pkg>` in the app's mount namespace, and
 * PRoot canonicalizes host paths to `/data/data/`. Only user 0 has that alias, so other users
 * (`/data/user/10/...`) and paths outside `/data` are returned unchanged.
 */
internal fun prootHostSpelling(path: Path): Path {
    val text = path.toString()
    if (!text.startsWith(USER_ZERO_PREFIX)) return path
    return java.nio.file.Paths.get(DATA_DATA_PREFIX + text.removePrefix(USER_ZERO_PREFIX))
}

private const val USER_ZERO_PREFIX = "/data/user/0/"
private const val DATA_DATA_PREFIX = "/data/data/"

/**
 * Android reaches the same app data directory through several spellings: `filesDir` reports
 * `/data/user/<n>/<pkg>`, while PRoot records link2symlink targets under `/data/data/<pkg>` (seen on
 * device). Both must count as the same root, or links survive the swap still pointing at staging.
 * The list keeps both spellings of the path itself, so links written in either spelling are matched.
 */
internal fun appDataAliases(path: Path): List<Path> {
    val text = path.toString()
    val userPrefix = Regex("""^/data/user/\d+/""")
    val alias: Path? =
        when {
            userPrefix.containsMatchIn(text) -> java.nio.file.Paths.get(text.replaceFirst(userPrefix, "/data/data/"))
            text.startsWith("/data/data/") -> java.nio.file.Paths.get(text.replaceFirst("/data/data/", "/data/user/0/"))
            else -> null
        }
    return listOfNotNull(path, alias)
}

/**
 * Applies the retarget rules of [normalizeRuntimeSymlinks] to one link. Links it does not own are not
 * touched. A failure on one link is skipped so the rest of the pass still runs.
 */
internal fun retargetSymlink(
    link: Path,
    current: Path,
    possibleRoots: List<Path>,
): Boolean {
    val target = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return false
    val newTarget: Path =
        if (target.isAbsolute) {
            // Absolute link under an older root or the other spelling: keep the location relative to the
            // root, under the PRoot spelling of the final path (`current`).
            val normalizedTarget = target.normalize()
            val oldRoot = possibleRoots.firstOrNull(normalizedTarget::startsWith) ?: return false
            current.resolve(oldRoot.relativize(normalizedTarget))
        } else {
            // Relative `.l2s.` link (older v2 normalization): resolve it where it sits inside the environment.
            if (!isProotBackingName(target)) return false
            val resolved = link.parent.resolve(target).normalize()
            if (!resolved.startsWith(current)) return false
            prootHostSpelling(resolved)
        }
    // Compare with the link text itself: a link already in the right spelling is left alone.
    if (newTarget == target) return false
    return runCatching {
        Files.delete(link)
        Files.createSymbolicLink(link, newTarget)
        true
    }.getOrDefault(false)
}

/** True when a relative link target names a PRoot link2symlink intermediate or backing file (`.l2s.*`). */
private fun isProotBackingName(target: Path): Boolean = target.fileName?.toString()?.startsWith(".l2s.") == true

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
