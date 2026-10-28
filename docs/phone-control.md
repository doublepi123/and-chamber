# Experimental local phone control

Phone control is an opt-in Android 11+ experiment. It uses the existing on-device OpenCode runtime,
an App-injected `andchamber_phone` plugin, and a native wireless-debugging client. It is not remote
device control and does not change the embedded browser's foreground-only authorization rules.

The covered API 35 / x86_64 emulator flows passed [integrated acceptance](testing-phone-control.md),
including a direct chat request, preauthorized app opening, native continuation and the first
overlay-read wait. Do not treat a fixture script or a successful APK build alone as full product
verification. A separate API 37/arm64 tablet follow-up covered the cases recorded in the acceptance
document; broad vendor background policies, TalkBack and real-model reasoning remain unverified.

The newer auto-port pairing/connect discovery, permanent standing durations and notification-question
overlay projection were validated only on the API 35 / x86_64 emulator. The earlier API 37/arm64
tablet follow-up predates these features and does not cover them; do not widen its claims from this
document.

## Trust and scope

- Off by default. The native phone-control screen explains the risks before enabling it.
- The whole local runtime, including guest subprocesses and installed plugins, must be trusted.
  PRoot and ordinary private files do not isolate a malicious same-UID guest. Session labels route
  calls; they are not a separate authentication boundary.
- Wireless debugging authorizes a device-level ADB identity. Restricting the tool to a native
  preauthorized app set is an admission policy, not an Android permission sandbox.
- The person saves one bounded **standing scope**: allowed apps, allowed actions, a window and a
  per-task duration. Both duration selectors offer an explicit **Permanent** choice; the finite
  defaults are unchanged. This is configuration, not a lease. The first valid chat request binds it
  to the current runtime and session. Each task then gets a new controller epoch and a lease bounded
  by the per-task duration and the remaining window. A permanent per-task choice inside a finite
  window is still bounded by that window's end.
- Only apps in that preauthorized set are openable. There is no arbitrary intent, shell, URI or
  package-install surface, and the installed-app catalogue is never handed to the model. The host,
  system UI, settings and system authorization surfaces cannot be selected for agent control.
- Pairing keys stay on the native side. Pairing codes are transient, are not sent through chat, and
  are not included in notification history or logs by this app. Deleting local state does not revoke
  authorization in Android's Wireless debugging settings.
- An authorized observation may reach the current model provider. Local execution does not mean
  that screen data stays on the phone. Do not use this experiment with untrusted projects or plugins.

## Setup and control

1. Open **Phone control** from Home and explicitly enable the experiment.
2. If the local runtime was already running without the phone plugin, return Home and restart it
   yourself. Restarting interrupts current OpenChamber tasks; enabling the switch never restarts it
   silently. The person confirms the restart on the standing card.
3. Follow Android's Wireless debugging setup. The main path no longer asks for two ports. While the
   system settings screens are open, a bounded native discovery service searches Android's official
   `_adb-tls-pairing._tcp` type, accepts only an endpoint whose numeric address is loopback or this
   phone's own interface address, and confirms that the single resolved port is really listening by
   binding `127.0.0.1:port` (only `EADDRINUSE` counts; a successful bind or another error discards the
   advertisement). The notification then asks only for the six-digit code; pairing starts against the
   discovered endpoint. After a real pairing success, a fresh bounded connect discovery finds the
   current `_adb-tls-connect._tcp` endpoint, probes it the same way, and performs one automatic TLS
   connect. An already-paired person can use **Find and connect** for the same bounded discovery.
   The old typed host/port path is kept only as a manual fallback. Cancel, completion or the
   five-minute bound stops the service, NSD and the short-lived API 30–33 multicast lock. On Android
   13 with T extension SDK 7 (and newer) an advertisement's port updates are followed with
   `registerServiceInfoCallback`; otherwise the bounded serial legacy resolve is used. Queries use
   the service type without a trailing dot and normalise a leading dot the platform may report, while
   accepting only those two exact types. Nothing scans ports or reads shell properties, and the
   feature adds no nearby-device or location runtime permission.
