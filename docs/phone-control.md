# Experimental local phone control

Phone control is an opt-in Android 11+ experiment. It uses the existing on-device OpenCode runtime,
an App-injected `andchamber_phone` plugin, and a native wireless-debugging client. It is not remote
device control and does not change the embedded browser's foreground-only authorization rules.

The covered API 35 / x86_64 emulator flows passed [integrated acceptance](testing-phone-control.md).
Do not treat a fixture script or a successful APK build alone as full product verification. Physical
arm64 devices and vendor background policies have not been verified for this feature.

## Trust and scope

- Off by default. The native phone-control screen explains the risks before enabling it.
- The whole local runtime, including guest subprocesses and installed plugins, must be trusted.
  PRoot and ordinary private files do not isolate a malicious same-UID guest. Session labels route
  calls; they are not a separate authentication boundary.
- Wireless debugging authorizes a device-level ADB identity. Restricting the tool to one selected
  third-party app is an admission policy, not an Android permission sandbox.
- The initial scope is one unlocked phone, one task, one selected app, and a short native grant.
  The host, system settings and system authorization surfaces cannot be selected for agent control.
- Pairing keys stay on the native side. Pairing codes are transient, are not sent through chat, and
  are not included in notification history or logs by this app. Deleting local state does not revoke
  authorization in Android's Wireless debugging settings.
- An authorized observation may reach the current model provider. Local execution does not mean
  that screen data stays on the phone. Do not use this experiment with untrusted projects or plugins.

## Setup and control

1. Open **Phone control** from Home and explicitly enable the experiment.
2. If the local runtime was already running without the plugin, return Home and restart it yourself.
   Restarting interrupts current OpenChamber tasks; enabling the switch never restarts it silently.
3. Follow Android's Wireless debugging setup. Keep the system pairing-code dialog open and use
   the notification's two reply steps for its pairing port and six-digit code. Pairing and connection
   ports are different. A manual connection-port fallback remains available.
4. Connect the local device, choose the target app, review the task, actions and lease duration, then
   confirm in the native screen. A chat proposal only prefills this review; it never authorizes itself.
5. Pause or return through the native notification, or return to AndChamber. Reconnection is not a
   grant. Continuing requires another native confirmation and fresh observations.

Foreground-service readiness is checked before a grant route is published and the target is
launched. Notification permission/channel checks and a successful `startForeground` call are not
proof that the user saw a notification. Returning to AndChamber is an independent native stop path.

Stopping does not undo a dispatched operation. If an outcome is unknown, check the target app
before deciding what to do next; the app does not replay it automatically. A tool acknowledgement
does not prove that the user's business task completed. Task results remain unconfirmed without
separate, relevant evidence.

Pause stops new changes; authorized observation can still help inspect an uncertain result. Use
**I'll take over**, return to AndChamber, or end the task before entering sensitive information:
these revoke the whole lease, including observation. A model's final sentence is not a native
control-release acknowledgement; use the native controls or the short lease's expiry.

## Optional system progress overlay

**Progress over other apps** is a separate, default-off native setting. Notifications remain
available in both modes. A system draw-over permission does not grant device control.

1. Open the system permission screen yourself. Android 11+ may show an app list rather than opening
   this app's switch directly. Refusing it never prevents selecting **Notifications only**.
2. Return to AndChamber, show the clearly marked demo capsule, tap **Take over**, then confirm that
   you saw and pressed it. The demo grants no device lease. Permission or `addView` success alone is
   not visibility evidence; the demo only establishes reachability at that moment.
3. Explicitly choose **Overlay and notifications**. New tasks still require native confirmation of
   target, actions and duration. Enabling an overlay does not resume a stopped task.

The draggable, edge-snapped capsule shows the control phase and dispatch count, not a percentage of
business completion. Tap its body for the bounded task panel. The confirmed summary is folded by
default. Events distinguish native observations/confirmations, dispatched actions and unverified
agent notes. No event means waiting for updates, not fabricated progress.

**Take over** revokes observation and changes while leaving the current target page open. **Pause
actions** in the panel stops new mutations but can still allow observation. Pending stop requests
are not displayed as confirmed. Already-sent device commands are never described as rolled back.
Continuing opens the native confirmation flow; there is no overlay grant or renewal action.

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

For observation/capture, an owner-bound display lease temporarily removes the overlay, independently
verifies its absence, restores the latest native state and waits for its first frame. Mutations cannot
run concurrently with that collection. The notification stop path remains independent. Cancelled or
timed-out restoration cannot silently leave a permanent hidden state or resurrect an older owner.
Actual WindowManager checks, not `FLAG_SECURE`, establish the supported exclusion behavior.

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
| Native presentation | `ui/phone/PhoneControlScreen.kt`, `PhoneUiModel.kt`, `PhoneUiLogic.kt` |
| Android coordination | `phone/android/PhoneIntegration.kt`, `PhoneControlService.kt`, `PhoneControlNotifications.kt` |
| Temporary pairing input | `phone/android/PhonePairingSession.kt`, `PhonePairingActionReceiver.kt` |
| Admission and task state | `phone/core/PhoneController.kt`, `PhoneContract.kt`, `PhoneState.kt` |
| Real device driver | `phone/adb/AdbPhoneDriverService.kt`, `AdbPhoneDriver.kt`, `AdbWindowState.kt` |
| Native ADB identity | `phone/android/PhoneIdentityStore.kt` |
| Per-launch loopback tool | `phone/tool/PhoneToolServer.kt`, `PhoneToolProtocol.kt`, `runtime/process/PhoneToolHost.kt` |
| OpenCode plugin and launch overlay | `assets/phone-tool/`, `runtime/process/PhonePluginAssets.kt` |
| System overlay presentation | `ui/phone/overlay/`, `phone/android/overlay/PhoneOverlayCoordinator.kt` |
| Task progress and native handoff | `phone/overlay/progress/`, `phone/android/overlay/PhoneTaskProgressAdapter.kt` |
| Overlay witness and execution exclusion | `phone/overlay/safety/`, `PhoneOverlayDriverBridge.kt`, `PhoneOverlaySurfaceHost.kt` |

The loopback listener has a per-launch bearer token and a private endpoint descriptor. It exposes
fixed actions, not a shell, arbitrary intents, pairing credentials or the installed-app catalog.
Only native code may publish a newly confirmed grant route. A chat-originated task pins its session
label; an explicitly manual task may allow the first action to claim the route.

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

See [the E2E fixtures](phone-e2e-fixtures.md) for the synthetic target APK, model-protocol fixture,
and independent-package commands. The fixture is deterministic test infrastructure, not model
reasoning. Full tool-loop evidence requires actual guest OpenCode execution, native admission,
native ADB transport and independently observed target effects.

The `Phone native smoke` workflow checks the default-off native entry, localization and reachable
controls on an emulator. It does not establish wireless pairing, driver behavior, or full tool-loop
acceptance. Those results must be recorded separately and must distinguish emulator and hardware.
