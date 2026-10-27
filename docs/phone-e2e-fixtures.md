# Phone E2E fixtures (test support only)

This document owns the two assets in `scripts/phone-e2e/`:

- a test-only Android target APK (`io.example.phonefixture`) that gives the real phone-control
  driver deterministic native views to observe and act on;
- a deterministic OpenAI-compatible model fixture that makes OpenCode 2.0.32 call the real
  `andchamber_phone` plugin tool.

They are test support. They do not change production Kotlin, Gradle, the manifest, DI or the
phone-tool plugin. They do not talk to an emulator, install an APK, start the app, or call the
phone tool server themselves. The orchestrator runs the real guest, the real standing scope,
native continuation and the real OpenCode/plugin/native chain.

## Privacy and repository rules

- The target package is `io.example.phonefixture` (reserved `example` domain). The fixture server
  binds `127.0.0.1` by default. The guest-side test address is the emulator host alias
  `10.0.2.2`, never a private LAN address.
- The provider API key in the template is the fixed non-secret sentinel
  `test-only-fixture-key`; the fixture ignores `Authorization`. Do not put a real key here.
- The signing key, PID files, transcripts and APKs stay outside the repository. The default
  keystore directory is `/tmp/opencode/andchamber-phone-e2e/keys`; the build script refuses a
  keystore directory outside `/tmp/opencode` and refuses an output directory inside the repository.
- Never write a real account, personal hostname/IP, cookie, password or screenshot into a fixture
  log or this document.

## 1. Target APK

### What it exposes

Package `io.example.phonefixture`, launchable `MainActivity`, second `SecondActivity` in the same
package. No permissions, no network, no storage, no real account. It compiles against API 35 but
targets API 34, so Android 15 does not force edge-to-edge layout over this test-only view fixture.

Stable resource ids and labels (real uiautomator XML sees both):

| id | role | text/label | behaviour |
|---|---|---|---|
| `fixture_title` | TextView | `AndChamber Phone Fixture` | stable first-page marker |
| `fixture_counter` | TextView | `Saved 0 times` | becomes `Saved 1 times`, `Saved 2 times`, ... |
| `fixture_input` | EditText | hint/content-desc `fixture-input` | ASCII input target; the privacy driver clears its editable label |
| `fixture_input_echo` | TextView (non-editable) | `fixture-input-echo-empty`, then the exact typed value | real-UI witness; mirrors only `fixture_input`, never the password field |
| `fixture_password` | EditText (password) | fixed dummy value, masked; hint/content-desc `fixture-password` | separate sensitive-field test only; the driver marks it non-editable and the scenarios never type into it |
| `fixture_save` | Button | `Save` | increments the counter |
| `fixture_next` | Button | `Next page` | opens `SecondActivity` |
| `fixture_list` | ScrollView | - | 30 rows `Fixture row 01` ... `Fixture row 30` |
| `fixture_second_title` | TextView | `Second page` | second-page marker |
| `fixture_second_body` | TextView | `Page two ready` | second page |
| `fixture_back` | Button | `Back` | finishes the second page; system back also works |

The driver's privacy policy clears the label of every editable/password node, so the input field's
own text is not a valid read-back. `fixture_input_echo` is a plain non-editable TextView that starts
at the fixed marker and mirrors the actual ordinary-input value through a TextWatcher. The model
fixture requires the marker in the first snapshot and a non-editable node with the exact typed value
in the final snapshot; a tool receipt or a cleared editable label cannot satisfy it. The password
field has no watcher and is never mirrored.

### Bounded busy window (negative cases, test hook only)

`MainActivity` accepts one optional integer launch extra, `fixture_busy_ms`. When the extra is
present it is stored in the fixture's own preferences (`fixture_hooks` / `busy_ms`) and clamped to
0..30000 ms. Every later `onResume` reads that stored value and, for that many milliseconds, sends
`AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED` from the root view every 100 ms. `onPause` and
`onDestroy` cancel the schedule, and a zero or expired window does not restart on its own.

