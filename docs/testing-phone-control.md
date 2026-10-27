# Local phone-control emulator acceptance

## Scope and result

The default-off phone-control experiment passed the covered API 35 / x86_64 emulator flows. The
tests used a separate application ID, an unlocked test device, the synthetic
`io.example.phonefixture` app, and synthetic tasks. No physical phone, real account, paid model API
or user project was used.

The tool-loop test used real guest OpenChamber 2.4.0 and OpenCode 2.0.32, the packaged
`andchamber_phone` plugin, the native controller and the App's TLS ADB client. Its model provider was
a deterministic local protocol fixture. This establishes real tool execution, configure-once
standing admission, native continuation and visible device effects, **not** model reasoning quality
or support for arbitrary third-party business workflows.

The emulator's original image did not enforce ADB authentication. Acceptance enabled
`ro.adb.secure=1` on this test AVD and required a fresh unpaired client to fail TLS authentication.
Host adb connectivity was never substituted for App-side pairing proof.

### New API 35 / x86_64 follow-up

These follow-up runs used the same covered API 35 / x86_64 emulator and a fresh debug application
identity. They are separate evidence: they do not upgrade the earlier tablet/base-flow results and
were not repeated on API 37, arm64 or an OEM device.

| Claim | Evidence | Result |
|---|---|---|
| Fresh independent port probe needs no runtime and no saved receipt | Isolated debug package with no phone runtime and no pairing receipt. The test operator read the six-digit code from the open system pairing dialog and submitted it through SystemUI RemoteInput; the App did not read the code from the screen, and no port was entered into its UI. App NSD/foreground discovery completed the real pair, then fresh discovery supplied the TLS connection endpoint. | Passed |
| An existing receipt still works | Separate **Find and connect** flow using an existing receipt reached the same TLS connection. | Passed |
| Permanent scope and per-task limit survive a confirmed restart | Both permanent selectors were explicitly selected and confirmed. After saving and confirming a runtime restart, the scope remained `ScopePermanent` / `EachTaskNoLimit`; real tool status reported `leasePermanent=true` and `leaseRemainingMs=null`. | Passed |
| A permanent lease does not exempt the stop path | Real overlay Takeover produced `NO_GRANT` and `standingBlocked=TAKEOVER`; the permanent lease did not bypass revoke. | Passed |

The builtin-question and plain-notice follow-up is recorded in
[the overlay verification](testing-phone-overlay.md). The matrix and tablet section below are the
earlier base-flow evidence. Do not combine them to claim the new pairing, permanent, question or
notice features on the tablet.

### Paused standing recovery (API 35 / x86_64)

Release follow-up automation passed 1,970 JVM tests (no failures, errors or skips), 359 Node tests
(358 passed and one existing skip), 72 Python tests, Android lint and debug/AndroidTest APK packaging.
Lint retained existing warnings/information. No new device instrumentation was executed.

This follow-up used the covered API 35 / x86_64 emulator, a dedicated debug application identity, the
synthetic fixture target and a permanent, fixture-only standing scope. It is separate evidence: it
does not upgrade the earlier tablet/base-flow results, and it was not run on API 37, arm64 or an OEM
device.

