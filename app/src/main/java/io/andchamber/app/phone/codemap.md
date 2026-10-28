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
  The controller starts each driver call from the fresh remaining plan deadline (driver margin ≤500 ms
  is reporting-only), and on `READ_TIMEOUT` publishes the logical disconnect/revoke in the same call,
  keeps the first error, preserves unknown, and fences the revoke to the planned connection owner.
- `standing/`: native-only standing scope, admission policy, refusal/start/open-app result types and
  the `PhoneTaskStarter` port. Permanent window/per-task values remain configuration and are still
  bounded by a finite scope end; the model/tool surface cannot construct a scope or grant itself.
- `adb/`: local-host policy, pairing receipts, fixed command schema, bounded XML/window parsing,
  window/content witnesses (actions compare only the real-window part; a tap by ref also needs its
  element at the observed bounds), native device transport and driver. Fixed typed read failures:
  `AdbDeviceFailure.HIERARCHY_NOT_IDLE` for the whole-trim fixed idle sentence or a dump that
  stays empty after bounded in-deadline re-reads (an overlapping on-device dump holds UiAutomation),
  `AdbWindowStateFailure.NOT_READY` only for a complete null `mCurrentFocus`/`mFocusedApp` field line
  (mapped to `WINDOW_CHANGED`), `AdbDeviceFailure.READ_TIMEOUT` for an abandoned read-service
  open/read, and the monotonic read deadline gate. No arbitrary shell tool surface.
- `tool/`: strict versioned tool schema, authenticated loopback server, per-launch claims/cancel
  tombstones, session-label routing, direct `request`, preauthorized `apps` and same-target
  `open_app`, plus permanent-lease status without a countdown. The old native pending-proposal store
  remains only for compatibility; the primary mode has no `PENDING_CONFIRMATION`. Labels are not
  authentication.
- `android/`: lazy AndroidKeyStore identity, private receipt metadata, native coordinator/foreground
  service, notification pairing replies, target catalogue/verdicts, bounded auto-port discovery with
  resolve tokens and loopback bind validation, process/epoch intent identities, the validating
  notification Return Activity, standing config persistence (finite or permanent sentinel)/mapping,
  the fail-closed standing pause projection and fresh-gated person resume coordinator, the shared
  startup/continuation observation loop (one 15 s budget, only explicit window transitions retried,
  first typed failure retained), the lazy tool starter adapter and the one-shot continuation sender.
  A failed continuation keeps the same task identity/binding and latest attempted epoch, publishes a
  native stop receipt instead of abandoning the key, and the status getter projects the same
  immutable screen facts (`taskRetained`, `taskState`, continuation state/failure) read-only.
- `overlay/progress/`: bounded task/event store and native event ports. Task identity is stable across
  grant epochs; model narration stays unverified. Handoff/end require native stop receipts.
- `overlay/safety/`: dynamic mode gate, real WindowManager witnesses, geometry-generation policy and
  point/swipe exclusion, plus the default-false `makeWay` capsule escape on the barrier contract. A
  manager expectation is not proof that the overlay is visible.
- `android/overlay/`: separate bounded display service, permission/demo flow, manager-owned layout,
  first-frame-awaited automatic make-way placement, capture-only read-hide tickets, prompt/notice
  projection from the current task's notification question, progress adapters, the lazy driver
  barrier and the bounded `awaitDriverSurface(epoch)` used before the first driver read.

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
remaining window; another session is refused only while the bound chat still owns a live/retained task (after it ends, the next task from any chat on the same runtime rebinds the session). `request` performs scope checks, the core standing
grant, the foreground stop entry, native task/lease binding, launching one preauthorized package and
a real `Observe` of that foreground target before replying `Started(RUNNING)`. Failure rolls back
exactly the epoch/task. `apps` lists only preauthorized labels/packages, never the installed
catalogue; `open_app` accepts only the live task's same session/target and proves the foreground
observation.

That start/continuation observation uses one shared budget of at most 15 seconds, including the
overlay wait and the real remaining time; it retries only explicit window/geometry/target
transitions. A busy hierarchy is `HIERARCHY_NOT_IDLE`, and a read-service watchdog expiry is
`READ_TIMEOUT`; the latter publishes the logical disconnect/revoke in the same call and never claims
the physical close. A complete official `mCurrentFocus=null`/`mFocusedApp=null` line is `NOT_READY`
and maps to `WINDOW_CHANGED` inside the same budget; malformed/truncated/oversized/unknown output
stays fail-closed.

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

The handoff separates `NeedUser` from a person `TakenOver`. A model `need_user` keeps the original
task retained (`taskRetained`, `taskState=WAITING_USER`) without a live grant; the native title says
the agent is waiting and the mapper keeps a halted task Active even when a needs-check record exists.
A transient busy snapshot is `HIERARCHY_NOT_IDLE` but remains READY on the same grant/task and does
not become a need-user handoff. A pre-wake failure with no recovery grant, or an exactly rolled-back
attempt, records `NOT_RESUMED` plus a fixed failure reason while keeping the same task key/history
and latest attempted epoch/native stop receipt; the store may project STOPPING, but the task is not
abandoned and a fresh person confirmation may create a new grant/Observe. After a real recovery, the
one fixed wake is either `DELIVERED` or conservatively `FAILED`; `FAILED` is never re-sent and never
becomes retryable merely because no grant is live. The overlay phase takes the explicit NeedUser
halt fact before a stale `WAITING_USER` projection; explicit TakenOver is not relabelled by that
projection, and unknown/closed/Ended/new-live-grant facts keep their priority. The read-only status
fields never authorize, and no tool action/schema/permission/overlay grant is added.