The hook exists only so a negative case can make the hierarchy deliberately non-idle. It is not a
production runtime feature, not root and not an AccessibilityService: it adds no permission, sends
no input, starts no service and changes no layout, resource id, label or `Save` behaviour.

The default is 0, so a fixture with no stored preference behaves exactly as before. The value is
persisted, and `adb install -r` with the same signing key keeps app data, so a plain reinstall does
not necessarily clear a window left by an earlier case. Pass an explicit 0 or clear the fixture data
first:

```sh
adb -s <emulator-serial> shell pm clear io.example.phonefixture
adb -s <emulator-serial> shell am start -n io.example.phonefixture/.MainActivity --ei fixture_busy_ms 0
```

Negative cases are host-operator steps against the synthetic fixture only. Reset the target before
each case so a previous intent or stored preference cannot leak into it, arm the bounded window, then
return to the local chat and run the existing `tap_save` model-fixture case unchanged:

```sh
adb -s <emulator-serial> shell pm clear io.example.phonefixture
adb -s <emulator-serial> shell am start -n io.example.phonefixture/.MainActivity --ei fixture_busy_ms 6000
# same with --ei fixture_busy_ms 30000 for the long window
# then: return to the local chat and send the existing tap_save case
```

| window | expected result |
|---|---|
| 0 or no extra | static page: `tap_save` completes and the counter reads `Saved 1 times` |
| 6000 ms | busy for the bounded window only, then a stable hierarchy: `tap_save` completes and the counter reads `Saved 1 times` |
| 30000 ms | the hierarchy does not idle while the request runs: refusal `HIERARCHY_NOT_IDLE`, `taskPresent` false and `connected` true. The target app may have opened, but no usable control task or input action is confirmed. |

Do not run `uiautomator` or any other accessibility dump while the model driver is still in flight:
it would take over the same accessibility session. Read the fixture counter only after the request
has returned, and never re-send the action.

The 30000 ms outcome is a safe refusal with an accurate cause. It is not a network disconnect, and it
is not evidence that the fixture (or the product) supports continuously animating pages; the fixture
only refuses a non-idle read instead of acting on a stale hierarchy.

Cleanup is `--ei fixture_busy_ms 0` or `pm clear io.example.phonefixture`. With the default zero the
fixture still needs no permission and makes no network call.

### Build (offline, SDK/JDK tools only)

```sh
node scripts/phone-e2e/build_target_apk.cjs \
  --out /tmp/opencode/andchamber-phone-e2e/apk
```

`--out` must be absent or empty and outside the repository. The script finds `aapt2`, `d8`,
`zipalign`, `apksigner` and `android.jar` under `ANDROID_HOME` (or `--sdk`), compiles the Java and
resource sources under `scripts/phone-e2e/target/`, and signs with a throwaway keystore generated
under `/tmp/opencode`. It prints and writes `build-result.json`.

Expected install command (the orchestrator owns installation; a serial is always explicit, never
auto-discovered):

```sh
adb -s <serial> install -r /tmp/opencode/andchamber-phone-e2e/apk/phone-fixture.apk
adb -s <serial> shell am start -n io.example.phonefixture/.MainActivity
```

The build itself does not invoke adb.

## 2. Provider test config for OpenCode 2.0.32

The provider must be injected into the guest's **inline test config** by the acceptance-only
launch/config seam. Editing only a normal `~/.config/opencode/opencode.json(c)` is not sufficient and
is not the supported path: the test launch may overwrite or ignore it. Do **not** write the user's
global config, shared storage, or the normal app profile.

Exact template: `scripts/phone-e2e/provider-overlay.example.json`.

```json
{
  "$schema": "https://opencode.ai/config.json",
  "providers": {
    "fixture": {
      "name": "Phone E2E Fixture",
      "package": "@opencode/ai/providers/openai-compatible",
      "settings": {
        "baseURL": "http://10.0.2.2:32123/v1",
        "apiKey": "test-only-fixture-key"
      },
      "models": {
        "phone": {
          "name": "phone-fixture",
          "capabilities": {
            "tools": true,
            "input": ["text", "image"],
            "output": ["text"],
            "temperature": false
          }
        }
      }
    }
  }
}
```