4. Save the standing scope: apps, action classes, window and per-task duration. Saving does not
   grant or launch anything, and it works before the running launcher has loaded the plugin.
5. Once the new runtime is actually running and the tool handshake is ready, the first valid chat
   request binds the scope to that runtime and chat session. Later requests must use the same runtime
   and session; another session receives a mismatch refusal. Every task has a fresh epoch.
6. In chat, `request` starts directly inside the scope with a bounded summary and one preauthorized
   package. The native side checks the scope, proves a foreground stop entry, creates the task/lease,
   launches that package and performs a real foreground observation before replying
   `RUNNING`/`READY` with the epoch. There is no per-task `PENDING_CONFIRMATION` in this mode.
7. `apps` lists only the person-preauthorized labels/packages. `open_app` brings the current live
   task's same target back to the foreground and proves the observation; it cannot switch to another
   app or create a new epoch.

The legacy per-task native grant form remains in the controller for compatibility, but the default
UI is configure-once standing. A chat proposal/request is never authority by itself.

Permanent durations use one explicit `PhoneTimeLimits.PERMANENT` sentinel inside the native layer;
they are never produced by adding a large duration to a clock. The tool protocol exposes
`leasePermanent` with no lease countdown (`leaseRemainingMs = null`) instead of a huge deadline. A
permanent standing scope is still stored as configuration only: after a reboot or process restart
there is no restored grant, observation or task route. The first valid chat request still binds the
configured scope, and every person stop, screen lock, disconnect, runtime change and process boundary
keeps its existing stop behaviour.

Foreground-service readiness is checked before a task route is published and the target is launched.
Notification permission/channel checks and a successful `startForeground` call are not proof that the
person saw a notification. Production tool readiness is not the old setup `/v1/ready` call alone: the
plugin registers a read-only `andchamber-phone/ready` RPC after its tool transform and tool-list
confirmation, and a bounded native probe must see
`{"output":{"ok":true,"id":"andchamber_phone"}}` for that exact launch. A merely listening server,
an endpoint file or a new launch never inherits readiness.

## Stop, blockers and continuation

Returning to ordinary local chat does **not** revoke. Entering the real native phone page applies the
host-return stop; notification Return and overlay Return remain explicit revoke actions.

`need_user`, takeover, unknown outcome, screen lock, connection loss and runtime change set a standing
block. A chat request, renewal or reconnect cannot bypass that block. A native person confirmation may
continue the same retained task/session/runtime: the controller creates a fresh epoch, the target is
observed again, and exactly one fixed wake-up prompt is sent through the launch-guarded local API to
the original chat session. The prompt asks the agent to observe again and not repeat an old action.
A failed wake-up is not retried and does not roll back an already established task; it is not proof of
business success. There is no automatic restart after process death.

A standing block with no retained task is shown as a **pause**, not as Ready. The screen mapper
projects only explicit controller facts: all six native blocking reasons map to one screen reason, a
blocked state whose reason was lost fails closed as `OutcomeUnknown`, and an ordinary paused lease
alone never becomes a standing pause. An unchecked unknown outcome, or a "may still apply" halt the
person has not acknowledged, keeps the pause non-resumable.

The paused card's **Resume chat use…** entry is a separate person-confirmed native action, never a
reconnect or screen-open side effect. Before it touches the controller it re-checks the entire state:
the experiment and controller are enabled; the saved scope is still active, not revoked or expired;
the current runtime identity is present, running and tool-ready; the device link is connected; the
foreground stop entry is actually verified; no native operation, continuation send, mutation or pause
is settling; and there is no live/retained task, lease, halt or task identity. A binding that names a
different runtime is refused instead of being silently rebound. The same
`resumeStandingForPerson` primitive then clears the standing block and the ordinary pause under the
controller lock; the saved scope, deadline, revision, store, same-runtime/session binding and isolation
are unchanged. The entry creates no grant and sends no chat wake-up. A fresh request still passes the
full standing policy and binds normally.