| Claim | Evidence | Result |
|---|---|---|
| The reported refusal reproduces on the old build | Save one permanent, fixture-only scope; finish the task; on Home stop and start the local runtime; close the Ended result card; **Find and connect** again. The native screen showed Ready with no task, while the status still carried `standingConfigured=true`, `runtimeReady=true`, `standingBlocked=true` and reason `RUNTIME_CHANGED`; a fresh chat request was refused as `STANDING_BLOCKED`. | Reproduced before the fix |
| A blocked no-task scope is honestly paused | The same path now shows **Paused** / **Runtime changed** instead of Ready, and the chat cannot start from the scope. | Passed |
| Cancel is a no-op | Opening the resume dialog and cancelling left the standing block and the task epoch unchanged, with no grant and no task. | Passed |
| Confirmed resume clears only the pause | Confirming **Resume chat use…** returned the native screen to Ready with no task: `standingBlocked=false`, reason null, phase `NO_GRANT`, `taskPresent=false`, and the same epoch. The chat message count and the fixture transcript count were unchanged, so no chat prompt was sent. | Passed |
| The saved scope is preserved | The persisted `standing.v1` configuration was exactly equal before and after, including the permanent window and permanent per-task choice. The resume itself did not change the current same-runtime/session binding; the saved revision and deadline were unchanged. | Passed |
| A fresh request still works normally | After the resume, a new real request started inside the saved scope as `RUNNING`; the target was actually tapped and read back, and its Save counter increased exactly once. | Passed |
| Fail-closed and no automatic clearing | The gate requires no task, halt, grant, retained task, lease or task identity and no in-flight/pausing/unknown state before it calls the person resume. Unknown outcome, missing/inactive/revoked scope and a different-runtime binding are refused. Opening the screen, reconnecting or RPC readiness never clears the pause. | Passed by focused JVM policy/controller tests |
| Mapper reasons are exhaustive and truthful | All six native reasons map to the matching screen reason; a lost reason while blocked is shown as `OutcomeUnknown` and is not resumable. A scope with no block is never marked paused merely because it is idle. | Passed by mapper tests |
| Existing stopped tasks keep their own path | After the resume, a new task called `need_user` and the native screen showed Paused / Need user. The original **Continue beside the chat…** entry stayed enabled; a real tap of that confirmation sent the original native fixed wake-up exactly once, the same chat session received the fixture input, and an independent read-back observed `standing-resume-check` in the target. The new scope resume did not replace this retained-task path, and no continuation prompt was sent by hand. | Passed in a separate regression on the same lane |

The paused-recovery path adds focused JVM coverage and the API 35 probe above; it does not restate
full-suite totals, which are owned by the separate full-suite validation. No new device
instrumentation was executed in this round. The stopped-task **Continue** regression is separate
evidence on the same lane and does not widen the API 37/arm64 or OEM claims.

### Startup observation and read-failure follow-up (API 35 / x86_64)

This follow-up used the real App on the covered API 35 / x86_64 emulator with a separate debug
application identity, the synthetic fixture target and the deterministic local protocol fixture. It
is API 35 emulator evidence only; it is not API 37/arm64 tablet evidence, a physical-device result,
real-model reasoning or a general OEM compatibility claim. The fault host for the negative case was
an isolated emulator-only setup: it is not evidence about App port discovery or a real device's
cause. No new device instrumentation was executed.

Final follow-up checks passed: 2,035 JVM tests (no failures, errors or skips), 360 Node tests
(359 passed and one existing skip), 72 Python tests, Android lint and debug/AndroidTest APK packaging.
Lint retained existing warnings/information.