Notes, checked against `@opencode/schema@2.0.32` and `@opencode/core@2.0.32`:

- OpenCode 2 uses `providers` (plural) with `package` and flat `settings`; the legacy singular
  `provider`/`options` shape is not used here.
- `@opencode/ai/providers/openai-compatible` is a built-in package alias, so the guest does not
  install an npm provider package and needs no network for provider loading.
- `10.0.2.2` is the emulator's host-loopback alias; the fixture listener itself stays on
  `127.0.0.1`. If a different test transport is used, replace only this test address.
- The `apiKey` is a fixed non-secret sentinel. The fixture never checks it.
- The model is `providerID=fixture`, `id=phone`. The fixture rejects any other model id.
- `capabilities.input` declares `image` because `capture` returns a PNG file part. OpenCode core's
  `session/model-request.ts` replaces an image file with a text "ERROR: Cannot read image..." when
  the model declares no image input; the OpenAI-compatible lowering then joins that text with the
  capture envelope and exact envelope parsing fails. Declaring image keeps the envelope as the
  only text part and sends the PNG as a later user attachment. The fixture accepts and ignores
  that attachment; it does not decode it, reason over it or claim visual understanding.
- The acceptance harness must inject this provider through its dedicated test-only guest inline
  config mechanism; the app launch overlay merges `plugins` into `OPENCODE_CONFIG_CONTENT`. Put the
  provider object in that same test-only config so the user's config is untouched. Simply editing a
  normal `opencode.json` is not enough. Applying the config requires restarting guest
  OpenCode/runtime; restarting only the model fixture server does not reload it.

## 3. Model fixture server

### Start / stop

```sh
node scripts/phone-e2e/model_fixture.cjs \
  --port 32123 \
  --transcript /tmp/opencode/andchamber-phone-e2e/transcript.jsonl

# prints, for example:
# phone-e2e-fixture listen http://127.0.0.1:32123/v1 model=phone pid=<pid> pidFile=/tmp/opencode/andchamber-phone-e2e/model-fixture-32123.pid

node scripts/phone-e2e/model_fixture.cjs --stop --port 32123
```

- Default bind is `127.0.0.1`; `--host` accepts only `127.0.0.1`.
- PID file default: `/tmp/opencode/andchamber-phone-e2e/model-fixture-<port>.pid`. Startup refuses
  to replace a live PID file; `--stop` verifies `/proc/<pid>/cmdline` names this fixture before
  `SIGTERM`, then removes only its own PID file. It never searches for or signals the main
  OpenCode server.
- `--transcript` must be an absolute path outside the repository; entries are JSONL.
- Endpoints: `POST /v1/chat/completions` (JSON and SSE), `GET /v1/models`, `GET /health`.
- OpenCode 2.0.32's OpenAI-compatible route always requests `stream: true`; the JSON response path
  is implemented and tested for clients that do not stream.

### Sentinel protocol

The fixture is inert without a test sentinel in the latest user message. Only a flat JSON object
is accepted:

```text
[[andchamber-phone-e2e {"mode":"begin","scenario":"input"}]]
[[andchamber-phone-e2e {"mode":"continue","scenario":"input"}]]
[[andchamber-phone-e2e {"mode":"action","action":"tap","label":"Next page"}]]
[[andchamber-phone-e2e {"mode":"action","action":"input","text":"andchamber-ascii-1"}]]
```

Native continuation is recognized separately. After the test sentinel, a later user message must be
exactly the fixed continuation prompt sent by the native `PhoneStandingContinueSender`:

```text
Continue the previous task. First observe the phone screen again; do not repeat any action that already happened.
```

That message is not a second sentinel and must not be posted by the acceptance driver as another
test prompt. The fixture treats it as `mode=continue`, reuses the scenario (or
`sentinel.resumeScenario`), and resumes with a real observation/action. Tests assert that the
original session received exactly one such prompt and no extra acceptance-script continuation
prompt.

The fixture first verifies that the request's `tools` contains `andchamber_phone` with the real
schema (`action` enum including `apps`/`open_app`, `observationId`, `ref`, `x`/`y`/`x2`/`y2`, `text`, `additionalProperties:
false`). If the tool is missing or malformed it returns a fixed text refusal and **no** tool call;
it never pretends a tool was executed. No network, paid model or real key is involved.

