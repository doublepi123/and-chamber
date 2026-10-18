# app/src/main/java/io/andchamber/app/runtime/process/

## Responsibility

Runs, probes and stops the OpenChamber server inside PRoot. Covers the PRoot command lines and guest environment, readiness and identity checks, and process ownership. A process is only ever signalled when it can be proven to belong to this app's runtime.

## Design

- `OpenChamberServer` (interface): `start(runtime, uiPassword, onLog, isCancelled): Long` (launch id), `stop()`, `isAlive()`, `readyLaunchId(): Long?`.
- `ProotOpenChamberServer`: the implementation. It has a single `lock` guarding `process`, `launchId`, `readyId`, `listenSignal` and `recentOutput`. Launch ids are monotonic. A launch is trusted only while `readyId` matches.
- `Readiness` (pure object): trust order is (1) the child printed `OpenChamber server listening on 127.0.0.1:<port>`, (2) `/health` says `status == ok` and `isOpenCodeReady`, (3) a concrete `lastOpenCodeError` with `openCodeRunning == false` fails the start. `identityStable` requires one `serverId` across polls.
- `HealthProbe` (`fun interface`): `read(port): HealthReport?`. `Loopback` does GET `http://127.0.0.1:<port>/health` (connect 2 s, read 5 s). `parse` is pure. `isPortFree` tests for a refused connection.
- `ProcessScanner` and `OwnedProcess` (`ProcessTree.kt`): the only place that scans `/proc`. Ownership needs all of: real uid equals the app uid, `argv[0]` equals the PRoot launcher path, and argv contains the rootfs path. Kills recheck the start time (`/proc/<pid>/stat` field 22) right before signalling, so a recycled pid is never signalled. `treePostOrder` lists children first. Only nodes that pass the uid check are included.
- `terminateProcessTree(process, scanner, roots, signal)`: `destroy()`, wait 750 ms, kill owned tree, then `destroyForcibly()` if still alive.
- `StaleServerCleanup.kt`: `ProotStaleServerCleaner` kills owned runtime processes, deletes `run/openchamber-<port>.pid`, then waits up to 5 s for the port to be free. `killOwnedRuntime` is shared with Stop, Repair and Uninstall.
- `ProotCommands` (pure builders): `prootArguments`, `guestShellCommand` (installer, `/bin/sh -lc`), `serverCommand` (server, `-w /root`), `serverEnvironment`, `installEnvironment`.
- `EmbeddedCommandSuite`: resolves the PRoot binaries from `nativeLibraryDirectory`. Names: `libandchamber_android_proot.so`, `libandchamber_android_proot_loader.so`, `libandchamber_android_proot_loader32.so`. Also creates `command-suite/{home,tmp}`.
- `truncateLogFile`: keeps the newest half of an oversized log, cut at a line boundary.

Invariants:
- Port ownership is decided before spawning. If the port stays held by a foreign listener, the launch refuses (`PortInUseException`), and the password is never sent to it.
- The password only reaches the child env, never a log line.
- Lines starting with `[OpenChamberDesktopNotify] ` go to `notificationSink` and are kept out of the log, the diagnostic tail and the readiness signal.
- The output pump clears `readyId` as soon as the child's output ends.

## Flow

Start (`ProotOpenChamberServer.start`):
1. `stop()` first, so no previous child remains.
2. `prootPath` from the suite. `cleanBeforeStart(port, run/, prootPath)` → `killOwnedRuntime` → delete stale pid file → `awaitPortFree(port, 5 s)`. Failure throws `PortInUseException`.
3. `refreshDns(runtime)`, `suite()`, truncate `openchamber.log` to 1 MB, mkdir logs, workspace and proot-tmp.
4. `bindsForStart()` → `GuestBinds`. `ProotCommands.serverCommand(proot, rootfs, binds, [], port)`.
5. `serverEnvironment(suite.env, prootTmp, uiPassword)`. `spawn(command, env, paths.root)`.
6. Store `process`, reset `recentOutput`, set `readyId = null`, `listenSignal = new`, `++launchId`.
7. `pumpOutput` thread: reads lines from the child's stdout. Writes `openchamber.log`, keeps the last 200 lines, and completes `listenSignal` on the listen marker.
8. `waitUntilReady` (deadline 90 s, poll 500 ms): cancel check, child death check (`EADDRINUSE` maps to PortInUse), wait for the listen signal, then poll `/health` with `identityStable` and `Readiness.verdict`.
9. On failure: `stop()` and rethrow. On success: if still the current child, `readyId = id`, then `onStarted(binds)`.

Stop (`stop`):
1. Under the lock: `readyId = null`, detach `process`.
2. `ownedRoots(prootPath, rootfs)` → `terminateProcessTree`.
3. `onStopped()` (clears shared-storage `startedBinds`).

Command (argv, from `ProotCommands`):
`proot --kill-on-exit --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys -b /system -b <binds...> -w /root /usr/bin/node /usr/local/lib/node_modules/@openchamber/web/bin/cli.js serve --foreground --port <port>`

Environment (cleared, then set): `HOME=/root`, `PATH` (guest), `LANG=C.UTF-8`, XDG_* under `/root`, `PROOT_TMP_DIR`, `PROOT_LOADER`, `PROOT_LOADER_32`, `LD_LIBRARY_PATH`, `OPENCHAMBER_HOST=127.0.0.1`, `OPENCHAMBER_UI_PASSWORD`, `OPENCHAMBER_RELAY_HOST=off`, `OPENCHAMBER_DESKTOP_NOTIFY=true`, `OPENCODE_DISABLE_AUTOUPDATE=true`, `OPENCHAMBER_OPENCODE_BIN=/usr/local/bin/opencode`, `OPENCHAMBER_OPENCODE_HEALTH_TIMEOUT_MS=15000`, `NODE_OPTIONS=--require /opt/andchamber/android-shim.cjs`.

## Integration

- Consumers: `runtime/LocalRuntimeController.kt` (`start`, `stop`, `isAlive`, `killOwned`); `runtime/install/OpenChamberInstaller.kt` (`ProotCommands`, `ProcessScanner`, `terminateProcessTree`); `data/WebSessionAuthenticatorImpl.kt` (`readyLaunchId` via `LocalReadiness`).
- Wiring: `di/AppModule.kt` builds `ProotOpenChamberServer`, `ProotStaleServerCleaner`, and the `killOwnedRuntime` kill hook.
- Storage: `ProotCommands.serverCommand` takes `storage/GuestBinds`.
- External contracts:
  - Health: `GET /health` returns JSON with `status` ("ok"), `isOpenCodeReady`, `openCodeRunning`, `lastOpenCodeError`, `serverId`.
  - Listen line: `OpenChamber server listening on 127.0.0.1:<port>`.
  - Notify prefix: `[OpenChamberDesktopNotify] ` followed by JSON (`NotificationPayload.PREFIX`).
  - Pid file: `~/.config/openchamber/run/openchamber-<port>.pid`, which OpenChamber refuses to start over when its owner is live.
  - Exit marker: `EADDRINUSE` in output.
