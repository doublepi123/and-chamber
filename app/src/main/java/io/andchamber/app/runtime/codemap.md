# app/src/main/java/io/andchamber/app/runtime/

## Responsibility

Lifecycle of the on-device Linux runtime (Alpine rootfs under PRoot running OpenChamber 2.4.0 with OpenCode 2.0.32): install, start, stop, repair, restart, uninstall and crash recovery. Also defines the contract types (state, failure phases, message keys, server targets, shared-storage state) that the UI and the foreground service depend on. Implementations live in the subfolders.

## Design

- `RuntimeContract.kt`: the UI-facing contract. `RuntimeState` is a sealed interface (Detecting, UnsupportedAbi, NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed). `FailurePhase` (Install, Start, Runtime) decides what Retry redoes. `MessageKey` names localised headlines; `UserFacingException` carries a key plus untranslated detail. Also `ServerTarget` (Local/Remote), `RemoteServerStore`, `RuntimeInfo`.
- Web session capability (`RuntimeContract.kt`, web session section):
  - `WebSessionIdentity(targetId, origin)`: one server target at one canonical origin. A WebView profile is bound to exactly one identity.
  - `WebSessionCookies`: the cookie capability of that one bound profile. It exposes `identity`, `cookieHeader(url)` and `storeCookies(url, headers)`. Any URL outside `identity.origin` gets no cookie back, and nothing is written for it.
  - `WebSessionAuthenticator.prepare(target, cookies: WebSessionCookies): WebSessionResult`. There is no global cookie manager and no `prepare(target)` overload. The caller must pass the capability of the target's own profile.
  - `WebSessionResult`: `Ready(url)`, `NeedsLogin(url)`, `NeedsGatewayLogin(url)` for interactive remote Access, or `Unreachable(reason)`.
  - Implementations live in `data/` (`WebSessionAuthenticatorImpl`, `WebSessionProfiles`). This package only defines the contract.
- `LocalRuntimeController.kt`: `DefaultLocalRuntimeController`. Every operation runs under one `operationLock` (a Mutex). `wantsRunning` records user intent and gates automatic recovery. The watchdog and recovery jobs take the same lock.
- Updates (OpenChamber and OpenCode npm packages, no Alpine re-download):
  - `updates: StateFlow<RuntimeUpdateState>` holds `checking`, `available` (`RuntimeUpdate`, one `VersionChange` per package, `changed` says whether it moves), `lastCheckedAtMs`, `checkFailed`, `lastFailure`.
  - Available = per package the higher of the registry (`PackageVersionSource`, `NpmPackageVersionSource`) and the manifest pins, compared with the installed metadata. It never offers a downgrade. A failed registry check still offers the bundled pins, with `checkFailed` set.
  - `checkForUpdates()` runs outside `operationLock` so the network never holds up the runtime. It is ignored while a check runs. Automatic checks run after detect and after install, throttled by `updateCheckIntervalMillis` (6 h). Manual checks ignore the throttle.
  - `update()` → `RuntimeOperation.Update` → `updateLocked()`. It stops a running server, calls `installer.update`, and publishes `Installing(updating = true)` while it runs. Success recomputes `available`. Failure does not publish `Failed`: it sets `lastFailure` and keeps the old environment. The server restarts if it was running, otherwise the state rests at Stopped.
  - Repair installs `max(installed, pinned)` per package, so a repair never downgrades OpenCode. A fresh install uses the pins.
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
- `supportedAbis` is `arm64-v8a` and `x86_64`. Other ABIs report UnsupportedAbi. x86_64 relies on PRoot 5.1.107.98: Android's x86_64 app seccomp policy rejects the raw `SYS_fork` (57), and PRoot rewrites it to `clone(SIGCHLD)` with the child's registers restored. The gate is `docs/testing-x86-runtime.md` (emulator probe, not yet a physical device).

## Flow

1. `initialize()` → `detectLocked()`: ABI check → `installer.recover()` → `installedRuntime()` → NotInstalled, or Stopped with `refreshInfo`.
2. A public action (`install/start/stop/repair/uninstall/restart`) → `ForegroundStarter.request(op)`. The service then calls `perform(op)`, which runs `launch(phase)` under `operationLock`.
3. Start: `startLocked(manual = true)` sets `wantsRunning`, resets the policy, calls `server.start(...)`, then publishes Running and schedules the watchdog.
4. Stop: `cancelRequested = true`, `wantsRunning = false`, cancel `restartJob`, then `stopLocked()`: `server.stop()` and `killOwned()`, then Stopped.
5. Repair: `stopLocked()` → `installLocked(thenStart = true, repair = true)`. Uninstall: `stopLocked()` → `installer.removeAll()` → NotInstalled, and `updates` resets.
5a. Update: `updateLocked()`, described under Design. Only the version target and the installed environment are used. The active environment is never replaced unless the copy and npm steps have all passed.
6. Restart: stop and start as one queued job, used to apply new storage binds.
7. Watchdog (every 5 s while Running): a dead child starts recovery at once. A live child with a failed `/health` read counts a miss, and 3 consecutive misses start recovery.
8. Recovery: `restartAfterCrash()` loops over the budget: delay, `server.stop()`, `startLocked(manual = false)`. Budget exhaustion is ordered: `stopAfterExhaustion()` runs `server.stop()` and then `killOwned()`, both under `NonCancellable`. Each step runs in its own try, so a throwing `stop()` does not skip `killOwned()`. Errors are logged. Only after both steps does the controller clear `wantsRunning` and publish the terminal Failed. Because the service decides from `busy` and `state`, the foreground service may finish only after that terminal state with no pending work. A cancellation that arrives during cleanup is rethrown after both steps have run. Owned processes are never left behind when the child is still alive at exhaustion.
9. Teardown ordering is covered by `RestartBudgetCleanupTest`, including a user Stop issued from inside the final `stop()`. That Stop runs after the cleanup, and the controller ends Stopped with busy false.

## Integration

- `di/AppModule.kt` builds `DefaultLocalRuntimeController` (with `NpmPackageVersionSource(OkHttpClient)` and the manifest pins), `RuntimeInstallerImpl`, `ProotOpenChamberServer`, `ProotStaleServerCleaner` and `SharedStorageControllerImpl`.
- `di/AppModule.kt` wires `ForegroundStarter { op -> RuntimeService.request(context, op) }`, so the service is started only through this lambda. The service runs `perform(op)`, and reads `busy`. The UI observes `state`, `logTail` and `info`.
- `data/WebSessionAuthenticatorImpl.kt` implements `WebSessionAuthenticator.prepare(target, cookies)`. It receives the per-target `WebSessionCookies` from `data/WebSessionProfiles.kt` and never touches a global cookie store. The UI passes that capability from `ui/web/WebScreen.kt`.
- Live DNS: `ProotOpenChamberServer` receives `DnsRefresherMonitor(DnsRefresher(AndroidSystemDnsProvider, AndroidNetworkEvents, reporter))` from `di/AppModule.kt`. The monitor starts after readiness and stops before teardown. See `network/codemap.md`.
- Watchdog probes: `HealthProbe.Loopback` (GET `http://127.0.0.1:<port>/health`) and `OpenChamberServer.isAlive()`.
- Subfolders:
  - `fs/`: symlink-safe tree operations.
  - `install/`: build, verify, and atomic environment swap.
  - `manifest/`: reads `assets/runtime-manifest.json`.
  - `network/`: guest `resolv.conf`.
  - `process/`: PRoot launch, readiness, and process ownership.
  - `storage/`: shared-storage binds and git config.
