# app/src/main/java/io/andchamber/app/ui/

## Responsibility

Root of the Compose UI. Hosts the navigation graph (home and one web screen per server), the launch decision and launch cover, notification-tap deep linking, system bar styling, and storage and notification permission requests. Screens live in `home/` and `web/`, shared widgets in `components/`, tokens in `theme/`.

Files:
- `AndChamberApp.kt`: `AndChamberApp(...)` root, `Routes`, `LocalActions`, launch and link coroutines.
- `LaunchDecision.kt`: pure rules (`launchTarget`, `initialLaunchStage`, `shownLocalState`, `withSessionPath`). No Android or Compose imports, so unit tests call them directly.
- `LaunchFlow.kt`: launch timing constants, `UiPrefs`, `LaunchCover`, `isLauncherLaunch()`.
- `SystemUi.kt`: `findActivity()`, `SystemBarsEffect`, `WindowBackgroundEffect`.
- `StorageAccess.kt`: `rememberStorageAccessRequest`, `hasStorageAccess`.
- `ComposeUtils.kt`: `rememberLastNonNull`, `revealOnEnter`, `rememberCopyAction`, `rememberNotificationPermissionRequest`.
- `Format.kt`: `formatBytes`, `uptimeLabel`, `displayAddress`, `checkServerAddress` (returns `AddressCheck`), `isLoopbackHost`.
- `preview/`: Compose previews and fakes (not described here).

## Design

- Navigation: `NavHost` with `Routes.HOME = "home"` and `Routes.WEB = "web/{targetId}"`. `Routes.web(id)` URL-encodes the id. `ServerTarget.LOCAL_ID` denotes the on-device server.
- Launch decision is a pure function: `launchTarget(RuntimeState)` gives `Wait`, `Home`, `OpenLocal` or `StartAndOpenLocal`. Setup, failures, unsupported ABI, Installing and Stopping go to `Home`. Stopped starts and then opens. Running and Starting open.
- `LaunchStage` (`Waiting`, `Opening`, `Done`) drives the cover. `initialLaunchStage` runs on frame one, so Home never flashes when an auto-open is possible.
- `LocalActions` wraps `LocalRuntimeController` for UI intents. `pendingStartFrom` makes a start read as Starting until the runtime reports back, with an 8 s safety timeout (`PENDING_START_TIMEOUT_MS`). `retry()` reinstalls on `FailurePhase.Install` and restarts otherwise. `restart()` is one queued runtime call.
- Flows are collected inside each destination, not at the root, so the graph is not rebuilt per log line. Shared-storage calls run in order on `Dispatchers.IO` through a `Channel`.
- Saved state: `launchPending` (decide once per launch) and `sessionLink` (pending notification deep link).
- `UiPrefs` stores `auto_open_local` (default true) in SharedPreferences `ui_prefs`.
- Insets and bars: `SystemBarsEffect` sets bar icon appearance from background luminance. `WindowBackgroundEffect` syncs the window background with the theme.
- While the cover is up, `NavHost` transitions are `EnterTransition.None`/`ExitTransition.None`. The cover fades out afterwards.
- Theme: `AndChamberTheme` wraps the root once. Dynamic color is off.

## Flow

Cold launch to local OpenChamber:
1. `AndChamberApp` composes with `launchPending = true`. `initialLaunchStage` yields `Waiting` (Detecting, auto-open on), `Opening` (Running, Starting or Stopped, auto-open on), or `Done`.
2. `Waiting` shows only `LaunchCover` (background plus `ChamberMark`). `NavHost` is not composed yet.
3. `openLocalOnLaunch` waits for `state != Detecting`, up to `LAUNCH_DETECT_TIMEOUT_MS` (8 s). On timeout it reveals Home (stage `Done`) and keeps waiting.
4. `launchTarget` returns `Home`: stage `Done`, return. Otherwise `onOpening()` sets `Opening`, composing `NavHost` under the cover.
5. Waits up to `NAV_READY_TIMEOUT_MS` (2 s) for the back stack, then for `RESUMED` (a service start needs the foreground). Checks the top route is still Home.
6. Re-decides on the current state. `StartAndOpenLocal` calls `local.start()`, which asks for notifications first. A failed start leaves the user on Home.
7. `navigate(web/local)` with `launchSingleTop`. Waits for the WEB entry plus two frames, then stage `Done`. The cover fades out over `LAUNCH_COVER_FADE_MS`.
8. Cover timing: the background shows alone for 120 ms, then the mark fades in. After 1.5 s the caption and breathing animation start.