A standing block with no retained task is projected as a pause, not as Ready. The mapper maps all six
native reasons exhaustively, fails closed to `OutcomeUnknown` when a block lost its reason, and does
not turn an idle or merely paused lease into a standing pause. The person's **Resume chat use…**
entry is a separate synchronous native action: it re-checks experiment/controller enablement, an
active non-revoked and non-expired scope, the current running tool-ready runtime, connection, the
real stop entry, no pending/continuation/in-flight/pausing operation, and no grant/halt/lease/
retained task/task identity or unchecked unknown. A different-runtime binding is refused rather than
silently rebound. Only after that gate does the existing `resumeStandingForPerson` clear the standing
block and ordinary pause under the controller lock; the saved scope, deadline, revision, store and
same-runtime/session binding are unchanged, and no grant or chat wake-up is created. Screen open,
reconnect and RPC readiness never auto-clear a pause. A stopped task keeps its existing continuation
entry and base readiness instead of this resume path.

`ui/phone` uses its own immutable presentation model, mapped by `android/PhoneScreenStateMapper`.
Connection, authority, task and standing readiness are separate. Reconnecting cannot grant. Unknown
operations require review and are never automatically replayed. The controller clock is
`elapsedRealtime`, including the native UI countdown. Runtime launch identities stay monotonic within
the same server instance. Old proposal revisions, grant attempts, launch callbacks, registration
results and PendingIntents cannot publish/clear a newer owner's state.

The optional overlay is not the browser window. Its presentation is `ui/phone/overlay`; native setup
tests reachability without granting control. Explicit notification-only mode retains the original
driver path. Selected overlay mode requires real window evidence and keeps the capsule visible for
observation: it is never the focused window, so its nodes cannot enter the focused app's hierarchy
dump, and a fresh VISIBLE witness is required before and after the read. Only capture hides and
restores its own surface, and only after a first frame. A tap/focus point or swipe segment that would
land on the capsule triggers one bounded make-way layout (opposite edge at the same height, then the
farthest vertical half) for the current live owner only; it never grants, renews, stops or persists
the person's drag posture, and a still-obstructed action is `OVERLAY_OBSTRUCTED` with nothing sent.
Before the first read
for an epoch, the native side waits on a bounded current-epoch surface attach; a timeout fails the
start/continuation and rolls back exactly that lease. Overlay loss reports an honest
stop-channel/visibility reason, never a person takeover, and requires native recovery. Hide/restore
of a capture is separate from a real failure. A short synchronized ticket store isolates
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
duration/sentinel handling, the standing pause projection/resume gate, discovery endpoint/token/probe
policy, registration probe retry/cancellation and readiness ownership. Native wiring and UI smoke are
under `androidTest/.../phone/`. `scripts/phone-e2e/` supplies a synthetic target
(`io.example.phonefixture`) and a deterministic model protocol, not a fake device driver or model
reasoning. Covered API 35/x86_64 emulator flows passed, including direct begin, `apps`/`open_app`,
native continuation, RPC readiness and the first overlay-read wait. The newer auto-port path (fresh
port probe, real six-digit SystemUI RemoteInput, automatic TLS connect), permanent standing
save/restart with the real lease flag, and the real built-in single-choice question (HTTP 204, same
model turn, Save count exactly one, first unknown not positioned/not resent, two clean end-to-end
runs) are API 35/x86_64 emulator evidence only. The paused standing recovery (permanent fixture-only
scope after runtime restart/reconnect, no chat prompt on resume, unchanged scope/epoch, fresh
`RUNNING` request) is also API 35/x86_64 emulator evidence only. The handoff follow-up on the same
lane covered a real need-user retained task, the busy-first-confirmation `NOT_RESUMED` retry, a
successful fresh second confirmation with exactly one original-session wake, transient busy remaining
READY, a second stop plus isolated wake-fault `FAILED` without resend, and explicit End/dismiss. It is
API 35 emulator evidence only and does not extend the tablet or real-model claims. A separate
regression on the same lane covered a stopped task's existing **Continue** path after a new-task
`need_user` pause: one fixed wake-up, same-session fixture input and an independent target read-back;
the new no-task resume does not replace it. A separate API 37/arm64 tablet follow-up covered direct input, overlay
handoff/continuation, capture and consecutive task ownership; it does not cover these newer features.
Broad OEM behavior, TalkBack, API 37 new-feature behaviour and real-model reasoning quality are not
established by that evidence. No new device instrumentation was executed for the paused-recovery
follow-up; its regression used the synthetic fixture.

A later API 35/x86_64 independent-App follow-up covered static idle, bounded busy (6 s), sustained
busy (30 s) and a forced read fault: the normal groups started, tapped and read back with `Save`
exactly once and the connection connected; sustained busy returned `HIERARCHY_NOT_IDLE` /
`OBSERVATION` with no grant/task and the connection connected after one terminal attempt; the forced
fault returned `READ_TIMEOUT` / `OBSERVATION` with a logical disconnect and no grant/task/`Save`
change. These are API 35 emulator results with the deterministic protocol fixture, not
tablet/physical-device/real-model/OEM evidence. The fault host was an isolated emulator-only
negative, not port-discovery or real-device cause evidence. Full-suite totals are owned by the
separate full-suite validation and are not restated here.
