# phone/

## Responsibility

Default-off local phone control, separate from the WebView browser. One trusted on-device runtime, a
native configure-once bounded standing scope (apps/actions/window/per-task, with explicit permanent
choices), a fresh epoch and short lease per task, and a TLS ADB driver. Pairing and connection setup
use a bounded native discovery service over the two official ADB service types, a local-address-only
endpoint and a loopback bind-occupancy probe; the notification then asks only for the six-digit code.
The same native task can project a current-task question/notice into the overlay from local stdout
notification events. Production readiness uses native RPC plugin registration after the plugin
transform/tool list. See [feature boundaries](../../../../../../../../docs/phone-control.md) and
[emulator acceptance](../../../../../../../../docs/testing-phone-control.md).

## Areas

- `core/`: Android-free contracts, admission guard, single-operation controller, bounded request
  records, observation generations, structured delivery/unknown results, monotonic leases, the single
  `PhoneTimeLimits.PERMANENT` sentinel, standing block/grant/resume primitives and target policy.
- `standing/`: native-only standing scope, admission policy, refusal/start/open-app result types and
  the `PhoneTaskStarter` port. Permanent window/per-task values remain configuration and are still
  bounded by a finite scope end; the model/tool surface cannot construct a scope or grant itself.
- `adb/`: local-host policy, pairing receipts, fixed command schema, bounded XML/window parsing,
  window/content witnesses, native device transport and driver. No arbitrary shell tool surface.
- `tool/`: strict versioned tool schema, authenticated loopback server, per-launch claims/cancel
  tombstones, session-label routing, direct `request`, preauthorized `apps` and same-target
  `open_app`, plus permanent-lease status without a countdown. The old native pending-proposal store
  remains only for compatibility; the primary mode has no `PENDING_CONFIRMATION`. Labels are not
  authentication.
- `android/`: lazy AndroidKeyStore identity, private receipt metadata, native coordinator/foreground
  service, notification pairing replies, target catalogue/verdicts, bounded auto-port discovery with
  resolve tokens and loopback bind validation, process/epoch intent identities, the validating
  notification Return Activity, standing config persistence (finite or permanent sentinel)/mapping,
  the lazy tool starter adapter and the one-shot continuation sender.
- `overlay/progress/`: bounded task/event store and native event ports. Task identity is stable across
  grant epochs; model narration stays unverified. Handoff/end require native stop receipts.
- `overlay/safety/`: dynamic mode gate, real WindowManager witnesses, geometry-generation policy and
  point/swipe exclusion. A manager expectation is not proof that the overlay is visible.
- `android/overlay/`: separate bounded display service, permission/demo flow, manager-owned layout,
  first-frame-aware read-hide tickets, prompt/notice projection from the current task's notification
  question, progress adapters, the lazy driver barrier and the bounded `awaitDriverSurface(epoch)`
  used before the first driver read.

## Flow

`assets/phone-tool` registers `andchamber_phone` in OpenCode. Per-launch injection, endpoint ownership
and readiness live in `runtime/process/PhonePluginAssets.kt` and `PhoneToolHost.kt`. Production
readiness is not the old setup `/v1/ready` call: after the plugin transform and `ctx.tool.list`
confirm the tool, it registers the read-only `andchamber-phone/ready` RPC. A native probe calls
`POST /api/rpc/andchamber-phone/ready` asynchronously on a bounded retry and marks only that launch
ready when `output.ok==true` and `output.id=="andchamber_phone"`. Stop/new launch cancels the probe
and late results are ignored; the legacy transport handshake remains only as a fallback when no RPC
probe is wired. The runtime is restarted only after the person confirms it.

Saving a standing scope stores apps, actions, the window and per-task duration; it is configuration,
not a lease. Either duration can be the explicit `PhoneTimeLimits.PERMANENT` sentinel; it is stored
and restored as configuration only, never as a persisted grant. A finite window still bounds a
permanent per-task choice. The first valid chat `request` binds the scope to the current runtime and
session. Every task then creates a fresh epoch and a lease bounded by the per-task duration and the
remaining window; another session is refused. `request` performs scope checks, the core standing
grant, the foreground stop entry, native task/lease binding, launching one preauthorized package and
a real `Observe` of that foreground target before replying `Started(RUNNING)`. Failure rolls back
exactly the epoch/task. `apps` lists only preauthorized labels/packages, never the installed
catalogue; `open_app` accepts only the live task's same session/target and proves the foreground
observation.

