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

## Chat question and plain-notice follow-up (API 35 / x86_64, this round)

These are separate API 35 / x86_64 follow-up runs, made after the earlier overlay base flow. They
used the real builtin-question path, a real form reply and the production overlay; model decisions
still came from the deterministic fixture. The final two runs used a build with no fixed-stage/HTTP
probe logging. Raw screenshots and traces remain outside this repository.

| Claim | Evidence |
|---|---|
| Builtin question is visible in full | A real OpenCode builtin question produced a pending form with one `q0` string field (`custom:true`) and two options. A screenshot observer confirmed the full question text, both options and the actual Send control; raw screenshots are not stored here. |
| An unknown first reply is read-only | The first send returned `Unknown`; the cause was not established. The overlay showed a read-only `Unknown` state with refresh/open-chat and did not automatically POST the form reply again. |
| Expanded panel is correctly excluded | A later fresh form returned HTTP 204, but the panel was still expanded over the fixture's Save button. The driver refused the target tap with `TARGET_FORBIDDEN`. That is the intended own-overlay exclusion, not a target or fixture failure. |
| A user Send collapses before the reply completes | After the fix, a real user Send enters `Sending`; the same Main callback collapses the panel immediately, while the IO pending check and single POST continue. It does not wait for HTTP 204 to collapse. There is no model-triggered collapse action. |
| Post-fix question loop completes twice | Two final no-diagnostic builds completed: overlay selects **Tap Save** + **Send** → HTTP accepted → the same model turn performs `status`/`snapshot`/`tap`/readback → fixture verification passes; the independent target witness reports `Saved` once. No extra user prompt or manual form-reply substitute was used. |
| Only the same answer identity may reopen | Focused JVM/regression checks cover the decision: if the final state is `Unknown` / `Refused` / `Superseded` / `Blocked` and the task/epoch/launch plus `formId`/`generation` are unchanged, the panel reopens read-only so the failure is not silent. A new task/epoch/launch or changed answer identity does not reopen the old panel; `Accepted` stays collapsed. This boundary was not separately forced in the two device runs. |
| Plain notices are shown independently | Under the **From the chat** section, a real task notice displayed the title **Build is ready** and body **Phone completed the task**. It is separate from the question identity; permission events keep their original notification path. No notice is written to progress, tool status or logs. |
| Accepted is terminal for this submission | After `Accepted`, refresh did not show a false `Unsupported` message and the overlay could not send the same question again. |
| Duplicate protection is bounded | The submit tombstone is keyed by launch + session + form id, holds no answer, is capped at 64 entries and never evicts. A full table shows `Blocked` read-only rather than allowing a duplicate; a new form id or launch may submit while capacity remains. A pending-read failure before POST is not tombstoned and may be re-read. This boundary is covered by focused JVM tests; it was not a 65-form device stress run. |

The `Unknown` and `TARGET_FORBIDDEN` observations above are historical findings before the final
collapse fix. They are not the successful post-fix runs and must not be counted as a pass for the
overlay-covered target. The final evidence is the two no-diagnostic question loops.

The native prompt/notice data is mapped field-for-field to the UI model: `formId` and `generation`
are preserved unchanged, the question/notice text stays bounded, and all prompt delivery states and
notice kinds use exhaustive `when` branches rather than runtime string lookup. A future native state
therefore requires a compile-time mapping update.

## Need-you handoff and continue layout follow-up (API 35 / x86_64)

A later API 35 / x86_64 follow-up re-checked the "agent asked for you" handoff after the panel and
native stopped-card layout changed. It used an independent debug package, the synthetic fixture
app, the real native controller and the production overlay; the final runs used an APK built from
the final layout source. Model decisions still came from the deterministic fixture. An observer
reviewed the two latest screenshots of the final layout; raw screenshots and traces stay outside
this repository.