### Deterministic sequence

`mode=begin` proposes exactly one read-only call:

```text
request { action:"request", summary:"AndChamber phone E2E <scenario>", packageName:"io.example.phonefixture" }
```

`mode=begin` starts the real user story directly: the first call is

```text
request { action:"request", summary:"AndChamber phone E2E <scenario>", packageName:"io.example.phonefixture" }
```

There is no per-task `PENDING_CONFIRMATION` and no manual "continue" prompt. Only a parsed
direct-start envelope with `ok:true`, a fresh integer `epoch >= 1` and `data.state` in
`RUNNING`/`READY`/`NEEDS_USER` continues. The next turn runs the concrete observed scenario, for
example `status -> snapshot -> <action> -> snapshot`. A plain text acknowledgement, a PENDING reply,
a stale epoch or a refusal stops with `phone-e2e-fixture: request-not-confirmed`.

`mode=continue` remains available for explicit orchestration and for a new sentinel/continuation
prompt. The native continuation path is described above and does not need a second sentinel.

`status` supplies the real epoch; `snapshot` supplies the real `observationId`, package binding,
display geometry and node refs. The action arguments use those returned values, never guessed ids.
The final snapshot is checked before the fixed completion text:

```text
phone-e2e-fixture: e2e-complete scenario=input action=input verify=pass epoch=<n>
```

If the check fails the text is `verify=fail:<fixed-reason>` and the scenario stops; it is not
retried. A tool refusal (`ok:false`) also stops immediately with
`phone-e2e-fixture: tool refused action=phone.<action> code=<CODE>`.

Scenarios:

| scenario | action | final check |
|---|---|---|
| `apps` | read-only `apps` | result is ok; every returned app is the preauthorized `io.example.phonefixture` package; no installed catalogue |
| `open_app` | `open_app` with `io.example.phonefixture`, then `snapshot` | result state is `OPENED`; final snapshot shows the same package in front |
| `input` | `input` into the editable `fixture-input` node (default ASCII `andchamber-ascii-1`, or sentinel `text`) | first snapshot has the non-editable `fixture-input-echo-empty` marker; final snapshot has a non-editable node with the exact typed value |
| `tap_save` | `tap` the clickable `Save` node | counter value increased |
| `tap_next` | `tap` the clickable `Next page` node | final snapshot shows `Second page` |
| `back` | `back` with the current observationId | final snapshot shows `AndChamber Phone Fixture` and not `Second page`; run this while the second page is current (use `tap_next` first, then a separate `back` prompt under the same task) |
| `swipe` | `swipe` upward inside the snapshot geometry | final snapshot shows a higher `Fixture row NN` than the first snapshot |
| `capture` | `capture` the current observationId | capture text envelope is `image/png` with positive width/height; a tool multipart may carry exactly one text part plus known `image_url`/`file` media parts; the following image user attachment is ignored. Base64 is never logged or echoed |
| `status` | read-only | minimal enabled/connection state; no observation or task required |
| `snapshot` | read-only | target package remains `io.example.phonefixture` |
| `need_user` | `status`, then `need_user` | native stop receipt; a later native continuation prompt resumes with a real mutation (default `input`) |

For explicit orchestration, `mode=action` with `action` selects the same sequence; `ref`, `x`/`y`,
`x2`/`y2`, `durationMs`, `text`, and `label` are optional fixed overrides. Explicit action
sentinel prompts still go through the real OpenCode tool call.

### `ask_choice`: built-in question to a phone action

`ask_choice` exercises OpenCode 2.0.32's built-in `question` tool before a real phone action. It is a
separate test-only branch; it does not add a production tool, plugin change, overlay rule or
notification reply path. The sentinel names the expected option:

```text
[[andchamber-phone-e2e {"mode":"begin","scenario":"ask_choice","expectAnswer":"Tap Save"}]]
```

The order is fixed:

1. The first model turn emits the real `andchamber_phone` `request`. This creates the current phone
   task/route; without it the real overlay has no `currentTask` and cannot show a choice. Only a
   parsed direct-start envelope with a fresh integer epoch and `RUNNING`/`READY`/`NEEDS_USER`
   continues.
2. The next turn emits the built-in `question` tool with exactly one question:

   ```json
   {"questions":[{"question":"Which fixture action should I run next?","header":"Next step","options":[{"label":"Tap Save","description":"Run the tap_save scenario"},{"label":"Type text","description":"Run the input scenario"}]}]}
   ```

   Option descriptions are mandatory. The fixture omits `multiple` and never sends `custom:false`;
   the model does not own the form's custom flag. If the request does not prove the real `question`
   function/schema was injected, the fixture returns a fixed refusal and emits no question call.
3. The real runtime creates the pending form (`q0` string, option values equal to the labels,
   `custom:true`). A person answers through the production overlay/notification path; the
   production bridge performs the real `NotificationActions.formReply` with an `answer` map. The
   fixture never posts an answer, a continuation prompt or a second request. It only reads the
   resulting roletool text:

   ```text
   User has answered your questions: "Which fixture action should I run next?"="Tap Save". You can now continue with the user's answers in mind.
   ```

4. Only an exact listed option that equals `expectAnswer` continues. `Tap Save` reuses the
   `tap_save` sequence and `Type text` reuses the `input` sequence (`status`, `snapshot`, mutation,
   `snapshot`) on the same task, then verifies the real UI. A missing answer or a still-pending
   result emits a fixed pending text; a wrong, unknown or free-form answer emits fixed mismatch
   text. Neither mutates, retries, asks again or posts a continuation.

A question roletool result is prose, never a phone envelope: even if it contains a JSON object with
`version`/`ok`/`action`/`epoch`, the fixture does not parse it as a phone result or let it satisfy
snapshot/read-back verification. Logs record the actual tool name (`tool=question action=- case=call-question`),
but never the question text, option labels, expected answer, answer or tool arguments.

### Transcript shape

The server logs only counts, fixed sentinel case, tool name, fixed action and fixed case. Direct
`begin` is deterministic: the first turn is `request`, then the scenario turns continue in the same
conversation (each line is a separate model request):

```text
phone-e2e-fixture request seq=1 stream=true messages=1 tools=1 phoneTool=valid sentinel=begin:input call=0 decision=tool tool=andchamber_phone action=request case=call-request
phone-e2e-fixture request seq=2 stream=true messages=3 tools=1 phoneTool=valid sentinel=begin:input call=1 decision=tool tool=andchamber_phone action=status case=call-status
phone-e2e-fixture request seq=3 stream=true messages=5 tools=1 phoneTool=valid sentinel=begin:input call=2 decision=tool tool=andchamber_phone action=snapshot case=call-snapshot
phone-e2e-fixture request seq=4 stream=true messages=7 tools=1 phoneTool=valid sentinel=begin:input call=3 decision=tool tool=andchamber_phone action=input case=call-input
phone-e2e-fixture request seq=5 stream=true messages=9 tools=1 phoneTool=valid sentinel=begin:input call=4 decision=tool tool=andchamber_phone action=snapshot case=call-snapshot
phone-e2e-fixture request seq=6 stream=true messages=11 tools=1 phoneTool=valid sentinel=begin:input call=5 decision=text tool=- action=- case=e2e-complete
```

The final text is the same fixed `e2e-complete ... verify=pass` line. With the native continuation
prompt the sentinel remains the original one, but the plan switches to `continue` and a real
mutation; the acceptance harness separately counts that the original session received exactly one
fixed prompt.

The epoch in a real run is the native task epoch returned by the real server, not the fixture's
unit-test value.

## 4. Harness calls (real guest, orchestrator-owned)

The fixture does not implement the guest harness. Use the already-verified OpenChamber 2.4.0 /
OpenCode 2.0.32 endpoints:

```text
POST /api/session?directory=%2Froot
  header: x-opencode-directory: /root
  body: {"model":{"id":"phone","providerID":"fixture"}}
  -> session id (`<session-id>`)

# optional re-assert on an existing session:
POST /api/session/<session-id>/model
  body: {"model":{"id":"phone","providerID":"fixture"}}

POST /api/session/<session-id>/prompt
  body: {"text":"<sentinel prompt>"}

POST /api/experimental/session/<session-id>/wait
GET  /api/plugin
  header: x-opencode-directory: /root
```

Suggested handoff:

1. Start the fixture and check `GET /health`.
2. Launch the test package with the dedicated test-only inline provider config above. In the native
   phone screen, save the standing scope once. If the runtime was already running without the
   phone plugin, confirm the native restart and wait for RPC registration (`output.ok=true`,
   `id=andchamber_phone`) and Ready.
3. Create the session with `model:{id:"phone",providerID:"fixture"}` and prompt `mode=begin`.
   The first tool call is the direct `request`; with a fresh epoch and `RUNNING`/`READY` it
   auto-continues to `e2e-complete ... verify=pass`. There is no per-task PENDING or manual grant.
4. Open the ordinary local chat; confirm the task stays READY. Then exercise `apps`/`open_app` and
   independently confirm the same `io.example.phonefixture` foreground.
5. For native continuation, trigger `need_user`, let the person act, then use the native Continue
   confirmation. Assert exactly one fixed continuation prompt in the original session, no extra
   acceptance-script continuation prompt, and `e2e-complete ... verify=pass` after re-observation.
6. For the second-page back check, run `tap_next` first, then a separate `back` prompt under the
   same task; do not expect two mutations in one scenario.

`GET /api/plugin` only proves the plugin is loaded; production readiness is the bounded native RPC
registration probe above. The model fixture's tool call, the real plugin HTTP result and the
independent target effects are the evidence that the tool was present and executed.

## 5. Verification status

Locally run (offline, no emulator, no app):

```sh
node --test scripts/tests/phone-e2e-fixture.test.cjs
```

This covers:

- JSON and SSE completion rendering;
- missing/malformed `andchamber_phone` tool schema rejection without a tool call;
- direct `begin`: `request` first, accepted `RUNNING`/`READY`/`NEEDS_USER` with fresh epoch,
  auto-continue, and rejection of plain text/PENDING/stale replies;
- native continuation prompt recognition: exact fixed prompt, no second sentinel, real
  observation/action, and no acceptance-script continuation prompt;
- `apps` only returns the preauthorized fixture package; `open_app` returns `OPENED` and a final
  snapshot proves the same package in front;
- tool-result envelope parsing, epoch/binding/ref use, one action per scenario, and fixed
  pass/fail completion text;
- input privacy witness: the non-editable `fixture_input_echo` marker and exact typed value are
  required; an editable label alone is rejected; the password field is never mirrored;
- capture wire format: one exact text envelope in a tool multipart with known media parts is
  accepted; two text parts or unknown part shapes are rejected; a following image user attachment
  is ignored; the test-only provider config declares image input;
- `ask_choice`: `request` precedes the built-in `question`; missing/malformed question schema is
  refused without a fake call; a pending, wrong, unknown or free-form answer stops without a
  mutation; an exact listed choice runs the `tap_save` or `input` sequence once on the same task;
  the question roletool result cannot impersonate a phone envelope; JSON and SSE name `question`;
  and logs keep only the tool name, not question/answer/arguments;
- loopback HTTP server, model listing, transcript count/no-prompt logging;
- offline target APK build, signature verification, manifest package/label/launchable activity,
  absence of `INTERNET`, `fixture_input_echo` resource presence, and `classes.dex` presence;
- the target still compiles with the test-only bounded busy window, which adds no permission, no
  service and no network call.

In the full acceptance run, Node totals were **349 tests: 348 passed, one existing
stock-ChatContainer fixture skipped, no failures**. The local commands above cover only the fixture
subset; the parent owns the complete suite.

Not verified here: installing the target APK, the real guest runtime, standing save/restart/RPC
readiness, the real OpenCode model loop, native continuation delivery, real uiautomator/ADB actions,
and real captures. Those belong to the orchestrator/emulator run. The API 35/x86_64 emulator run is the only device claim; physical
arm64/OEM, TalkBack and real-model reasoning are not established by these fixtures.