Notification tap to `/?session=<id>`:
1. `OpenSessionLink.pending` emits. The root calls `take()`, stores `sessionLink = OpenSessionLink.pathFor(sessionId)` in saved state, and bumps `linkRequest`.
2. During a cold launch, `wantsLocal()` already treats the link as a reason to open local. The link coroutine waits for `launchPending == false`.
3. `openLocalForLink` waits for detection and nav readiness, then `RESUMED`. Stopped starts the server (via `shownLocalState`, so a pending start is not repeated). Setup, problems or unsupported devices call `onDiscard()` and `showHome()`.
4. `navigate(web/local) { popUpTo(HOME); launchSingleTop }` unless already on the local route.
5. `WebScreen` loads the page with the link, or runs `navigateWithinSession` if the session is already loaded. Then `onDeepLinkUsed` clears `sessionLink`.
6. Leaving the local screen through `onExit` also clears `sessionLink`, so a stale link never applies twice.

Back behavior:
1. `WebScreen` `BackHandler`: if there is no overlay and the WebView can go back, `goBack()`. For local with no overlay, `moveTaskToBack(true)`, so Back leaves the app rather than stepping into Home. Otherwise `onExit()`.
2. `onExit` for a remote server, or from an overlay, calls `closeIfResumed` then `showHome()`. `showHome()` pops to HOME if it is on the stack, else navigates to HOME with `popUpTo(0)`.
3. `openIfResumed` ignores taps while a transition runs.

Local server start from UI:
1. Home card actions map to `HomeActions`: Set up (NotInstalled), Start (Stopped), Retry or Repair (Failed), Stop and Open (Running).
2. `local.start()` requests POST_NOTIFICATIONS once (Android 13+), sets `pendingStartFrom` when starting from Stopped or Failed, then calls `runtime.start()`.
3. Web overlays offer Start again or Try again through `onStartLocal = local.startOrRetry`.
4. Shared-folder change while running shows a restart notice; `local.restart()` queues stop then start.
5. Repair asks for confirmation, then `pool.discard(LOCAL_ID)` and `runtime.repair()`. Uninstall discards the pooled WebView before `runtime.uninstall()`.

Shared-storage permission:
1. `SharedStorageCard` shows when `storage != null`, not checking, and the state is not Detecting, UnsupportedAbi or NotInstalled.
2. The switch calls `storage.setEnabled` on the IO channel. Turning off while Active first asks for confirmation.
3. Allow access: on Android 11+ it opens "All files access" settings (per-app screen, then list, then app details). Below 30 it requests `READ/WRITE_EXTERNAL_STORAGE`. After two denials with no rationale, the next tap opens app details.
4. `ON_RESUME` (in the root and in `NeedsPermissionBody`) triggers `refreshPermission()` and `hasStorageAccess()`. If access is granted but status lags, the card waits 900 ms (`STATUS_SETTLE_MS`) before reporting the folder as unavailable, with Check again.

## Integration

- Entry point: `AndChamberApp(runtime, remotes, auth, storage)`, called by `MainActivity` (outside these folders). The launcher-tap listener needs `launchMode="singleTop"`.
- `runtime.LocalRuntimeController`: `state`, `info` and `logTail` flows, plus `install`, `start`, `stop`, `restart`, `repair`, `uninstall`. Consumes `RuntimeState` (`Running` carries `url`, `port`, `sinceEpochMs`), `FailurePhase`, `RuntimeInfo`, `InstallStep`, and `ServerTarget.Local(url)`.
- `runtime.RemoteServerStore`: `servers` flow, `upsert`, `remove`. The web session key uses the remote id, baseUrl and a hash of the UI password.
- `runtime.WebSessionAuthenticator.prepare(target, cookies)` → `WebSessionResult` (`Ready`, `NeedsLogin`, `Unreachable`). Used by `WebScreen` only after the target's profile is bound (`WebSessionProfiles` via `ui/web/WebDependencies.kt`). The root passes `WebViewPool(downloads)` the app's `HttpDownloadStarter`.
- `runtime.SharedStorageController`: `state` flow, `setEnabled`, `refreshPermission`. Drives `SharedStorageState` and `SharedStorageStatus`.
- `notify.OpenSessionLink`: `pending` flow, `take()`, `pathFor(sessionId)`.
- `i18n.UserMessages.stepLabel(InstallStep)` maps install steps to `R.string`.
- All user-facing text is `R.string` and `R.plurals`, resolved with `stringResource` in composition or `context.getString` in WebView callbacks.
