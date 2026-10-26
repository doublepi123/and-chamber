# phone/

## Responsibility

Default-off local phone control, separate from the WebView browser. One trusted on-device runtime,
one native-confirmed task/target, a short lease, and a TLS ADB driver. See
[feature boundaries](../../../../../../../../docs/phone-control.md) and
[emulator acceptance](../../../../../../../../docs/testing-phone-control.md).

## Areas

- `core/`: Android-free contracts, admission guard, single-operation controller, bounded request
  records, observation generations, structured delivery/unknown results and monotonic leases.
- `adb/`: local-host policy, pairing receipts, fixed command schema, bounded XML/window parsing,
  window/content witnesses, native device transport and driver. No arbitrary shell tool surface.
- `tool/`: strict versioned tool schema, authenticated loopback server, per-launch claims/cancel
  tombstones, native-only pending proposals and session-label routing. Labels are not authentication.
- `android/`: lazy AndroidKeyStore identity, private receipt metadata, native coordinator/foreground
  service, notification pairing replies, target catalogue, discovery, process/epoch intent identities
  and the validating notification Return Activity.
- `overlay/progress/`: bounded task/event store and native event ports. Task identity is stable across
  grant epochs; model narration stays unverified. Handoff/end require native stop receipts.
- `overlay/safety/`: dynamic mode gate, real WindowManager witnesses, geometry-generation policy and
  point/swipe exclusion. A manager expectation is not proof that the overlay is visible.
- `android/overlay/`: separate bounded display service, permission/demo flow, manager-owned layout,
  first-frame-aware read-hide tickets, progress adapters and the lazy driver barrier.

## Flow

`assets/phone-tool` registers `andchamber_phone` in OpenCode. Per-launch injection and endpoint
ownership live in `runtime/process/PhonePluginAssets.kt` and `PhoneToolHost.kt`. Native confirmation
creates a grant, proves the foreground stop entry, publishes its session route and opens the target.
Calls then pass the tool server, core admission and live driver checks before reaching adbd.

`ui/phone` uses its own immutable presentation model, mapped by `android/PhoneScreenStateMapper`.
Connection, authority and task facts are separate. Reconnecting cannot grant. Unknown operations
require review and are never automatically replayed. Returning to the host or taking over revokes
observation as well as mutation; a plain Pause blocks new mutations.

The controller clock is `elapsedRealtime`, including the native UI countdown. Runtime launch
identities stay monotonic within the same server instance. Old proposal revisions, grant attempts,
launch callbacks and PendingIntents cannot publish/clear a newer owner's state.

The optional overlay is not the browser window. Its presentation is `ui/phone/overlay`; native setup
tests reachability without granting control. Explicit notification-only mode retains the original
driver path. Selected overlay mode requires real window evidence, temporarily hides its own surface
for collection and restores only current native authority after a first frame. A short synchronized
ticket store isolates cancellation, restore timeout and newer owners; stop requests are never
converted into rollback claims. The overlay can stop/return/end, never grant or renew.

The tool's `progress`, `need_user` and `finish` actions use a frozen explicit epoch and the native
published route. Native-only completion callbacks carry observation/delivery metadata, not typed
input or pixels. `PhoneTaskProgressAdapter` projects only the matching task epoch into a display;
demo surfaces never inherit a real task feed. Native End saves a needs-check record before publishing
its exact-owner end fact; closing the overlay result leaves that record intact.

## Driver dependency patches

`io/github/muntashirakon/adb/AndChamberAdbTransport.kt` and `AndChamberAdbStreamReader.kt` are
same-package LibADB 3.1.1 adapters: bounded/cancellable pairing, TLS-only checks and correct queued
payload/CLSE draining. They reuse upstream crypto and fail closed if the pinned layout changes.
Source/license/relink information is in `third_party/phone-adb/`.

## Verification boundaries

JVM tests cover state/races/driver/protocol/receipt codecs. Native wiring and UI smoke are under
`androidTest/.../phone/`. `scripts/phone-e2e/` supplies a synthetic target and deterministic model
protocol, not a fake device driver. Covered API 35/x86_64 flows passed; arm64/OEM and actual model
reasoning quality are not established by that evidence.