Explicit pairing starts `PhoneDiscoveryService` as a bounded foreground service while the system
settings pages are open. It searches official `_adb-tls-pairing._tcp` (query without a trailing dot,
normalising a reported leading dot, exact type only), accepts only loopback/own-interface numeric
addresses, and uses `LoopbackBindPortProbe` only to prove that a single resolved port already has a
local owner (`EADDRINUSE`); a free or errored bind is discarded. The notification then contains only
the six-digit code RemoteInput. A real pairing success opens one fresh bounded
`_adb-tls-connect._tcp` search and performs one automatic receipt/configure/TLS connect. Already
paired users use **Find and connect**. The old typed host/port path is manual fallback. Cancel,
completion or the five-minute bound stops discovery and releases NSD/API 30–33 multicast; Android 13
with T extension SDK 7 (and newer) may follow port updates with `registerServiceInfoCallback`,
otherwise legacy resolve is serial and bounded. Resolve results are gated by per-advertisement
tokens and session revisions, so a service-lost callback cannot revive a stale port.

Returning to ordinary local chat does not revoke. Entering the real native phone page applies the
host-return stop; notification/overlay Return remain explicit revoke actions. `need_user`, takeover,
unknown outcome, screen lock, connection loss and runtime change set a standing block. Only a native
person confirmation may continue: the same retained task/session/runtime gets a fresh epoch, a fresh
observation and exactly one fixed wake-up prompt through the launch-guarded `LocalApiClient` to the
original session. A failed send is not retried and does not roll back an established task; no model
or tool text is treated as business success.

`ui/phone` uses its own immutable presentation model, mapped by `android/PhoneScreenStateMapper`.
Connection, authority, task and standing readiness are separate. Reconnecting cannot grant. Unknown
operations require review and are never automatically replayed. The controller clock is
`elapsedRealtime`, including the native UI countdown. Runtime launch identities stay monotonic within
the same server instance. Old proposal revisions, grant attempts, launch callbacks, registration
results and PendingIntents cannot publish/clear a newer owner's state.

The optional overlay is not the browser window. Its presentation is `ui/phone/overlay`; native setup
tests reachability without granting control. Explicit notification-only mode retains the original
driver path. Selected overlay mode requires real window evidence, temporarily hides its own surface
for collection and restores only current native authority after a first frame. Before the first read
for an epoch, the native side waits on a bounded current-epoch surface attach; a timeout fails the
start/continuation and rolls back exactly that lease. Overlay loss reports an honest
stop-channel/visibility reason, never a person takeover, and requires native recovery. Normal
hide/restore is separate from a real failure. A short synchronized ticket store isolates
cancellation, restore timeout and newer owners; stop requests are never converted into rollback
claims. The overlay can stop/return/end, never grant or renew.

The tool's `progress`, `need_user` and `finish` actions use a frozen explicit epoch and the native
published route. Native-only completion callbacks carry observation/delivery metadata, not typed
input or pixels. `PhoneTaskProgressAdapter` projects only the matching task epoch into a display;
demo surfaces never inherit a real task feed. Native End saves a needs-check record before publishing
its exact-owner end fact; closing the overlay result leaves that record intact.

The question/notice projection is separate from task progress. It starts from local runtime stdout
`DesktopNotification` events, reads the pending form once for the event and again on open/refresh,
and binds one answer to the current native task, route session, launch, form id, schema fingerprint
and generation. Only a Ready single-choice question exposes choices and Send in the overlay; a
complex/unsupported form opens the local chat, unknown send is never retried and Permission keeps its
original notification path. Plain Ready/Error/Goal/RestartInterrupted notices are bounded text and
never change the answer identity. Selecting an answer collapses the panel first; only a failed send
with the same task/epoch/launch and same form/generation may re-expand it read-only.

## Driver dependency patches

`io/github/muntashirakon/adb/AndChamberAdbTransport.kt` and `AndChamberAdbStreamReader.kt` are
same-package LibADB 3.1.1 adapters: bounded/cancellable pairing, TLS-only checks and correct queued
payload/CLSE draining. They reuse upstream crypto and fail closed if the pinned layout changes.
Source/license/relink information is in `third_party/phone-adb/`.

## Verification boundaries

JVM tests cover state/races/driver/protocol/receipt codecs, standing admission/rollback, permanent
duration/sentinel handling, discovery endpoint/token/probe policy, registration probe
retry/cancellation and readiness ownership. Native wiring and UI smoke are under
`androidTest/.../phone/`. `scripts/phone-e2e/` supplies a synthetic target
(`io.example.phonefixture`) and a deterministic model protocol, not a fake device driver or model
reasoning. Covered API 35/x86_64 emulator flows passed, including direct begin, `apps`/`open_app`,
native continuation, RPC readiness and the first overlay-read wait. The newer auto-port path (fresh
port probe, real six-digit SystemUI RemoteInput, automatic TLS connect), permanent standing
save/restart with the real lease flag, and the real built-in single-choice question (HTTP 204, same
model turn, Save count exactly one, first unknown not positioned/not resent, two clean end-to-end
runs) are API 35/x86_64 emulator evidence only. A separate API 37/arm64 tablet follow-up covered
direct input, overlay handoff/continuation, capture and consecutive task ownership; it does not cover
these newer features. Broad OEM behavior, TalkBack, API 37 new-feature behaviour and real-model
reasoning quality are not established by that evidence.
