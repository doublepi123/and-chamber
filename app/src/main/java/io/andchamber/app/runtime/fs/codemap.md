# app/src/main/java/io/andchamber/app/runtime/fs/

## Responsibility

Filesystem tree operations for the runtime that never follow symbolic links. The guest rootfs and the user's home contain links, and `File.deleteRecursively()` and `File.walk()` follow directory links. A link pointing outside the runtime would then delete or measure foreign directories. This package is the only place that deletes, sizes, copies or replaces runtime trees.

## Design

Stateless top-level functions in `SafeFiles.kt`. Links are unlinked or measured as entries, never traversed.

- `deleteTree(File): Boolean`: no-follow recursive delete. Returns true when the path no longer exists, including when it never existed.
  - Written as explicit recursion, not `Files.walkFileTree`. The walker opens a directory before its callback runs, so a mode-000 directory would fail before it could be fixed.
  - Permission repair is bounded: each directory gets at most one retry (`retriedPermissions`).
  - Repairs add owner bits only: `makeOwnerAccessible` for unreadable or read-only directories, and `makeParentWritable` when the parent blocks unlinking.
  - A symlink at any depth, including the root, is unlinked and its target is left alone.
- `deleteTreeOrThrow(File)`: same, but throws if the tree remains.
- `treeSize(File): Long`: total bytes of regular files, not following links. Symlinks count as 0. Unreadable entries are skipped, so the result is a best-effort disk-usage figure.
- `copyTreeNoFollow(source, destination)`: copies files, directories and symlinks. Symlinks are recreated as links. Entries that fail are skipped. Used only for the non-essential home skeleton.
- `writeFileReplacing(target, content)`: atomic replace. Writes a temp file in the same directory, then `Files.move(REPLACE_EXISTING)`. A directory or symlink at the target is removed first, so a link's destination is never written.

Invariant: no operation follows a link. This also rules out loops.

## Flow

1. Environment swap and cleanup: `install/EnvironmentActivation.kt` and `install/OpenChamberInstaller.kt` call `deleteTree` or `deleteTreeOrThrow` on `staging`, `rollback` and the leftover staging tree.
2. Disk usage: `LocalRuntimeController.refreshInfo` → `treeSize(paths.root)`.
3. Home seeding: `OpenChamberInstaller.seedHome` → `copyTreeNoFollow` from the legacy `rootfs/root` or the Alpine skeleton, only when the home is empty.
4. Git config and state files: `storage/GitConfigWriter` → `writeFileReplacing` for both `shared-storage.gitconfig` and `.gitconfig`.
5. Removal: `RuntimeInstallerImpl.discardEnvironment/removeAll` use `deleteTreeOrThrow`. `ProotStaleServerCleaner` deletes only the stale pid file, with `File.delete`, not this package.

## Integration

- Consumers: `runtime/LocalRuntimeController.kt`, `runtime/install/*`, `runtime/storage/GitConfigWriter.kt`, `runtime/process/StaleServerCleanup.kt`.
- No Android APIs are used except `PosixFilePermission`. The `UnsupportedOperationException` fallback in `makeOwnerAccessible` and `makeParentWritable` uses `java.io.File` setters for non-POSIX filesystems.
- External contract: none. Operates on paths handed in by callers under `filesDir/runtime`.