| Case | Evidence |
|---|---|
| Continue is visible without scrolling | Expanded **Your turn** panel after a real `need_user`: the pinned **I'm done, let the agent continue…** button is fully visible on the first screen. The chat's plain notice and **Recent** scroll in the body below and do not push it away. |
| The overlay entry is navigation only | Tapping it opens the native confirmation. Before that confirmation, epoch, no-grant state and the original chat context are unchanged; the overlay grants nothing. |
| A busy first continue is not resumed | A first person confirmation while the fixture page was busy returned `NOT_RESUMED` with `SCREEN_NOT_IDLE`: task still retained, no grant, no chat wake-up. The overlay kept showing **Your turn** (`WAITING_USER`), not a false unknown result. |
| Not resumed screen leads with the real cause | The native screen showed, in order: the status strip, **Not resumed yet, task kept** with the fixed technical cause, a fully visible **Confirm continue again…** button, then task details and open-app. It did not repeat the original manual-step request and showed no ended or "you took over" title. |
| A second confirmation continues the same task | After the page settled, a second person confirmation created a fresh grant and observation, woke the same chat once and the fixture recorded Save once. No new request replaced the original task. |
| Unsafe states stay closed | JVM UI/native policy regressions verify that an unknown outcome, an unconfirmed stop, an expired scope or a not-ready state still disables continue. No new forced-device unknown or permission-revoke run is claimed here. The overlay never grants directly. |
| Stop controls are not demoted while a continue runs | UI ordering tests verify that only an idle or not-resumed settled stop moves continue above the task details (and take over / end right below the card). While a continue is sending, delivered or failed, take over / end stay at the top. Reordering does not change continue eligibility or the single chat wake-up. |

The full flow was repeated on the APK built from the final layout source and passed. This evidence
covers the default English text at the current phone viewport only. Large font, landscape, TalkBack,
physical devices and API 37 were not re-checked for the new layout, and earlier layout or
compatibility evidence must not be read as covering the pinned button or the reordered card. The
fixture's continuation is deterministic; it says nothing about a real model's reasoning. The
behaviour boundaries are described in [phone control](phone-control.md); the base flow is in
[the phone-control verification](testing-phone-control.md).

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

- JVM: 1,947 tests, with no failures, errors or skips.
- Node: 359 tests total: 358 passed and one existing stock-ChatContainer fixture skipped; no failures.
- Python: 72 tests passed.
- A later wording-only cleanup passed all 12 `PhoneStringsTest` cases and a debug APK rebuild.
- `lintDebug`, debug and AndroidTest packaging passed; lint retains warnings/information, not a
  zero-warning claim.
- No new device instrumentation was executed in this round. The earlier device smoke result
  (20/20 in the earlier run) remains historical base-flow evidence only; it is not a new test result
  and does not cover the question/notice or permanent-scope follow-ups. The new evidence is the
  API 35 / x86_64 production overlay run above.
- No release version, tag or publication was created by this work.

## Reproduction

Use [the offline fixture instructions](phone-e2e-fixtures.md), an explicitly selected emulator and an
independent `andchamber.testApplicationId`. Check authentication enforcement before using pairing
success as evidence. Use the normal native controls for permission/demo/grant/handoff; do not replace
them with a fake controller or preauthorized host adb commands.

For the question follow-up, use the real builtin-question fixture with the two-option form. Confirm
the full question text, both options and the Send control on the production overlay; select one
option and press Send. Verify the panel collapses immediately after the real user Send, the form
reply is accepted, the same model turn continues through `status`/`snapshot`/`tap`/readback, and the
independent target witness shows `Saved` exactly once. Do not inject an extra user prompt or send a
form reply by hand. The `Unknown`/`TARGET_FORBIDDEN` observations above are historical; the post-fix
evidence is the two no-diagnostic runs. Use the focused JVM tests for the bounded tombstone and
same-identity reopen boundaries rather than fabricating a device failure.

For the plain-notice check, deliver the ordinary **Ready**, **Goal** or **Error** notice for the
current task session and confirm it appears independently of the question. A permission event must
continue through its original notification path, not as a plain notice, and must not call a
permission reply from the overlay.

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

The question, plain-notice and permanent-scope follow-ups are API 35 / x86_64 only. No new API 37,
arm64, tablet, OEM, TalkBack or real-model-reasoning evidence was produced for them. The earlier
API 37 tablet base flow in [the phone-control verification](testing-phone-control.md) is retained as
historical base-flow evidence and must not be cited as a pass for these new features. No active-lease
permission revocation was exercised in this round, and no claim is made for untested negative cases.
