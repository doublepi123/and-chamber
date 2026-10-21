# app/src/main/java/io/andchamber/app/runtime/install/

## Responsibility

Builds the Alpine + Node + OpenChamber environment in staging, verifies it, and activates it by an atomic directory swap. Also downloads and verifies the rootfs tarball, extracts it without a guest package manager, and recovers an environment swap that a process death interrupted.

## Design

- `RuntimeInstaller` (interface): `recover()`, `installedRuntime()`, `install(report, versions?)`, `update(report, versions)`, `discardEnvironment()`, `removeAll()`. `RuntimeInstallerImpl` implements it over the on-disk layout and is not thread-safe, since the controller serialises calls.
- `PackageVersions` (npm versions of `@openchamber/web` and `@opencode/cli`) and `PackageVersionSource` (fun interface: `latest(pinned)`).
- `NpmPackageVersionSource`: `PackageVersionSource` over the npm registry's abbreviated metadata (OkHttp, 15 s call timeout, `registryBaseUrl` injectable). Picks the highest stable version with the pin's major. Excludes prereleases (`0.0.0-dev-*`) and OpenCode below `MIN_OPENCODE_VERSION`. Never returns below the pin.
- `OpenChamberInstaller`: the pipeline. Its nested `GuestRunner` runs `/bin/sh -lc` inside PRoot with `ProotCommands`, streams output to `InstallReporter.log` and `logs/install.log`, and enforces a timeout. On timeout or interrupt, `awaitExit` kills the PRoot tree and waits for the launcher before returning. The live DNS monitor is not part of install: `configureRootfs` writes resolv.conf once, from the system DNS at that moment.
  - `install` and `update` share `finishEnvironment`: npm install of the versions, node-pty rebuild, PTY smoke, verify, metadata, then the swap. Metadata records the version read back from the installed `package.json`, not the requested one.
  - `update` requires an active environment with metadata. It checks free space (`treeSize` + 512 MiB, else `UpdateNotEnoughSpace`), copies the environment with `copyTreeStrict`, clears the copied marker, normalizes the links into staging, removes the old global packages (`removeGlobalPackages`), refreshes resolv.conf, then runs the shared tail. Failure deletes staging only.
- `GuestOutputPump` (internal): reads a guest command's combined output on a daemon thread and passes each line to `onLine`. Two entry points, both bounded by a caller-supplied deadline:
  - `drain(deadline)`: normal exit. Waits for end-of-stream so trailing lines are delivered. An unfinished reader returns `ReaderStillRunning`.
  - `abort(deadline)`: timeout, interrupt or error. Sets `closing` (no new line starts delivery), closes the stream on a helper thread, and joins both within the deadline. An interrupt is restored and returned.
  - Failure rules: an `IOException` from the read is expected only after `abort` or when the child is gone. An error from `onLine` is always a failure, including an `IOException`.
  - Limits: the deadline bounds how long the caller waits. It does not stop work already running. An `onLine` call in progress can finish after `abort` returns. A reader or a blocked `close()` that outlives the deadline is abandoned as a daemon thread, and it is not guaranteed to exit. The caller reads lines through a thread-safe snapshot only.
- `GuestOutputPump.awaitAndTearDown(awaitCommand, teardownBudgetNanos, timeoutFailure, readerFailure)`: the production orchestration `GuestRunner.run` calls. The teardown deadline is created only after `awaitCommand` returns, so a long command never spends the teardown budget. `drain` and a later `abort` share that one deadline, and it is never refilled. The catch and timeout paths create a fresh deadline when they enter `abort`. The primary error keeps its identity, a teardown error is attached as suppressed (never the primary itself), and an interrupt is restored before the throw.
- `GuestRunner.run`: uses `awaitAndTearDown`. The pump's clock is injectable, so tests drive the same code path with a fake clock.
- `GuestRunner.mustSucceed` reads its lines through a synchronised list and takes a snapshot before it classifies the output.
- `EnvironmentActivation.kt`: swap protocol (see Flow). Also `normalizeRuntimeSymlinks(environment, writeMarker = true)`, which keeps PRoot link2symlink links working at the final path. PRoot resolves its `.l2s.` intermediates only through **absolute** targets, and a relative target makes Node load `pty.node` as JavaScript. The rules:
  - An absolute target under `environment`, `environment.staging` or `environment.rollback` (or the `/data/data` ↔ `/data/user/N` spellings) is retargeted to the same relative location under the **PRoot host spelling** of this environment (`prootHostSpelling`). It stays absolute. `/data/user/0/<pkg>` maps to `/data/data/<pkg>`, which is what PRoot records and what the app process resolves. The server fails with the `/data/user/0` spelling, so this matters. Other users (`/data/user/N`, N≠0) and non-`/data` paths are unchanged.
  - A relative target named `.l2s.*` (older v2 output) is made absolute under the link's directory, in the PRoot spelling, if it stays inside the environment.
  - Other links are untouched: npm's relative bin links, and guest-absolute targets such as `/usr/lib/...`.
  - Marker `.internal-symlinks-relative` = `absolute-l2s-v4`, written only at the end of a full pass. Older markers (`normalized-v2`, `absolute-l2s-v3`) are repaired. With `writeMarker = false` (staging) no marker is written. `clearSymlinkMarker` removes it.
