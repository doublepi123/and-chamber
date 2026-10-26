# System phone-progress overlay verification

## Evidence boundary

These checks use an owned API 35/x86_64 emulator, an independent debug package and the synthetic
`io.example.phonefixture` app. The real guest OpenCode, injected plugin, native controller, TLS ADB
transport and system overlay are exercised. Model decisions come from a deterministic protocol
fixture, not a real model. No personal account, physical phone or consequential business operation
is used. Raw screenshots, tokens, pairing data and traces are kept outside the public repository.

The earlier [phone-control acceptance](testing-phone-control.md) remains separate. A test overlay or
manifest/unit test alone is not evidence that the production Compose window works end to end.

## Covered production paths

| Claim | Evidence |
|---|---|
| Overlay permission is distinct from control | Actual system app-list permission flow; returning without allowing leaves notification mode usable |
| Permission alone is not visibility | Real demo window/frame, physical Take over tap, native receipt and explicit seen confirmation |
| Notifications only remains usable | Actual native selection before retesting visibility, native grant and real OpenCode snapshot without an overlay |
| Existing connection can enter overlay mode | Same native device link, explicit overlay selection, new native grant and actual production capsule |
| Meaningful progress, not a fabricated percentage | Real `progress` call; native observation/delivery versus unverified agent-note labels inspected in the panel |
| Input, Save, same-app navigation, Back, swipe, capture | Real OpenCode tool loops with target effects and fresh observations; swipe uses actual ScrollView bounds |
| Capture excludes the production overlay | Real PNG file part decoded and checked; visual review sees target/keyboard, no own capsule/panel or black patch |
| Pause is not privacy takeover | Physical panel Pause, paused/connected native status, successful authorized observation |
| Takeover stops observation too | Physical panel Take over; target remains visible, native `NO_GRANT`, snapshot refused |
| Human handoff | Real `need_user` event, native revoke before `WAITING_USER`, retained overlay with no read permission |
| Continuing is native-only | Overlay returns to the native screen; a new native confirmation/epoch is required before progress resumes |
| Model finish is not business verification | Real `finish` action, native needs-check record and result overlay; no grant and no subsequent snapshot |
| User End works after stopping | Actual native End after an unknown outcome; retained needs-check result, not a false success |
| Closing a result is not deleting the record | Physical result-card Close, overlay removed, native needs-check record still present |
| End does not silently hide a live task | Panel and drag/drop confirmation; Cancel does not create a grant or terminate the existing one |
| Target actively hides overlays | Production window with target `setHideOverlayWindows(true)`; protected read refused and grant revoked |
| Permission loss is not a silent downgrade | Runtime overlay AppOp fault, native revoke/read refusal; restoring permission does not resume |
| Both stop surfaces lost | Effective UID notification AppOp plus overlay permission fault; native revocation, no automatic regrant |
| Geometry stays separate from authority | Actual rotation/clamping and per-direction position restoration, with no restored grant |
| Cold recovery is not authorization recovery | Stop an active isolated process, reopen it: no connection, no grant, needs-check record and no resurrected old overlay |
| Soft keyboard and large text | Move a capsule low, open the real keyboard, verify it moves above the IME; inspect 1.6 font scale, dark theme and Chinese rendering |

The permission-loss rows use explicit fault injection on the isolated package. The initial permission
grant itself is performed in Android's UI, not substituted with an adb AppOp grant. Negative tests
may use the authenticated native bridge; they do not replace the real OpenCode loops.

Native reconfirmation makes tool control available; fixture continuation prompts are explicitly
driven by the harness. These results do not establish a real model's autonomous planning, waiting,
sensitive-page recognition or continuation strategy.

## Safety probe and regression checks

A separate real `TYPE_APPLICATION_OVERLAY` probe first established the platform mechanics:

- Full WindowManager state identifies the overlay and its real visibility. A token-specific dump
  supplies its actual frame; requested coordinates alone are not trusted.
- A distinctive test surface appears in a raw capture and is absent during protected collection and
  when the target hides it. Pixel checks supplement, rather than replace, window evidence.
- Tap/input focus inside the real bounds and a swipe segment crossing the bounds are refused.
- Moving/expanding invalidates old observations. A final manager-generation check immediately before
  injection prevents a newly moved overlay from being hit after the earlier frame sample.

Production regressions retain these boundaries:

- Notification-only mode does not require a nonexistent overlay, while selected overlay mode cannot
  silently use the unprotected path.
- Read hiding is owner/ticket-bound. Cancellation after Main removes the view still performs cleanup;
  restoration timeout marks the ticket uncertain and posts independent bounded-owner cleanup.
- Ticket install/check/mark/settle/clear share one short monitor. Old timeout work cannot poison a new
  owner's uncertainty; the monitor never covers a callback, window operation or dispatcher hop.
- Restoration waits for the current view's first traversal/frame. A successful `addView` is not
  substituted for visible surface evidence. The driver's independent post-check remains in place.
- Pause/takeover/handoff changes received while hidden update native state without reattaching.
  Restoration renders the latest authority, not an older Running snapshot. Reflow changes geometry
  only. New owners, explicit detach and closed results defeat late restoration.
- Unknown outcomes are not inferred as ended from an old native display record. Explicit exact-owner
  End publishes its fact only after saving the native needs-check record.
- A new native epoch can reactivate the same task and clear its current stop receipt, but cannot
  replay old operations. Same-epoch manual route adoption does not reactivate a stopped task.
- Overlay text/events are filtered by display epoch; a demo never displays a real task feed.
- Full-display placement uses the matching window layout origin; status-bar offsets are not added
  twice. Expanded height is bounded by the available viewport. Bounded-rate geometry tracking runs
  only while the manager owns an attached surface. It reads window metrics, not target text or
  screenshots, and never manufactures progress. Large-font labels may ellipsize; details remain in
  the scrollable panel.

## Final automated checks

- 1,741 JVM tests passed, with no failures or skips.
- Node: 335 passed and one existing stock-ChatContainer fixture skipped; Python: 72 passed.
- Native device checks: 13 phone wiring, four overlay wiring and three entry/localization/large-font
  smoke tests passed. These metadata/smoke checks do not replace the real task loops above.
- `lintDebug`, debug and AndroidTest packaging passed; lint retains warnings/information, not a
  zero-warning claim. The isolated debug APK passed signature verification.
- No release version, tag or publication was created by this work.

## Reproduction

Use [the offline fixture instructions](phone-e2e-fixtures.md), an explicitly selected emulator and an
independent `andchamber.testApplicationId`. Check authentication enforcement before using pairing
success as evidence. Use the normal native controls for permission/demo/grant/handoff; do not replace
them with a fake controller or preauthorized host adb commands.

Do not run native instrumentation or a UiAutomation inspector concurrently with a model/driver
operation: the runner may terminate its target, and UiAutomation can compete with the driver's real
`uiautomator dump`. The private accessibility inspector used for physical overlay-button coordinates
targets its own throwaway package, reads only, disconnects after export and is not a product feature.

First-frame timing, IME bounds and actual frames must be checked on the built production overlay.
The tool call uses the existing bounded 15-second maximum to accommodate window/focus checks; the
controller also caps it by the remaining native lease, and cancellation/admission checks remain.

## Limits

Window checks are conservative, not atomic with another app and not a universal visibility detector.
Unreliable states fail closed. Physical arm64/OEM behavior, foldables, multi-window policy and real
model reasoning are not established by this emulator evidence. Accessibility semantics/actions can
be inspected without claiming that every spoken TalkBack interaction was exercised. Unicode,
password entry, automatic submission and malicious same-UID guest isolation remain out of scope.