Mechanism note: the official `uiautomator dump` command calls
`UiAutomation.waitForIdle(1000, 1000 * 10)` and prints the fixed sentence
`ERROR: could not get idle state.` when that bound expires
([AOSP API 35 `DumpCommand.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)).
The earlier 5-second startup observation could exhaust the App's hard read watchdog on a busy target;
the watchdog closed the local read connection and the next observation reported `NOT_CONNECTED`. A
temporary fixed-code observation probe (since removed) recorded that transition. The current startup
observation uses one shared 15-second maximum (overlay wait plus real remaining) and retries only
explicit window transitions, so the same busy page is reported as `HIERARCHY_NOT_IDLE` with the
connection still connected. This explains the observed emulator transition; it does not claim a real
user's field cause or exclude every OEM difference.

| Claim | Evidence | Result |
|---|---|---|
| Static idle start | Normal request, tap and independent read-back; `Save` increased exactly once; the status remained connected. | Passed |
| Bounded busy target (6 s) | The same normal start/tap/read-back completed with `Save` exactly once and connected status. The complete tool scenario took 19.655 s because it also ran the later snapshot/tap/read-back; that is not the observation budget, which remains the shared 15 s. | Passed |
| Sustained busy target (30 s) | The request returned at 13.27 s with `HIERARCHY_NOT_IDLE` and `data.startFailureStage=OBSERVATION`; no grant and no task, while the connection stayed connected. Exactly one terminal attempt was made; focused loop tests confirm there is no blind retry. | Passed |
| Forced read fault | `READ_TIMEOUT` / `OBSERVATION`; status `connected=false`, phase `NO_GRANT`, grant count 0, `taskPresent=false`, `Save` unchanged. The dedicated fault rule and its temporary fixed-code logging were removed. | Passed |

The 6-second and 30-second labels describe the busy fixture's behaviour window, not an observation
budget of 30 s or 19.655 s. A sustained busy page still does not expose a usable hierarchy for agent
operations, so it remains an honest typed refusal rather than full UI support.

Typed classification and retained behavior:

- The hierarchy idle failure is only the whole trimmed fixed sentence. Valid XML containing the same
  words is still parsed; partial/invalid/unknown output stays a driver failure, and the XML
  parser/window checks are unchanged.
- `mCurrentFocus=null` / `mFocusedApp=null` is `NOT_READY` only for a complete official field line
  with the matching window missing, and maps to `WINDOW_CHANGED` inside the bounded startup retry.
  Missing, truncated, oversized, malformed or unknown output remains fail-closed; no partial window
  or target nodes are used.
- `READ_TIMEOUT` covers a read-service transaction (`open` or `read`) whose watchdog abandoned the
  exact connection. The core publishes the logical disconnect/revoke in the same call, keeps the
  first error, preserves an already-recorded unknown outcome, and fences newer connections. It does
  not claim the physical close has completed.
- The hard stream+connection watchdog is retained (no stream-only keepalive, no added total time).
  The outer bound is the fresh remaining plan deadline; the driver's ≤500 ms margin is a reporting
  opportunity, not a guarantee, so an outer win can still return `DEADLINE`. A monotonic
  completion/expiry gate prevents a late finish from disarming an already-expired watchdog.
- Mutation `afterWrite`/touched `UNKNOWN` and no-replay behavior are unchanged; cancel after a grant
  rolls back exactly that epoch/task and propagates the original cancellation, leaving a newer owner
  unaffected. The existing scope and person-resume paths are unchanged.

## Evidence matrix

| Claim | Evidence | Result |
|---|---|---|
| Off by default; no grant without native configuration and confirmation | Real native entry/risk-refusal instrumentation | Passed |
| First pairing stays inside the system dialog | Actual SystemUI RemoteInput: port, then code; dialog remained open after the first reply | Passed |
| Wrong code fails; correct code pairs; unpaired identity cannot connect | App-side production pairing/TLS probe with authentication enabled | Passed |
| Pairing survives an App process/package restart | AndroidKeyStore identity + private receipt restored; explicit TLS connection without another pairing | Passed |
| First configure-once flow becomes ready | Old runtime without the phone plugin → enable and save the standing scope → confirmed restart → `RPC output.ok=true / id=andchamber_phone` → Ready | Passed |
| Direct begin starts without per-task pending confirmation | Real OpenCode request; native standing starter; `RUNNING`/`READY` with a fresh epoch (the `need_user` begin returned `RUNNING`); no per-task `PENDING_CONFIRMATION` | Passed |
| Direct input is independently observed | Real model-protocol loop and independent non-editable target echo witness for `andchamber-ascii-1` | Passed |
| Preauthorized app opening is bounded and observed | `open_app` returned `OPENED`; independent observation confirmed the same `io.example.phonefixture` foreground; no arbitrary package switch | Passed |
| Reading local chat keeps the task usable | Opening the local chat kept the lease READY; no host-return revoke on ordinary chat | Passed |
| Native overlay reflects the stop | Overlay displayed **Your turn** / **Agent access revoked** instead of a business-success or takeover claim | Passed |
| Native continuation wakes the original session once | After the person acted, exactly one fixed prompt appeared in the original session; re-observation and input verification passed; no acceptance-script continuation prompt was sent | Passed |
| First overlay read waits for the current surface | From AndChamber, the first read required the bounded current-epoch surface wait and then passed | Passed |
| Another ordinary chat cannot take a live task | Second real OpenCode session received `SESSION_BUSY`; routing is not authentication | Passed |
| Pause followed by return to the native page revokes observation | Actual notification Pause; return to native UI; snapshot refused with the connection still alive | Passed |
| Manual takeover does not restore observation | Native takeover opened the remembered target; snapshot remained refused | Passed |
| Android 15 notification Return opens the native screen | Actual SystemUI action through the nonce/epoch-validating Activity | Passed |
| Notification entry gate is checked | AppOp, permission and channel gate checked before native review/start; the acceptance check began with **no live lease**, so this is not an active-lease permission-revoke claim | Passed for the no-lease gate; active-lease revoke not tested |
| Runtime restart is not control recovery | Native Stop/Start, explicit device reconnect, then `NO_GRANT` / snapshot refusal | Passed |
| Unknown outcomes are not replayed | In-flight swipe interrupted by wireless-debugging loss: `OUTCOME_UNKNOWN`, native block, duplicate request refused | Passed |
| Unknown recovery requires human review | Reconnected without re-pairing; target state unchanged; user check and another native confirmation required | Passed |
| Screen-off revokes control | Screen-off then wake left no grant; observation remained refused | Passed; no credential-based keyguard was used |
| Old refs and duplicate calls are refused | Real authenticated bridge checks; display resize rejected the old geometry; fresh-observation reuse of a call ID did not repeat a mutation | Passed |
| Unsupported Unicode is not falsely reported as entered | Real bridge returned `UNICODE_UNSUPPORTED` before dispatch | Passed |
| Password handling | Driver tests reject password input; actual captured fixture password was masked | Passed within this scope |
| Native UI readability | Normal/large font, narrow width, Chinese and landscape checks; static screenshot review | Passed for covered screens |
| Soft keyboard | Actual numeric keyboard; port field and Connect above it; successful connection then keyboard dismissal/top reset | Passed |
| End task does not manufacture business success | Native End task retained an unconfirmed result/check path | Passed |

Negative protocol tests used the authenticated native bridge directly where appropriate. They are
separate from, and do not replace, the real OpenCode tool-loop evidence above.

## Automated checks

### Physical tablet follow-up (earlier base flow)

On an Android 17 / API 37 arm64 tablet, a dedicated test package also completed native TLS
connection, chat-started ASCII input with an independent target echo, overlay handoff, native
continuation with exactly one prompt in the original session, return-to-chat without revocation,
same-target `open_app`, a 3200 × 2136 PNG capture, and a subsequent task with an independently
observed Save counter increase. The provider was the deterministic local fixture, not a real-model
reasoning test. Earlier continuation attempts were inconclusive; the clean follow-up build passed.

This section is the earlier API 37/arm64 base-flow evidence. The fresh no-receipt pairing,
permanent-scope and question/plain-notice follow-ups above and in the overlay document were not run
on this tablet, so they must not be called tablet-passed.

The tablet's launcher required a separate, person-approved cross-app launch permission. Its pairing
notification exposed input through a long press; a collapsed notification was not evidence that
RemoteInput was unsupported. Overlay-and-notification mode required the demo tap and visibility
confirmation before it could be selected.

The follow-up found a first-read handover race: the controller already owned the new epoch while the
Main-thread overlay projection still carried the previous task. The bounded wait now waits for the
exact current display owner instead of treating that projection lag as a stale lease. A changed live
epoch still fails immediately, and no old display is accepted as ready for the new task. Regression
tests cover both cases. This is evidence for the covered tablet flows, not general OEM compatibility,
long-duration survival, TalkBack, or active permission-revocation coverage.

### Emulator acceptance automation

- JVM: 1,947 tests, no failures, errors or skips.
- Node: 359 tests total: 358 passed, one existing stock-ChatContainer fixture skipped, no failures.
- Python: 72 tests passed.
- After the full run, a wording-only cleanup passed all 12 `PhoneStringsTest` cases and a debug APK
  rebuild. Setup text now acknowledges Permanent rather than promising only short tasks.
- `lintDebug`, debug APK and AndroidTest APK builds passed. Lint retained warnings/information;
  this is not a claim of zero lint findings.
- No new device instrumentation was executed in this round. The earlier 20/20 smoke result is
  historical base-flow evidence only; it is not a new test result and does not cover the new
  fresh-pairing, permanent-scope or question/notice follow-ups. The separate API 35 fixture/emulator
  lane carries the new evidence; the smoke suite does not stand in for pairing or the full tool loop.
- The paused-standing follow-up adds focused JVM coverage for the mapper's six reasons/unknown
  combinations, the resume gate's independent refusals, and the real controller resume clearing the
  standing block plus ordinary pause without a grant or replay. Full-suite totals are not restated
  here; they belong to the separate full-suite validation.
- The startup/read-failure follow-up adds focused JVM coverage for the shared observation loop's
  retry classification, whole-trim hierarchy idle output, null focus/app typing and fail-closed
  malformed cases, read-timeout logical disconnect and connection-owner fencing, the fresh-remaining
  budget with its reporting-only margin, the monotonic completion gate, and unchanged mutation
  unknown/no-replay. It also adds no new device instrumentation. Full-suite totals are owned by the
  separate full-suite validation.

## Important regressions retained

- Fresh pairing works without a pre-existing runtime or receipt: the test operator enters the system
  dialog's six-digit code through RemoteInput, the App never receives a typed port, and NSD/foreground
  discovery follows the pair. An existing receipt's **Find and connect** is a separate path.
- A permanent standing scope or permanent per-task limit is not a stop exemption. A real overlay
  Takeover ends in `NO_GRANT` and `standingBlocked=TAKEOVER`.
- A standing block with no task is presented as paused, never Ready. The person resume is gated on
  current runtime/tool readiness, verified stop entry, an active non-revoked scope, no task/mutation
  and no unchecked unknown; it keeps the same runtime/session binding and the exact saved scope,
  deadline, revision and store, and creates no grant or chat prompt. Opening the screen, reconnecting
  or RPC readiness never clears it.
- A stopped task still uses the existing **Continue** entry and base readiness; the new resume entry
  is not a second continuation path. This was rechecked after a new-task `need_user` pause: **Continue**
  stayed enabled, sent the original fixed wake-up once, the same session produced the fixture input
  and an independent target read-back observed it. A different-runtime binding fails closed rather
  than rebinding.
- Startup observation has one shared 15-second budget (overlay wait plus real remaining), and only
  explicit window/geometry/target transitions retry. A busy page returns `HIERARCHY_NOT_IDLE`, not a
  blind retry loop, and the connection remains usable.
- A hierarchy dump is `HIERARCHY_NOT_IDLE` only when the whole trimmed output is the fixed AOSP
  sentence; a valid XML label containing the same words is not misclassified.
- A null official window field (`mCurrentFocus=null`/`mFocusedApp=null`) is `NOT_READY` only for a
  complete field line with its window match missing and becomes `WINDOW_CHANGED`; malformed,
  truncated, oversized or unknown output remains fail-closed and produces no partial window/nodes.
- `READ_TIMEOUT` is a read-service transaction (`open` or `read`) that abandoned the exact
  connection. The same call publishes the logical disconnect/revoke and keeps the first error;
  unknown is preserved, a newer reconnect is fenced, the hard watchdog remains, and no physical
  close completion is claimed.
- A late finish cannot disarm an expired watchdog: completion is checked against the monotonic
  deadline. The driver margin is at most 500 ms and is reporting-only; an outer win can still be
  `DEADLINE`.
- An `afterWrite`/touched mutation remains `UNKNOWN` and is never replayed; cancel after a grant
  rolls back exactly that epoch/task and propagates the original cancellation.
- LibADB 3.1.1 can wait forever after queued data followed by CLSE. The local reader drains its
  buffer/queue before EOF; the test exercises the shipped SDK's hang and the replacement's success.
- A failed async driver child must not cancel the HTTP parent instead of returning its structured
  error. Controller tests exercise the former `coroutineScope` failure and `supervisorScope` fix.
- Old requests keep their admitted epoch; a new grant cannot make an old request valid.
- The first valid standing request binds the saved scope to the current runtime and session. Later
  requests must match both; every task gets a fresh epoch/lease. A second session is refused.
- Production readiness is the bounded native RPC registration probe after the plugin transform and
  tool list. A late/old result, an endpoint file, a listening server or a new launch cannot mark the
  current launch ready; stop/new launch cancels the wait.
- Ordinary local chat does not revoke; entering the real native phone page applies the host-return
  stop. Notification/overlay Return remain explicit revoke paths.
- A missing overlay surface makes the first read wait on the bounded current-epoch attach and then
  fail/roll back exactly that start or continuation. Overlay loss is reported as a stop-channel or
  visibility failure, not a person takeover.
- Native continuation reuses the retained task/session/runtime, creates a fresh epoch, re-observes
  and sends the fixed wake-up prompt once. A failed send is not retried and does not roll back an
  established task.
- One Proot server instance samples phone opt-in per launch; its launch identity stays monotonic.
- Launcher discovery does not require `CATEGORY_DEFAULT`, which ordinary launcher filters omit.
- Notification Return uses an Activity PendingIntent, not a forbidden service-to-Activity trampoline.
- Notification checks include self-UID AppOps, permission and channel state. API acceptance is not
  notification delivery or user acknowledgement.
- Fixed native insets, step scroll/focus reset and an IME-sized scroll viewport keep controls visible.

## Reproduction and limitations

See [the fixture instructions](phone-e2e-fixtures.md) and [the feature boundaries](phone-control.md).
Always choose an explicitly named emulator and a dedicated debug application ID. Do not run native
instrumentation while a live runtime/control experiment is active: the runner may finish target
activities or terminate its process.

For the new API 35 follow-up, start with a fresh debug application identity that has no runtime and
no pairing receipt. Use the real system pairing dialog: read its six-digit code and submit it through
SystemUI RemoteInput; do not type the port into the App UI. Let App NSD/foreground discovery perform
the pair, then use a fresh NSD connection and TLS connect. Repeat with an existing receipt through
**Find and connect**. Select both permanent choices explicitly, save, confirm the runtime restart,
and check `leasePermanent=true` / `leaseRemainingMs=null`; then use real Overlay Takeover and verify
`NO_GRANT` plus `standingBlocked=TAKEOVER`. The builtin-question and plain-notice steps are in
[the overlay verification](testing-phone-overlay.md).

For the paused-standing follow-up, use the same API 35 / x86_64 test identity and a permanent,
fixture-only scope. Save once, finish the task, stop and start the local runtime from Home, close the
Ended result card, then use **Find and connect**. After the fix the native screen must show Paused /
Runtime changed rather than Ready. Cancelling the resume dialog must leave the block and epoch
unchanged. Confirming it must clear only the standing block and ordinary pause: the status must show
no block, no grant and no task, the same epoch, an unchanged `standing.v1` configuration and no new
chat message or fixture transcript entry. A fresh request must then start as `RUNNING` with a real
tap/read-back. This path is API 35 / x86_64 only; it is not tablet evidence. The retained-task
**Continue** regression is recorded as a separate check on the same lane and must not be merged into
the no-task resume path.

The physical tablet follow-up is limited to the cases above; no general OEM background-lifetime,
Android 11-wide compatibility, TalkBack or long-duration survival claim is made. The input backend supports only its declared ASCII subset, not Unicode,
passwords or automatic submission. The target-package policy is not an OS sandbox, and the whole
local runtime must remain trusted. Window checks are conservative before/after checks, not an atomic
transaction with another Android app. No actual payment, account operation or externally
consequential task was performed, and no business-success claim is made.

The new follow-up is API 35 / x86_64 only. It does not establish API 37/arm64 tablet, OEM,
TalkBack, real-model reasoning or active-lease permission-revocation behavior. The retained tablet
section is earlier base-flow evidence, not evidence for the new features.

The startup/read-failure follow-up also used only the covered API 35 / x86_64 emulator and a separate
debug application identity. The fault host was an isolated emulator-only negative; it is not App
port discovery evidence and does not identify a real device's or user's cause. A real user's field
typed failure would still be required to judge that environment. Do not copy private paths, ports,
UIDs, root rules or screenshot/log content into this repository. A sustained busy page still does not
provide a usable hierarchy, so the typed refusal must not be described as full UI support. Full-suite
totals are owned by the separate full-suite validation and are not restated here.

The notification permission/AppOp check began with no lease; the run did not revoke permission
during an active lease. That distinction matters: the no-lease gate is verified, active-lease
permission revocation is not.

Raw screenshots, authentication state, pairing details and traces remain outside this public
repository. Public fixtures use synthetic content; no private host, device identifier or account is
needed to reproduce the checks.
