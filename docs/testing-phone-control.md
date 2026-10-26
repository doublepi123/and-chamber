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

- JVM: 1,817 tests, no failures, errors or skips.
- Node: 349 tests total: 348 passed, one existing stock-ChatContainer fixture skipped, no failures.
- Python: 72 tests passed.
- `lintDebug`, debug APK and AndroidTest APK builds passed. Lint retained warnings/information;
  this is not a claim of zero lint findings.
- Device instrumentation: 20/20 passed. The smoke suite does not stand in for pairing or the full
  tool loop; the separate fixture/emulator lane carries the direct-start, continuation and overlay
  evidence.

## Important regressions retained

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

No physical arm64, OEM background-lifetime, Android 11-wide compatibility, TalkBack or long-duration
survival claim is made. The input backend supports only its declared ASCII subset, not Unicode,
passwords or automatic submission. The target-package policy is not an OS sandbox, and the whole
local runtime must remain trusted. Window checks are conservative before/after checks, not an atomic
transaction with another Android app. No actual payment, account operation or externally
consequential task was performed, and no business-success claim is made.

The notification permission/AppOp check began with no lease; the run did not revoke permission
during an active lease. That distinction matters: the no-lease gate is verified, active-lease
permission revocation is not.

Raw screenshots, authentication state, pairing details and traces remain outside this public
repository. Public fixtures use synthetic content; no private host, device identifier or account is
needed to reproduce the checks.