The `ask_choice` focused Node tests do not model or close the runtime's pending-form schema
(`q0`, `custom:true`), do not render the overlay choice, and do not deliver a real notification or
SystemUI reply. The fixture test only proves the built-in `question` function/schema is present and
that the fixture asks the two fixed options; the real harness must independently verify the pending
form, `custom:true`, overlay display and answer round-trip on the device. The native choice sender
accepts only the listed option values. No device pass is claimed by this section.

## 6. Native UI smoke (phone entry / off state)

`app/src/androidTest/java/io/andchamber/app/phone/smoke/PhoneNativeSmokeTest.kt` is a small,
deterministic native-UI smoke for the experimental entry. It uses only platform
`UiAutomation`/`AccessibilityNodeInfo` and `ActivityScenario`; there is no Compose test library,
UiAutomator dependency or fake grant.

It checks:

- a fresh debug test app starts with phone control off;
- the real `MainActivity` Home exposes the phone entry and it opens the native phone screen;
- the screen shows the experimental/risk/off copy, the risk acknowledgement is unchecked, and the
  enable action is disabled. Attempting the disabled action does not turn phone control on;
- Back returns to Home with phone control still off; `RuntimeService` and `PhoneControlService`
  are not running;
- phone strings exist in both English and Chinese resources, and a few are actually translated;
- on an emulator only, a narrow 720x1280 display plus font scale 1.5 still lets the entry open,
  the enable/risk controls be reached, and Back return Home. The test saves and restores the
  original `wm size`, `wm density`, and `system font_scale` in a finally block.

Safety rules in the test: it refuses `io.andchamber.app`, requires the phone store to be off before
launch, only sets the UI auto-open preference (not the phone switch), never calls the controller,
never grants/pairs/connects, never reads another app's screen, and closes its activity. All shell
calls go through the instrumentation's own `UiAutomation`, never host adb discovery.

Build and run (fresh test package; explicit serial):

```sh
export ANDROID_SERIAL=emulator-5554   # the test emulator's serial, never auto-discovered
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -Pandchamber.testApplicationId=io.andchamber.phonesmoke
adb -s "$ANDROID_SERIAL" uninstall io.andchamber.phonesmoke >/dev/null 2>&1 || true
adb -s "$ANDROID_SERIAL" uninstall io.andchamber.phonesmoke.test >/dev/null 2>&1 || true
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class io.andchamber.app.phone.smoke.PhoneNativeSmokeTest \
  -e expectedPackage io.andchamber.phonesmoke \
  io.andchamber.phonesmoke.test/androidx.test.runner.AndroidJUnitRunner
```

The package is a debug test id and must be installed fresh; a reused package with phone control
already enabled fails the default-off assertion instead of silently continuing.

`.github/workflows/phone-smoke.yml` runs this class on an API 35 x86_64 emulator for
`workflow_dispatch` and phone-related pull-request paths. It builds the app and test APK in the
same job, uses the existing network source-preparation and real NDK/PRoot build path, and runs adb
with an explicit `ANDROID_SERIAL` (no host device discovery). On failure it uploads only the
instrumentation output and a screenshot of the fresh smoke UI; it does not upload cookies, keys,
`adb_keys`, or logcat.

Scope limit: this is native UI/entry smoke only. A green run does not claim TLS, wireless-debugging
pairing, the OpenCode tool loop, or real phone actions; those remain the separate fixture/emulator
E2E lane above and are not exercised by this smoke.

Locally this lane was compile/packaging checked with:

```sh
./gradlew :app:compileDebugAndroidTestKotlin -Pandchamber.testApplicationId=io.andchamber.phonesmoke
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pandchamber.testApplicationId=io.andchamber.phonesmoke
```

The full API 35/x86_64 acceptance run later executed the device instrumentation lane with **20/20
passed**. This document's local smoke commands remain compile/packaging checks; the instrumentation
requires the emulator owned by the final orchestrator. The smoke does not stand in for pairing, the
tool loop, native continuation or overlay behavior.
