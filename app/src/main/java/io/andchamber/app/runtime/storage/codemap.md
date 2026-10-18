# app/src/main/java/io/andchamber/app/runtime/storage/

## Responsibility

The optional shared-storage feature. Projects and the OpenCode config can live in a public folder `/sdcard/AndChamber` that is visible to a file manager or USB. This package decides which folders are bound into the guest for each server start, prepares the shared folders, and writes the git configuration that FUSE storage needs. Secrets and node_modules stay private.

## Design

- `SharedStorageDecision` (pure object):
  - `status(enabled, permissionGranted, sharedRootUsable)`: Off unless enabled. NeedsPermission when enabled without access or a usable root. Active only when all hold.
  - `binds(status, ...)`: Active → `GuestBinds.shared`. Off and NeedsPermission → `GuestBinds.privateOnly`.
  - `prepareExceptions(privateDir, legacyConfigDir)`: creates the empty `service.json` and `service.json.tmp`, and the `node_modules` directory. Adopts a non-empty legacy `service.json` when the target is empty.
  - `restartRequired(started, current)`: true when a server is running with a different (status, bind list) pair.
- `GuestBinds` (data class): ordered `-b` specs. Fixed order: (1) private home over `/root`, (2) `projects` (shared or private), (3) `projects-private` (Active only), (4) shared opencode folder over `/root/.config/opencode` (Active only), (5) private exceptions nested on top of (4).
- `SharedStorageLayout` (object): guest and host constants, `planMigration` and `applyMigration`, `ensureMinimal`, `readme`, `mountPointsFor`. Migration is a pure plan with steps `Copy` or `Skip`. Rules: no symlinks, no secrets or node_modules, no overwrite, skip zero-byte placeholders.
- `SharedStorageControllerImpl`: owns the state flow and `startedBinds` (status plus spec list of the running server). `prepareForStart()` returns the binds for the next launch. `recordStarted` and `recordStopped` maintain `restartRequired`. `SharedStoragePrefs` and `SharedStoragePermission` are injected.
- `SharedStorageAndroid.kt`: `PrefsSharedStoragePrefs` (SharedPreferences, file `andchamber_shared_storage`, key `enabled`). `SharedStorageAccess` covers all-files access (API 30+ `isExternalStorageManager`, API 26–29 runtime permissions) and the settings intents.
- `GitConfigBlock` (pure): the managed block between `# >>> AndChamber (managed, do not edit) >>>` and `# <<< AndChamber <<<`. It includes `shared-storage.gitconfig` for `gitdir:/root/projects/` and adds `safe.directory = /root/projects/*`. `apply()` replaces the block in place, keeps the user's bytes and line endings, and is idempotent.
- `GitConfigWriter` (object): writes the shared-storage gitconfig (`core.createObject=rename`, `fileMode=false`, `symlinks=false`) and the managed `.gitconfig` in the home. Symlinked `.gitconfig` is replaced, never read through.

Invariants:
- Shared storage is a convenience. If preparing it fails, the start falls back to private storage (`NeedsPermission`).
- Migration runs once, guarded by `home-private/opencode/.migrated-to-shared`. After that the shared folder is authoritative.
- `service.json` (holds the OpenCode service password) and `node_modules` are never placed in shared storage.
- The config directory bind must come after its parent folder bind, and nested exception binds come last. Otherwise the directory bind would hide the private files.

## Flow

`SharedStorageControllerImpl.prepareForStart()` (every server start, before spawn):
1. `decideStatus()`: `prefs.enabled()`, `permission.granted()`, and `sharedRootUsable()` (mkdirs and `canWrite`).
2. If Active: `prepareShared()`:
   - mkdirs `hostRoot/projects`.
   - If the marker is absent, `planMigration(private .config/opencode, shared opencode)` → `applyMigration`, then write the marker.
   - `ensureMinimal(shared opencode)`: creates `opencode.jsonc` with the default `$schema` if neither `opencode.jsonc` nor `opencode.json` exists, plus `skills/`, `agents/`, `commands/`.
   - Write `README.txt` into `hostRoot`.
   - On failure, log, then fall back to `NeedsPermission`.
3. `bindsFor(status)` → `SharedStorageDecision.binds`.
4. `GitConfigWriter.apply(home)`. Runs in every mode and failures are logged only.
5. `ensureMountPoints(binds)`: mkdirs `home/projects`, plus `projects-private` and `.config/opencode` in Active.
6. If Active: `prepareExceptions(home-private/opencode, home/.config/opencode)`.
7. Return `GuestBinds`. The server calls `onStarted(binds)` → `recordStarted`, which stores `(status, specs)` and refreshes the state flow. `onStopped` → `recordStopped`.

## Integration

- Consumers:
  - `runtime/process/OpenChamberServer.kt`: `bindsForStart` (= `prepareForStart`), `onStarted`, `onStopped`.
  - `runtime/process/ProotCommands.kt`: `GuestBinds.privateOnly` and `serverCommand(binds)`.
  - `runtime/SharedStorageContract.kt`: the public `SharedStorageController` and `SharedStorageState`.
- Wiring: `di/AppModule.kt` constructs `SharedStorageControllerImpl` with `PrefsSharedStoragePrefs.open(context)` and the `hostRoot` (`Environment.getExternalStorageDirectory()/AndChamber`). `prepareForStart` is supplied as `bindsForStart`, and `recordStarted` and `recordStopped` are the callbacks.
- Git: `GitConfigWriter` writes into the persistent guest home (`runtime/home` = guest `/root`).
- External contracts:
  - Host layout: `/storage/emulated/0/AndChamber/{projects/, opencode/, README.txt}`.
  - Guest paths: `/root/projects`, `/root/projects-private`, `/root/.config/opencode`, and `service.json`, `service.json.tmp`, `node_modules` under the config dir.
  - Git: `/root/.config/andchamber/git/shared-storage.gitconfig` (included for `gitdir:/root/projects/`), and the managed block in `/root/.gitconfig`.
  - Android: `Environment.isExternalStorageManager()` and `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` on API 30+, runtime `READ/WRITE_EXTERNAL_STORAGE` on API 26–29.
