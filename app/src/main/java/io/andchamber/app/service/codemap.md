# app/src/main/java/io/andchamber/app/service/

## Responsibility

Keeps runtime work (install, start, server lifetime) alive in the background through one `specialUse` foreground service, `RuntimeService`. It:

- Shows the ongoing status notification (channel `openchamber-runtime`, IMPORTANCE_LOW, id 4101).
- Forwards queued `RuntimeOperation`s from intents to `LocalRuntimeController`.
- Decides, from the controller's `busy` and `state`, whether to stay foreground or stop.
- Holds a partial wake lock exactly while foreground.

Files: `RuntimeService.kt` (Android service), `RuntimeServicePolicy.kt` (pure decision logic), `WakeHold.kt` (process-wide wake lock).

## Design

- `RuntimeService` is `Service()` with `exported=false`, `specialUse` type (manifest subtype text: keeps the on-device OpenChamber web server running in the background).
- Dependency: `LocalRuntimeController` via Koin `inject()`. Exposes `busy: Flow<Boolean>` and `state: StateFlow<RuntimeState>`, and `perform(RuntimeOperation)`.
- Foreground barrier: `onStartCommand` calls `enterForeground(...)` before anything else (`ServiceCompat.startForeground`, type `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`).
- Actions on the start intent:
  - `ACTION_OPERATION` with extra `operation` (enum name of `RuntimeOperation`) → `runtime.perform(op)`.
  - `ACTION_STOP_NOTIFICATION` → `runtime.perform(RuntimeOperation.Stop)`.
- `startedOnce` flag: set on the first start. Until then the policy returns `KeepRunning`, so resting states from before the first intent cannot stop the service.
- `latestStartId`: the newest startId seen. Completion uses `stopSelfResult(latestStartId)`, so an older request cannot stop a newer one.
- Policy (`RuntimeServicePolicy.decide(busy, state, startedOnce)`):
  - `!startedOnce` → `KeepRunning`.
  - `busy` → `KeepRunning`.
  - `Running` or `Starting` → `KeepRunning`. `Starting` covers a watchdog restart waiting out its backoff.
  - `Stopped`, `NotInstalled`, `Failed`, `UnsupportedAbi` → `Finish`.
  - Anything else (`Detecting`, `Installing`, `Stopping` with nothing queued) → `KeepRunning`.
  - `mayStop(latest, requested)` and `wakeLockHeld(action)` are pure helpers that document the stale-startId and lock rules. `RuntimeService` itself relies on `stopSelfResult(latestStartId)` and does not call them.
- `apply(action, state)`:
  - `KeepRunning`: if already foreground, `notify(NOTIFICATION_ID, buildNotification(state))`; otherwise `enterForeground`. Then `WakeHold.acquire(this)`.
  - `Finish`: `leaveForeground()`.
- `leaveForeground()`: `WakeHold.release()`; if foreground, `stopForeground(STOP_FOREGROUND_REMOVE)`; then `stopSelfResult(latestStartId)`.
- `buildNotification(state)`:
  - Installing: setup detail or `runtime_notification_setup` plus percent; ongoing.
  - Starting / Stopping: string resources; ongoing.
  - Running: `runtime_notification_running` with the URL; ongoing.
  - Others: `UserMessages.failureText` (Failed), or not-installed, stopped or unsupported strings; not ongoing.
  - Stop action only for Starting or Running (`stopIntent` → `ACTION_STOP_NOTIFICATION`).
  - Tap opens `MainActivity` with `SINGLE_TOP`.
- `onDestroy`: `WakeHold.release()`, cancel observer job, cancel scope, super.
- Channel: `ensureChannel` (API 26+) recreates name and description on each start so language changes apply.
- `WakeHold`: singleton, `PARTIAL_WAKE_LOCK`, tag `AndChamber:runtime`, `setReferenceCounted(false)`, no timeout. `acquire` is idempotent; `release` is safe to call repeatedly; `isHeld()` is for diagnostics.
- `RuntimeService.request(context, operation)` (companion): builds the intent and calls `startForegroundService` on API 26+, `startService` below. Called from the UI thread in the foreground.

## Flow

Start of an operation:
1. Caller → `LocalRuntimeController.perform` → `ForegroundStarter.start(op)` → `RuntimeService.request(context, op)`.
2. `startForegroundService(intent)` → `onCreate` (channel, observer started).
3. `onStartCommand` → `enterForeground(buildNotification(state))` → `latestStartId`, `startedOnce = true`.
4. `ACTION_OPERATION` → `runtime.perform(op)`.
5. Observer receives `combine(busy, state)` → `decide` → `KeepRunning` → `WakeHold.acquire`.

Lifecycle end:
1. Controller goes idle with `busy == false` and state in {Stopped, NotInstalled, Failed, UnsupportedAbi}.
2. Observer → `Finish` → `leaveForeground()` → `WakeHold.release()` → `stopForeground(REMOVE)` → `stopSelfResult(latestStartId)`.
3. `onDestroy` → `WakeHold.release()` → cancel observer.

Stop from the notification:
1. Stop button → `ACTION_STOP_NOTIFICATION` → `onStartCommand` → `runtime.perform(Stop)`.
2. Controller state moves to Stopping then Stopped → observer `Finish` as above.

## Integration

- Imports `MainActivity`, `R` (`runtime_notification_*`, `ic_notification`), `i18n/UserMessages.kt`, `runtime.LocalRuntimeController`, `runtime.RuntimeOperation`, `runtime.RuntimeState`.
- Started from `runtime/` (`ForegroundStarter` lambda in `di/AppModule.kt`) only. Not bound.
- Manifest entry: `app/src/main/AndroidManifest.xml`, `android:name=".service.RuntimeService"`, `exported="false"`, `foregroundServiceType="specialUse"`. Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- `WakeHold` is called from this folder only (`RuntimeService`). Its doc says the foreground starter acquires it before commands run; that path is not confirmed here (see `runtime/codemap.md`).
- No HTTP endpoints. Local OpenChamber health is checked by `runtime/process/HealthProbe` (not here).
