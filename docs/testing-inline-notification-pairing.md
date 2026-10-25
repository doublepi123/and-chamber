# Inline notification pairing verification

## Contract

Text questions must be answerable without opening an AndChamber Activity. Android's wireless-debugging
pairing dialog has to remain open while a same-device client pairs. Opening a separate native form
is not an acceptable substitute for this notification interaction.

An inline form stages one field at a time in process memory. Only the final answer submits the whole
map. OpenCode question fields with `options` and explicit `custom: true` are text-capable; closed
choices are not silently converted into free text. The notification must not echo staged answers or
server error bodies. Lost drafts restart from the beginning and never submit a partial map.

## Real end-to-end path

`NotificationPairingE2ETest` refuses any package except `io.andchamber.pairprobe` and requires an
x86_64 emulator. It uses the production application graph, installer, runtime, notification sink,
notifier, receiver and local API client. The host drives the actual Settings and SystemUI controls.

1. Build and install the isolated APK and androidTest APK:

   ```sh
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
     -Pandchamber.testApplicationId=io.andchamber.pairprobe
   adb -s <emulator-serial> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <emulator-serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   ```

2. Enable Wireless debugging on the emulator. Do not pair it using the host's ADB client. Start:

   ```sh
   adb -s <emulator-serial> shell am instrument -w -r \
     -e class io.andchamber.app.browser.NotificationPairingE2ETest \
     -e runId inline-pair-example \
     io.andchamber.pairprobe.test/androidx.test.runner.AndroidJUnitRunner
   ```

3. The test starts the real local server and installs `android-tools-adb=35.0.2-r21` **only inside
   the test guest**. The APK hash and installed version are checked. This does not add ADB to the
   production runtime package list. The guest ADB server uses private keys and port 15037.
4. The real OpenCode API creates a session form with `q0`, `q1`, `q2`, suggestions and `custom: true`.
   OpenChamber's normal event stream produces the notification; the test does not call `post()`.
5. At `await-inline-answers`, read the connection port from Wireless debugging, then open its pairing
   code dialog. Expand the notification shade and, if necessary, the AndChamber notification group.
   Enter the pairing port, pairing code and connection port through SystemUI's real inline reply box.
   Do not tap an app form, send an explicit broadcast, invoke `PendingIntent.send`, or POST a reply
   from the host script.
6. The test confirms the server form stays pending after replies one and two. After reply three it
   reads the settled answer from the real server, then the guest executes loopback `adb pair`,
   `adb connect`, `get-state`, and `shell echo PAIR_OK`.
7. Successful evidence requires actual `device` and `PAIR_OK` outputs, not just zero exit status or
   a rendered input field. Verify no AndChamber Activity was created/resumed during notification input.

Status and evidence are in the isolated package's `files/local-provider-e2e/current/` directory.
Pairing values and private keys must never be copied into repository files or published logs. The
test stops its own guest ADB server and local runtime; it never stops another application's service.

## Recorded evidence

The final integrated code passed both forms on the API 35 x86_64 emulator:

- `inline-pair-final-multi`: three real SystemUI replies, 77.755 seconds; the first two replies left
  the real server form pending, the third settled it. Guest ADB reported `device` and `PAIR_OK`.
- `inline-pair-final-single`: one real SystemUI reply to a single custom question, 56.226 seconds;
  the server returned the complete answer and guest ADB reported `device` and `PAIR_OK`.
- Neither final run needed a retry, launched the app Activity during input, or used host `adb pair`.
- All 18 notification instrumentation regressions passed, including first-step RemoteInput,
  action identity, no answer history, recovery broadcasts and revision safety. The full JVM suite
  passed 1303 tests with no failures or skips; lint and Debug/Release/androidTest builds passed.

### Earlier diagnostic runs

On an API 35 x86_64 emulator, run `inline-pair-v3` passed using three actual SystemUI replies:

- each step had a free-form RemoteInput broadcast and no Activity action or content intent;
- the server form remained pending after each of the first two replies;
- the last reply settled the whole answer map;
- the guest ADB client paired and connected on loopback, read `device`, and returned `PAIR_OK`;
- the host activity-event check found no test-app Activity launch during the replies;
- no host `adb pair` was used, and pairing codes were absent from status and evidence JSON.

Raw evidence is retained outside the repository in `/tmp/opencode/notification-inline-pairing/`.
Run `inline-pair-single-v4` additionally exercised a single `q0` field with suggestions and
`custom: true`: one real SystemUI reply contained the three values separated by spaces. A pre-send
pending check timed out; the notification retained the local answer, and an explicit user Retry then
settled the real form. The guest again read `device` and `PAIR_OK`, with no test-app Activity launch
during input. This run required a manual retry and is not reported as an uninterrupted first-attempt
pass. Enable this variant with `-e singleField true`; without it the test uses three fields.

This result covers the exercised normal path. Separate unit/device tests cover replay, draft loss,
notification revisions and failure handling. It does not establish arm64, OEM SystemUI, or a real
model-generated conversation: the question-shaped form is created through the public API without
calling a model.