- `VerifiedRuntimeDownloader`: validates the download URL and parameters, then uses OkHttp with a SHA-256 pin and optional expected size. After recovering `.backup`, it verifies the destination hash (and optional size) before networking; a valid cached destination succeeds directly and stale `.partial`/`.backup` files are cleaned up. Otherwise, a complete partial is verified before any re-download. It resumes through `.partial` with Range, restarts from zero on a 200 response, and treats 416 by discarding the partial. Uses `.backup` during destination replacement. Requires HTTPS, except loopback hosts.
- `RuntimeArchive`: `verifySha256`, `extractTarGz` and `extractDebianPackage`. Rejects entries that escape the destination. Hard links fall back to byte copies. Symlinks are resolved after all entries are written, deepest first, to handle merged-/usr layouts.
- `RuntimeMetadata` (serialized to `environment/metadata.json`) and `InstalledRuntime`. `InstallReporter` receives `step` and `log`.
- `NetworkFailure`: recognises apk/npm output markers such as `EAI_AGAIN`, `ETIMEDOUT`, `bad address`, and maps them to `MessageKey.MirrorsUnreachable`.

Invariants:
- The active environment is untouched until the commit rename. Metadata is written inside staging, so the rename commits it too.
- `installedRuntime()` returns null unless `metadata.json` parses, the `rootfs/` directory exists, and `abi` matches.
- `node-pty` is rebuilt from source for musl (the glibc prebuild segfaults). A PTY smoke test must pass before verify.

## Flow

Install (`OpenChamberInstaller.install`):
1. `deleteTreeOrThrow(staging)`, then `mkdirs` for cache, logs, proot-tmp and home.
2. DownloadRootfs: `VerifiedRuntimeDownloader` fetches the Alpine tarball from `architecture.alpineUrl` to `cache/alpine-<ver>-<abi>.tar.gz`, verified by `alpineSha256`.
3. ExtractRootfs: `extractTarGz` into `staging/rootfs`, then `configureRootfs` (resolv.conf from `SystemDnsProvider`, `/etc/hosts`, libapk SONAME symlink) and `seedHome`.
4. InstallPackages: `apk add nodejs npm bash git curl ripgrep openssh-client ca-certificates libstdc++ python3 make g++ linux-headers` (`set -e`, 20 min).
5. Write `/opt/andchamber/android-shim.cjs` (before npm).
6. InstallOpenChamber: `npm i -g --no-fund --no-audit @openchamber/web@2.4.0 @opencode/cli@2.0.32` (30 min).
7. BuildNativeModules: `node-gyp rebuild` in `node_modules/node-pty`, then the PTY smoke script (2 min).
8. Verify: `node --version`, `opencode --version` (must be at least 2.0.20), and `dist/index.html` must exist.
9. Write `metadata.json` in staging. Then `activateRuntimeEnvironment(active, staging, rollback)`.
10. On any throwable: `deleteTree(staging)`, then rethrow.

Update (`OpenChamberInstaller.update`, no Alpine download):
1. `deleteTreeOrThrow(staging)`. Check free space (`treeSize(environment)` + 512 MiB), else `UpdateNotEnoughSpace`.
2. CopyEnvironment: `copyTreeStrict(environment, staging)`. Symlinks are kept as-is, modes are kept, and any failing entry fails the copy. Then clear the copied marker and write resolv.conf into the copy.
3. `removeGlobalPackages(rootfs)`: host-side, no-follow delete of `usr/local/lib/node_modules/@openchamber/web`, `usr/local/lib/node_modules/@opencode/cli` and the bin links `usr/local/bin/{openchamber,opencode,opencode2}`. Missing entries are fine. Reason: npm's in-place upgrade over PRoot's faked hard links (`.l2s.*` chains) leaves `opencode` broken ("Operation not permitted" / "not found"), so verify fails. A clean reinstall works (verified on device).
4. `finishEnvironment` with the target versions (npm, node-pty rebuild, smoke, verify, metadata, swap). `runtimeVersion`/`alpineVersion` come from the active metadata.
5. Any throwable: `deleteTree(staging)`, then rethrow. The active environment is untouched.

Swap (`activateRuntimeEnvironment`):
1. Delete leftover `rollback` (only committed garbage can be there).
2. Clear the staging marker. No normalization runs on staging, because absolute targets under staging do not survive the rename. The commit happens in step 4.
3. Rename `environment` → `rollback` (uncommitted; on failure, move back).
4. Rename `staging` → `environment`. COMMIT POINT.
5. `deleteTree(rollback)`. A failure here does not undo the commit.
6. `normalizeRuntimeSymlinks(environment)` on the committed environment. A failure is logged and does not undo the commit. Without a marker, `installedRuntime()` runs it again.

Recovery (`recoverInterruptedRuntimeEnvironment`, at every detection):
- rollback present, environment absent: move rollback back (pre-commit crash).
- both present: delete rollback (post-commit crash).

## Integration

- Controller: `runtime/LocalRuntimeController.kt` calls `recover`, `installedRuntime`, `install`, `removeAll`.
- Wiring: `di/AppModule.kt` builds `RuntimeInstallerImpl` with `VerifiedRuntimeDownloader(OkHttpClient)`, the manifest, and the shim asset.
- Depends on: `runtime/manifest/RuntimeManifest` (pins), `runtime/process/ProotCommands` and `EmbeddedCommandSuite` (launch), `runtime/process/ProcessTree` (kill on timeout), `runtime/network/ResolvConf`, `runtime/fs/SafeFiles`.
- External contracts:
  - Alpine tarball: HTTPS URL and SHA-256 from `runtime-manifest.json`.
  - npm pins: `@openchamber/web@2.4.0`, `@opencode/cli@2.0.32`, and `opencode --version` at least 2.0.20.
  - Guest paths: `/usr/local/lib/node_modules/@openchamber/web` (`dist/index.html`), `/opt/andchamber/android-shim.cjs`, `/opt/andchamber/pty-smoke.cjs`.
  - `metadata.json` fields: `runtimeVersion`, `alpineVersion`, `abi`, `port`, `openChamberVersion`, `openCodeVersion`, `nodeVersion`, `installedAt`.