A stopped task that still has its halt/lease/task identity does not use this entry: it continues
through the existing continuation path and its own base readiness, so the pause caused by its own
stop does not lock that path. Opening the native screen, reconnecting or the tool becoming ready
never clears a pause. An unknown outcome, a missing/revoked/inactive scope, or a lost reason stays
fail closed; no new acknowledgement shortcuts this entry into resuming.

### Handoff state flow

- **Normal:** one live grant/epoch and an ACTIVE task. No handoff state is present.
- **Transient wait:** an ordinary busy read returns `HIERARCHY_NOT_IDLE` without revoking a live
  grant. The agent may wait and make a bounded number of fresh read-only observations, each within
  its existing call limit. Startup still treats this as terminal within its shared budget. The live
  task remains READY/ACTIVE on the same epoch and does not become a person handoff by itself.
  An empty hierarchy dump is treated the same way. On a real API 37 phone, a dump that overlaps an
  earlier on-device dump (for example one whose read was abandoned) fails with UiAutomation
  "already registered" and prints nothing; the driver re-reads it a bounded number of times
  within the same call deadline before reporting `HIERARCHY_NOT_IDLE`, never `DRIVER_UNAVAILABLE`.
- **Manual need_user:** the native halt is `NeedUser`, separate from `TakenOver`, with the
  `NEED_USER` standing block and no live grant. The original task stays retained
  (`taskRetained=true`, `taskState=WAITING_USER`) and is not presented as Ended. Overlay Continue is
  navigation only; the native person dialog is still required.
  Ordinary chat questions, answer choices and OpenCode permission replies do not by themselves
  require this phone handoff; a genuinely manual operation on the phone still does.
- **Retry NotResumed:** a failure before any wake attempt and before a recovery grant, or after an
  exact-owner rollback confirmed that no recovery grant remains, records
  `continuationState=NOT_RESUMED` plus one fixed failure reason. The same task key/history and the
  latest attempted epoch/native stop receipt are kept even when the store projects STOPPING. A new
  person confirmation may create a fresh grant and Observe; this is not a replay of old mutations.
- **Delivered / Failed wake:** only after native recovery is established is exactly one fixed
  wake-up sent. `DELIVERED` means the API accepted that prompt, not that the business task succeeded.
  `FAILED` means the one wake was attempted but not confirmed;
  it is never sent again and the live grant remains. It is never treated as retryable merely because
  no grant happens to be live.
- **Explicit End:** native End records `ENDED_PENDING_CHECK` and keeps the needs-check evidence;
  dismissing the result clears the retained task. Old epoch actions and events remain rejected.

The read-only status surface adds `taskRetained`, `taskState`, `continuationState` and the fixed
`continuationFailure` reason alongside the existing `taskPresent`. `taskPresent` still means a live current
epoch; the new fields come from the same bound task store and screen facts and never grant, resume,
or widen authority. No tool input action/schema, permission or overlay grant is added.

Stopping does not undo a dispatched operation. If an outcome is unknown, check the target app
before deciding what to do next; the app does not replay it automatically. A tool acknowledgement
does not prove that the person's business task completed. Task results remain unconfirmed without
separate, relevant evidence.

Pause stops new changes; authorized observation can still help inspect an uncertain result. Use
**I'll take over**, return to the native phone page, or end the task before entering sensitive
information: these revoke the whole lease, including observation. A model's final sentence is not a
  native control-release acknowledgement; use the native controls, or expiry for a finite lease.

## Observation startup and fixed read failures

A start or continuation observes the target inside one shared call budget: at most 15 seconds,
including any overlay-surface wait and the real remaining time. The budget is never restarted per
attempt, and only explicit window/geometry/target transitions are retried. A busy hierarchy, a read
timeout, a security refusal and every other driver failure stop with their first typed error.

