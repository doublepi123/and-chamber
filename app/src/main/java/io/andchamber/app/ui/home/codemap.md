# app/src/main/java/io/andchamber/app/ui/home/

## Responsibility

The control-room home screen (`Routes.HOME`). Shows the on-device OpenChamber runtime (setup, install progress, start and stop, failures, details, log), the shared-storage folder card, and saved remote servers with add, edit and remove.

Files:
- `HomeScreen.kt`: `HomeScreen`, `HomeActions` (lambdas passed in from the root), header, one-shot `HomeEntrance`.
- `LocalRuntimeCard.kt`: `LocalRuntimeCard`, `RuntimePhase` (coarse phase of `RuntimeState`), per-phase bodies, footer toggles, `AutoOpenRow`.
- `SharedStorageCard.kt`: `SharedStorageCard` (switch, status, folder tree, restart notice, permission and unavailable bodies).
- `RemoteServers.kt`: `RemoteServersSection`, server rows, `RemoveServerDialog`, `ServerEditorDialog`.

## Design

- State is hoisted. Composables take plain data and `HomeActions` lambdas. The root (`ui/AndChamberApp.kt`) owns flows and side effects. `HomeScreen` is also used by previews.
- `RuntimePhase` collapses `RuntimeState` to a phase. `AnimatedContent` keys on the phase, so progress ticks update in place. `rememberLastNonNull` keeps the outgoing body during exit transitions.
- `checking` overrides the phase to `Checking` during detection, showing a spacer, not a Set up offer.
- Installing lists `InstallStep.entries`: earlier steps are Done, the current one is Active (ring, percent, bar, detail), later ones are Pending. A vertical rail is drawn with `drawBehind`.
- Running shows the URL, uptime (a 30 s `produceState` ticker), port and a copy action. Failed shows `failed.reason`. Recoverable failures get Retry and Repair. Non-recoverable ones get only Repair, with an explanation.
- Repair and Uninstall go through `ConfirmDialog`. The uninstall wording changes when the shared folder is in use, since projects stay in place.
- `NotInstalledBody` shows free space from `filesDir.usableSpace` and warns below 3 GiB (`LOW_SPACE_BYTES`).
- `SharedStorageCard` has three states: `Off`, `NeedsPermission`, `Active`. Turning it off while Active asks for confirmation. The folder tree is drawn with rails. `codeSpans()` sets paths in mono.
- Remote editor: address and name use `rememberSaveable`. The password uses plain `remember`, so it is never saved in instance state. The address is normalized as the user types (`checkServerAddress`). Plain HTTP to a non-loopback host needs an explicit opt-in checkbox.
- Dialog state: `editingId` and `removingId` are `rememberSaveable` ids. `NEW_SERVER` is a sentinel for the add flow. A dialog closes itself if its server disappears.
- Layout: a `verticalScroll` column with safe-drawing horizontal insets, `widthIn(max = 640.dp)`, and top and bottom spacers. Content scrolls under the transparent system bars.
- Entrance: `revealOnEnter(index)` staggers items by 70 ms. `HomeEntrance` makes it run once per process. Inspection mode skips it.

## Flow

Render:
1. The root collects `runtime.state`, `info`, `logTail`, `remotes.servers` and `storage.state` inside the HOME destination.
2. `HomeScreen(state = shownLocalState(state, pendingStartFrom), checking, ...)`.
3. `LocalRuntimeCard` derives the phase and renders the matching body. The log toggle appears when there is a log. The details toggle appears when `info != null` or the phase is Failed.
4. `SharedStorageCard` shows when `storage != null && !checking && state.allowsSharedStorage()`, which excludes Detecting, UnsupportedAbi and NotInstalled.

Install and start:
1. Set up → `HomeActions.onSetUp` → `local.install()`. The card moves to Installing with the current step and progress.
2. Stopped → Start → `local.start()`. The card shows the starting transition while `pendingStartFrom` is set.
3. Running → Open → `nav.openIfResumed(entry, Routes.web(LOCAL_ID))`.
4. Failed → Retry → `local.retry()`. Repair opens a confirm dialog, then `local.repair()`.

Remote servers:
1. Empty list: `EmptyServersCard`. Otherwise, a `SectionHeader` with an Add button.
2. Row tap → `onOpenRemote` → `openIfResumed(Routes.web(server.id))`.
3. Row menu: Edit → `ServerEditorDialog`. Remove → `RemoveServerDialog` → `onRemoveRemote`, which discards the pooled WebView and calls `remotes.remove`.
4. Save: `checkServerAddress` must return `Valid`. An insecure remote with a password needs the opt-in. Then `onSaveRemote(ServerTarget.Remote(...))`. New servers get a UUID, and the id is kept on edit.

Auto-open setting: `AutoOpenRow` appears in Details when `autoOpenLocal != null` and `info != null`. It calls `onAutoOpenChange`, which updates `UiPrefs`.

Storage card:
1. Switch on → `storage.setEnabled(true)` on the IO channel. Switch off while Active → confirm → `setEnabled(false)`.
2. Allow access → `requestStorageAccess()`. Check again → `refreshStorage()`.
3. Restart notice (Running and `restartRequired`) → `local.restart()`.

## Integration

- Used by `ui/AndChamberApp.kt`, which builds `HomeActions` and passes state. The `HomeActions` doc says previews share this surface (`ui/preview/`).
- Runtime types: `RuntimeState`, `FailurePhase`, `RuntimeInfo`, `InstallStep`, `ServerTarget.Remote`, `ServerTarget.LOCAL_ID`, `SharedStorageState`, `SharedStorageStatus`.
- From `ui/`: `hasStorageAccess`, `rememberStorageAccessRequest`, `checkServerAddress`, `AddressCheck`, `displayAddress`, `isLoopbackHost`, `rememberCopyAction`, `rememberLastNonNull`, `revealOnEnter`, `formatBytes`, `uptimeLabel`.
- From `ui/components/`: `ChamberCard`, `ConfirmDialog`, `IconTile`, `DetailRow`, `LogConsole`, `SectionHeader`, `StatusPill`, `ChamberMark`.
- `i18n.UserMessages.stepLabel(InstallStep)` and `R.string.*` / `R.plurals`.
- `ON_RESUME` observed via `LifecycleEventEffect` in `SharedStorageCard`.
