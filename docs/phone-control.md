# Experimental local phone control

Phone control is an opt-in Android 11+ experiment. It uses the existing on-device OpenCode runtime,
an App-injected `andchamber_phone` plugin, and a native wireless-debugging client. It is not remote
device control and does not change the embedded browser's foreground-only authorization rules.

The covered API 35 / x86_64 emulator flows passed [integrated acceptance](testing-phone-control.md),
including a direct chat request, preauthorized app opening, native continuation and the first
overlay-read wait. Do not treat a fixture script or a successful APK build alone as full product
verification. Physical arm64 devices, vendor background policies, TalkBack and real-model reasoning
have not been verified for this feature.

## Trust and scope

- Off by default. The native phone-control screen explains the risks before enabling it.
- The whole local runtime, including guest subprocesses and installed plugins, must be trusted.
  PRoot and ordinary private files do not isolate a malicious same-UID guest. Session labels route
  calls; they are not a separate authentication boundary.
- Wireless debugging authorizes a device-level ADB identity. Restricting the tool to a native
  preauthorized app set is an admission policy, not an Android permission sandbox.
- The person saves one bounded **standing scope**: allowed apps, allowed actions, a window and a
  per-task duration. This is configuration, not a lease. The first valid chat request binds it to the
  current runtime and session. Each task then gets a new controller epoch and a short lease bounded
  by the per-task duration and the remaining window.
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
3. Follow Android's Wireless debugging setup. Keep the system pairing-code dialog open and use
   the notification's two reply steps for its pairing port and six-digit code. Pairing and connection
   ports are different. A manual connection-port fallback remains available.
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

Stopping does not undo a dispatched operation. If an outcome is unknown, check the target app
before deciding what to do next; the app does not replay it automatically. A tool acknowledgement
does not prove that the person's business task completed. Task results remain unconfirmed without
separate, relevant evidence.

Pause stops new changes; authorized observation can still help inspect an uncertain result. Use
**I'll take over**, return to the native phone page, or end the task before entering sensitive
information: these revoke the whole lease, including observation. A model's final sentence is not a
native control-release acknowledgement; use the native controls or the short lease's expiry.

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
`finish` ends control and saves a result needing review, not a verified business success. Ordinary
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

## Implementation boundaries

| Area | Entry points |
|---|---|
| Native presentation | `ui/phone/PhoneControlScreen.kt`, `PhoneStandingCard.kt`, `PhoneUiModel.kt`, `PhoneUiLogic.kt` |
| Android coordination | `phone/android/PhoneIntegration.kt`, `PhoneControlService.kt`, `PhoneControlNotifications.kt` |
| Standing configuration/lifecycle | `phone/standing/PhoneStanding.kt`, `PhoneTaskStarter.kt`, `phone/android/PhoneStandingConfig.kt`, `PhoneIntegrationTaskStarter.kt` |
| Temporary pairing input | `phone/android/PhonePairingSession.kt`, `PhonePairingActionReceiver.kt` |
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
and current window/content witnesses; a successful mutation expires the observation. Capture checks
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
wait and the retained driver/pairing/capture cases. It did not test physical arm64/OEM,
TalkBack or real-model reasoning. The notification check began with no live lease; no active-lease
permission-revoke claim is made. Detailed counts and evidence are in
[testing-phone-control.md](testing-phone-control.md).