The official `uiautomator dump` command waits with a 1-second quiet window and a 10-second global
bound, and prints the fixed sentence `ERROR: could not get idle state.` when that bound expires
([AOSP API 35 `DumpCommand.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java)).
An earlier 5-second startup observation could therefore exhaust its own watchdog on a busy target;
that watchdog hard-closes the local read connection, and a later observation reported `NOT_CONNECTED`.
The current shared 15-second budget reports the same busy page as `HIERARCHY_NOT_IDLE` while the
connection remains usable. This explains the observed emulator transition; it does not establish a
real user's field cause or exclude every OEM difference.

- `HIERARCHY_NOT_IDLE` only when the whole trimmed hierarchy output is exactly that fixed sentence.
  A valid XML page that merely contains the words is still parsed normally, and partial/invalid/
  unknown output stays a fixed driver failure. The XML parser, node caps and window checks are
  unchanged.
- `READ_TIMEOUT` means a read-only ADB service transaction (`open` or `read`) was abandoned because
  its watchdog expired. The exact connection is no longer usable for control; the core publishes the
  logical disconnect and revokes in the same call, keeps a first `READ_TIMEOUT`, preserves an
  already-recorded unknown outcome, and fences the revoke to that exact connection so a newer
  reconnect survives. It does not claim that a physical socket close has completed.
- The existing hard watchdog still closes the stream and connection to unblock a blocking read. There
  is no stream-only keepalive and no extension of the total call bound. The outer bound is the fresh
  remaining plan deadline; the driver gets a margin of at most 500 ms below it only so its own typed
  failure can be reported first. That is a reporting opportunity, not a guarantee: if the outer
  bound wins, the caller gets an ordinary `DEADLINE` and no link claim is invented. A monotonic
  completion/expiry gate prevents a late watchdog from closing after a genuine pre-deadline finish.
- A transient window-manager state with a complete official `mCurrentFocus=null` or
  `mFocusedApp=null` line and no matching window is typed `NOT_READY` and mapped to
  `WINDOW_CHANGED`; it is retried only inside the shared startup budget. Missing fields, truncated
  or oversized output, bad packages/activities and unknown formats stay fail-closed. No partial
  window or target nodes are produced, and capture/mutation still verify the window on both sides: a
  pre-injection `null` is `NotSent(WINDOW_CHANGED)`, while a failure after a possible touch remains
  `UNKNOWN` and is never replayed.
- Mutation semantics are unchanged: an `afterWrite`/touched timeout or read failure remains `UNKNOWN`
  and is never automatically replayed. Cancel after a grant rolls back exactly that epoch/task and
  propagates the original cancellation; a newer owner is unaffected. The old scope and person-resume
  paths are unchanged.

A sustained busy page still does not provide a usable hierarchy for agent operations. The typed
failure is an honest refusal, not support for operating an animated or continuously changing page,
and must not be marketed as general UI support.

## Optional system progress overlay

**Progress over other apps** is a separate, default-off native setting. Notifications remain
available in both modes. A system draw-over permission does not grant device control.

1. Open the system permission screen yourself. Android 11+ may show an app list rather than opening
   this app's switch directly. Refusing it never prevents selecting **Notifications only**.
2. Return to AndChamber, show the clearly marked demo capsule, tap **Take over**, then confirm that
   you saw and pressed it. The demo grants no device lease. Permission or `addView` success alone is
   not visibility evidence; the demo only establishes reachability at that moment.
3. Explicitly choose **Overlay and notifications**. New tasks still use the native standing scope and
   its per-task epoch/lease; a running task is not resumed by enabling an overlay.

The draggable, edge-snapped capsule shows the control phase and dispatch count, not a percentage of
business completion. Tap its body for the bounded task panel. The confirmed summary is folded by
default. Events distinguish native observations/confirmations, dispatched actions and unverified
agent notes. No event means waiting for updates, not fabricated progress.

**Take over** revokes observation and changes while leaving the current target page open. **Pause
actions** in the panel stops new mutations but can still allow observation. Pending stop requests are
not displayed as confirmed. Already-sent device commands are never described as rolled back.
Continuing opens the native confirmation flow; there is no overlay grant or renewal action.

Before the first driver read for the current epoch, a selected overlay must be attached and its real
window witness available. The native side waits on a bounded current-epoch surface attach. A timeout
makes the start/continuation fail and rolls back exactly that lease; it is not replaced with a fixed
sleep or an unprotected read. Notification-only/no-coordinator paths keep the original driver path.

Overlay loss is reported as an honest stop-channel or visibility failure, never as a person takeover.
The exact lease is stopped and the retained task facts stay for native recovery. Normal read
hide/restore is a separate success path: it removes the surface temporarily, verifies absence,
restores the latest native state and waits for the first frame. A cancelled or timed-out restore
cannot silently leave a permanent hidden state or resurrect an older owner. Actual WindowManager
checks, not `FLAG_SECURE`, establish the supported exclusion behavior.

The tool has three additional closed actions, all requiring the published session route and explicit
current `epoch`: `progress(phase)`, `need_user(reason)` and `finish(summary)`. Their bounded text is
an unverified agent note, not native evidence. `need_user` revokes first, then displays the handoff;
`finish` ends control and saves a result needing review, not a verified business success. When the
model finishes on a clean live grant (no halt, no unknown block, nothing in flight), the native screen
shows only a light, dismissible "reported done, not verified" note instead of the full check card;
person ends, take-overs and uncertain stops keep the full card. A closed result record alone does not
count as a task owner for the post-restart standing resume. Ordinary
model prose has no such authority. Automatic detection of every login/sensitive page is not provided.

The display service follows the task/result rather than the lease and has bounded retention.
Collapse keeps following. Ending requires confirmation and native revocation; only then can the
result card be closed. Closing the overlay result does not delete the native needs-check record.

Driver checks bind actual window tokens/frames to the manager's owner and geometry generation.
Tap/input focus and the entire swipe segment must not intersect the overlay. Move the capsule and
obtain a fresh observation if it covers the intended target; the agent cannot operate its own control
surface. A declared overlay mode never silently becomes an unprotected notification-only operation.

Known permission/display loss fails closed without automatic resumption. Notifications are a recovery
path, not proof of delivery or acknowledgement. Window checks cannot detect every OEM/system hiding
behavior; unsupported/unreliable states are refused rather than presented as continuously visible.
The native tool-call budget remains bounded by the existing 15-second maximum and by the shorter
live lease; the extra window/focus checks do not extend authority.

See the [system-overlay verification](testing-phone-overlay.md) for production-window evidence and
the separate platform probe, regression cases and limitations.

## Task questions and notices in the overlay

The floating surface can also show the current task's OpenChamber question and plain notices. Its
only source is local runtime stdout `DesktopNotification` events; it does not poll the browser or a
remote server. `SessionPromptController` reads the pending form once for the event and again when the
answer surface is opened or refreshed, then fences one answer on the current native task, route
session, launch, form id, schema fingerprint and presentation generation.

Only a **Ready single-choice** question offers choices and **Send** in the overlay. A complex or
unsupported form falls back to opening the local chat; no generic form is synthesized in the floating
window. `Permission` and unknown notification kinds keep their original notification/entry path and
never become an overlay permission reply. An unknown send outcome is never automatically retried or
shown as answered. A 2xx/HTTP 204 response is delivery acknowledgement, not proof of business success.

Plain **Ready**, **Error**, **Goal** and **RestartInterrupted** notices are displayed with bounded
text and are independent of the question identity. Selecting an answer collapses the panel first so
it cannot keep covering the target. A failed delivery re-expands the panel read-only only when the
same task/epoch/launch and the same form/generation still own it; a different identity stays
collapsed and nothing is resent. Source boundaries are
`notify/SessionPromptProjection.kt` and `phone/android/overlay/PhoneTaskConversation.kt`.

## Implementation boundaries

| Area | Entry points |
|---|---|
| Native presentation | `ui/phone/PhoneControlScreen.kt`, `PhoneStandingCard.kt`, `PhoneUiModel.kt`, `PhoneUiLogic.kt` |
| Android coordination | `phone/android/PhoneIntegration.kt`, `PhoneControlService.kt`, `PhoneControlNotifications.kt` |
| Wireless-debugging discovery | `phone/android/PhoneDiscoveryService.kt`, `PhonePortDiscovery.kt`, `PhoneDiscoveryPolicy.kt` |
| Standing configuration/lifecycle | `phone/standing/PhoneStanding.kt`, `PhoneTaskStarter.kt`, `phone/android/PhoneStandingConfig.kt`, `PhoneIntegrationTaskStarter.kt`, `phone/core/PhoneTimeLimits.kt` |
| Standing pause projection and resume gate | `phone/android/PhoneScreenStateMapper.kt`, `PhoneStandingConfig.kt` (`StandingResumeFacts`/`standingResumeRefusal`), `PhoneIntegration.kt` (person resume) |
| Continuation status and retained-task projection | `phone/standing/PhoneTaskStarter.kt`, `phone/android/PhoneIntegration.kt`, `phone/android/overlay/PhoneTaskProgressAdapter.kt` |
| Temporary pairing input | `phone/android/PhonePairingSession.kt`, `PhonePairingActionReceiver.kt` |
| Notification question/notice bridge | `notify/SessionPromptProjection.kt`, `phone/android/overlay/PhoneTaskConversation.kt` |
| Admission and task state | `phone/core/PhoneController.kt`, `PhoneContract.kt`, `PhoneState.kt`, `PhoneTargetPolicy.kt` |
| Real device driver | `phone/adb/AdbPhoneDriverService.kt`, `AdbPhoneDriver.kt`, `AdbWindowState.kt` |
| Native ADB identity | `phone/android/PhoneIdentityStore.kt` |
| Per-launch loopback tool | `phone/tool/PhoneToolServer.kt`, `PhoneToolProtocol.kt` |
| Plugin registration readiness | `runtime/process/PhoneToolHost.kt`, `PhonePluginRegistrationProbe.kt` |
| OpenCode plugin and launch overlay | `assets/phone-tool/`, `runtime/process/PhonePluginAssets.kt` |
| Native continuation wake-up | `phone/android/PhoneStandingContinueSender.kt` |
| System overlay presentation | `ui/phone/overlay/`, `phone/android/overlay/PhoneOverlayCoordinator.kt`, `PhoneOverlayFailureLogic.kt` |
| Task progress and native handoff | `phone/overlay/progress/`, `phone/android/overlay/PhoneTaskProgressAdapter.kt` |
| Overlay witness and execution exclusion | `phone/overlay/safety/`, `PhoneOverlayDriverBridge.kt`, `PhoneOverlaySurfaceHost.kt` |

The loopback listener has a per-launch bearer token and a private endpoint descriptor. It exposes
fixed actions, not a shell, arbitrary intents, pairing credentials or the installed-app catalog.
Only native code may publish a new task route. A direct chat start is admitted by the native standing
starter; a legacy manually confirmed task may still allow first-action routing. A route is routing,
never authentication.

Driver operations are single-slot and bounded. Mutations use controller-issued admission guards
and the current real-window witness; a successful mutation expires the observation. Ordinary content
changes inside the same window (countdowns, banners, labels) do not refuse an action, but a new
activity/dialog, rotation or display change does, and a tap by ref requires a live element at
exactly the observed bounds. Capture checks
the target before and after collecting bytes and discards inconsistent results. These are conservative
checks, not an atomic transaction with another Android app. Unreadable window state fails closed.

The initial ADB input capability is limited to supported ASCII characters. Unicode/IME insertion,
automatic submission and password input are not supported; unsupported text is refused rather than
reported as entered. Editable field values are redacted from node labels. The synthetic fixture has
an explicit, non-editable ASCII witness so tests can verify input without weakening that policy.

## Dependencies and verification

See [the ADB dependency notice](../third_party/phone-adb/README.md) for the selected licenses,
upstream source versions, cancellable transport patch and LGPL rebuild/relink instructions.

See [the E2E fixtures](phone-e2e-fixtures.md) for the synthetic target APK, direct-begin
model-protocol fixture, native-continuation prompt recognition, and independent-package commands.
The fixture is deterministic test infrastructure, not model reasoning. Full tool-loop evidence
requires actual guest OpenCode execution, native admission, native ADB transport and independently
observed target effects.

The `Phone native smoke` workflow checks the default-off native entry, localization and reachable
controls on an emulator. It does not establish wireless pairing, driver behavior, or full tool-loop
acceptance. Those results must be recorded separately and must distinguish emulator and hardware.

The API 35/x86_64 emulator acceptance passed the configure-once save → confirmed runtime restart →
RPC registration → Ready → direct begin flow, `open_app`, native continuation, first overlay-read
wait and the retained driver/pairing/capture cases. It also passed the newer auto-port path: a fresh
port was discovered, the notification accepted only the real six-digit SystemUI RemoteInput code, and
the subsequent TLS connect was automatic. A permanent standing scope survived save/restart with the
actual lease shown as permanent, and a real built-in question (`q0 = custom true`) answered in the
overlay with **Tap Save** returned HTTP 204, continued the same model turn and kept the Save count at
exactly one without an extra prompt. The first unknown overlay outcome was not positioned and was not
resent; the final clean end-to-end run passed twice. Physical tablet follow-up results are recorded
separately and cover only the earlier basic flows; they do not establish the new auto-port, permanent
or question-bridge features. Neither run establishes broad OEM behaviour, TalkBack or real-model
reasoning. The notification check began with no live lease; no active-lease permission-revoke claim
is made. Detailed counts and evidence are in [testing-phone-control.md](testing-phone-control.md).

A separate API 35 / x86_64 emulator follow-up covers the paused standing recovery: a permanent,
fixture-only scope that became Ready-with-no-task but was still refused as `STANDING_BLOCKED` after a
runtime restart and reconnect shows `Paused (RuntimeChanged)` after the fix, and only a confirmed
person resume clears it without a grant, replay, scope/binding change or chat prompt. This is API 35
emulator evidence only, not API 37/arm64 tablet evidence. A separate regression on the same lane
confirmed that a stopped task still uses its own **Continue** entry after a new-task `need_user`
pause: one original fixed wake-up, same-session fixture input and an independent target read-back.
The new no-task resume does not replace that retained-task path.

A later API 35 / x86_64 independent-App acceptance covered the startup read path in four groups:
static idle start, bounded busy (6 s), sustained busy (30 s) and a forced read fault. The static and
bounded-busy groups completed a normal request/tap/read-back with `Save` exactly once and the
connection still connected. The sustained-busy group returned `HIERARCHY_NOT_IDLE` with
`startFailureStage=OBSERVATION`, no grant and no task, while the connection stayed connected; it made
exactly one terminal attempt with no blind retry. The forced read fault returned `READ_TIMEOUT` /
`OBSERVATION` with the logical connection disconnected, phase `NO_GRANT`, no grant and no task, and
`Save` unchanged. These are API 35 / x86_64 emulator results with the deterministic protocol fixture,
not real-model reasoning, API 37/arm64 tablet evidence, a physical-device result or a general OEM
compatibility claim. The fault host was an isolated emulator-only negative and is not evidence about
App port discovery or a real device's cause; a real user's field typed failure would still be needed
to judge that environment. Full-suite totals are owned by the separate full-suite validation and are
not restated here.

A further API 35 / x86_64 independent-App acceptance covered the handoff flow: a real `need_user`
kept the original task retained (`WAITING_USER`) without a live grant while the native title said the
agent was waiting; overlay Continue only navigated, and only the native dialog confirmation resumed
control. A 30-second busy target before the first wake produced `NOT_RESUMED` + `SCREEN_NOT_IDLE`
with no wake and no new main-chat message; after the fixture cleared, a second person confirmation
performed a fresh epoch/route/Observe and sent the fixed wake exactly once in the original session,
with one tap and one independent `Save` read-back. A transient busy snapshot during a normal task
stayed `READY` on the same grant/task and did not create a need-user handoff. A second manual stop plus
an isolated wake fault produced `FAILED` while the live task remained ready, with no automatic resend.
An explicit Finish kept `ENDED_PENDING_CHECK` until the native result was closed. The final layout was
inspected on the first screen and in the retry state: overlay Continue was visible, and the native
retry showed “not resumed yet”, the fixed technical cause and Confirm again directly. This is API 35
emulator evidence only, not API 37/arm64 tablet, physical-device or real-model reasoning evidence;
dynamic hierarchy remains unsupported and the Unicode input policy is unchanged. No claim is made
that every human intervention or every possible model misuse is eliminated.
