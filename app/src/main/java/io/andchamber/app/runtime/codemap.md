# app/src/main/java/io/andchamber/app/runtime/

## Responsibility

Lifecycle of the on-device Linux runtime (Alpine rootfs under PRoot running OpenChamber 2.4.0 with OpenCode 2.0.32): install, start, stop, repair, restart, uninstall and crash recovery. Also defines the contract types (state, failure phases, message keys, server targets, shared-storage state) that the UI and the foreground service depend on. Implementations live in the subfolders.

## Design

- `RuntimeContract.kt`: the UI-facing contract. `RuntimeState` is a sealed interface (Detecting, UnsupportedAbi, NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed). `FailurePhase` (Install, Start, Runtime) decides what Retry redoes. `MessageKey` names localised headlines; `UserFacingException` carries a key plus untranslated detail. Also `ServerTarget` (Local/Remote), `RemoteServerStore`, `WebSessionAuthenticator`, `RuntimeInfo`.
- `LocalRuntimeController.kt`: `DefaultLocalRuntimeController`. Every operation runs under one `operationLock` (a Mutex). `wantsRunning` records user intent and gates automatic recovery. The watchdog and recovery jobs take the same lock.
- `WorkCounter.kt`: counts queued and running work and publishes `busy = count > 0` under one lock. Decrement uses `invokeOnCompletion`, so a job cancelled before it starts still releases its count.
- `RestartPolicy.kt`: pure backoff budget (2 s, 5 s, 15 s, 30 s). The budget refills only after 120 s of continuous health. A manual start calls `reset()`. The clock is injected.
- `RuntimePaths.kt`: layout under `filesDir/runtime`. `environment/` (rootfs and `metadata.json`), `environment.staging`, `environment.rollback`, `home/` (guest /root, survives repair), `cache`, `logs`, `workspace`, `proot-tmp`, `command-suite`, `ui-password`.
- `RuntimeSecrets.kt`: a 32-byte random URL-safe password, persisted once in `runtime/ui-password`. It is passed to the server as `OPENCHAMBER_UI_PASSWORD`.
- `SharedStorageContract.kt`: `SharedStorageController`, `SharedStorageState`, and `SharedStorageStatus` (Off, NeedsPermission, Active).
- `UserFacingException.kt`: the typed failure carrying a `MessageKey`.

Invariants:
- Stop sets `cancelRequested` and cancels `restartJob` before queuing behind the lock. The restart job holds the lock while it waits, so queuing first would let it finish.
- Repair never deletes the live environment first. Install builds in staging, and the swap is the only mutation.
- A cancelled start lands in Stopped or NotInstalled, never in Starting.
- Only `arm64-v8a` runs on device (`supportedAbis`). x86_64 is blocked because Android's seccomp filter kills `SYS_fork`, which PRoot cannot translate.

## Flow

1. `initialize()` → `detectLocked()`: ABI check → `installer.recover()` → `installedRuntime()` → NotInstalled, or Stopped with `refreshInfo`.
2. A public action (`install/start/stop/repair/uninstall/restart`) → `ForegroundStarter.request(op)`. The service then calls `perform(op)`, which runs `launch(phase)` under `operationLock`.
3. Start: `startLocked(manual = true)` sets `wantsRunning`, resets the policy, calls `server.start(...)`, then publishes Running and schedules the watchdog.
4. Stop: `cancelRequested = true`, `wantsRunning = false`, cancel `restartJob`, then `stopLocked()`: `server.stop()` and `killOwned()`, then Stopped.
5. Repair: `stopLocked()` → `installLocked(thenStart = true)`. Uninstall: `stopLocked()` → `installer.removeAll()` → NotInstalled.
6. Restart: stop and start as one queued job, used to apply new storage binds.
7. Watchdog (every 5 s while Running): a dead child starts recovery at once. A live child with a failed `/health` read counts a miss, and 3 consecutive misses start recovery.
8. Recovery: `restartAfterCrash()` loops over the budget: delay, `server.stop()`, `startLocked(manual = false)`. When the budget is spent, `stopAfterExhaustion()` runs `server.stop()` and `killOwned()` under `NonCancellable` (a cancellation is rethrown after cleanup; other errors are logged), and only then does it publish Failed and clear `wantsRunning`. The service may finish only after this terminal state.

## Integration

- `di/AppModule.kt` builds `DefaultLocalRuntimeController`, `RuntimeInstallerImpl`, `ProotOpenChamberServer`, `ProotStaleServerCleaner` and `SharedStorageControllerImpl`.
- `di/AppModule.kt` wires `ForegroundStarter { op -> RuntimeService.request(context, op) }`, so the service is started only through this lambda. The service runs `perform(op)`, and reads `busy`. The UI observes `state`, `logTail` and `info`.
- `data/WebSessionAuthenticatorImpl.kt` implements `WebSessionAuthenticator`.
- Watchdog probes: `HealthProbe.Loopback` (GET `http://127.0.0.1:<port>/health`) and `OpenChamberServer.isAlive()`.
- Subfolders:
  - `fs/`: symlink-safe tree operations.
  - `install/`: build, verify, and atomic environment swap.
  - `manifest/`: reads `assets/runtime-manifest.json`.
  - `network/`: guest `resolv.conf`.
  - `process/`: PRoot launch, readiness, and process ownership.
  - `storage/`: shared-storage binds and git config.
