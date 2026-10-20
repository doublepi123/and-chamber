# Embedded browser control

**Experimental. Verified with deterministic fixtures on an Android emulator.** The full on-device OpenCode → MCP → WebView chain on an arm64 phone has not yet been verified.

This is a separate in-app browser, not the OpenChamber page and not an existing OpenChamber browser tool. OpenCode reaches it through a local stdio MCP server named `andchamber_browser`. Putting that server in the in-memory MCP overlay does not make any built-in OpenChamber browser tool connect to this page.

## What the agent can call

The adapter exposes six tools:

| Tool | Purpose |
|---|---|
| `browser_navigate` | Open one public `https` URL |
| `browser_snapshot` | Read a text snapshot and element refs |
| `browser_click` | Activate a ref from the current snapshot |
| `browser_type` | Type into a ref from the current snapshot |
| `browser_scroll` | Scroll `up`, `down`, `top`, or `bottom` |
| `browser_capture` | Capture the browser view as PNG |

The adapter reads a private endpoint file on every `tools/call`. It does not follow redirects and it does not retry.

## How to use it

1. Start the local runtime and wait until it is ready. Control is refused unless the foreground local runtime has a ready launch id.
2. In chat, ask the agent to use the embedded browser. Leaving the browser screen revokes control, so enable it only after the task has been sent.
3. On Home, open Browser.
4. Turn control on from that screen. A new grant is required after every revoke. There is no silent re-enable.

Control is revoked when the browser leaves the foreground, the activity pauses, the user touches the page, the user takes over, the lease expires, the runtime stops, or the runtime process dies. A replacement runtime launch also drops the previous lease.

Sign-in cookies can remain in the browser's own profile, `andchamber_browser_v1`. They are not copied from, or into, the OpenChamber page profile.

## Limits

- Pages must be public `https`. This is not a complete outbound firewall. Redirects, non-document requests, and subresources have separate restrictions, and a refused subresource is not a proof that nothing else left the device.
- A controlled GET navigation replaces the view, stays on this profile, and requires `MULTI_PROFILE` and `DOCUMENT_START_SCRIPT`. Missing support does not fall back to another profile or a weaker document identity check.
- POST, unrecognized redirects, and complex login flows are not supported in this controlled browser. They are not replayed as GET. Use an external browser for unsupported flows; its cookies are not imported here.
- There is no production path that uses root or ADB. WebView remote debugging and CDP are not enabled.
- The driver does not promise a trusted gesture, a solved CAPTCHA, an iframe DOM, arbitrary `eval`, cookie export, file upload, or unlimited background use.
- Password and file fields are refused. Emulator checks confirmed viewport-sized PNG capture; video, WebGL, and hardware-layer pixel fidelity have not been verified.
- The credential file is private to the runtime home. Its token is not written into shared config, the MCP command, or the process environment. The MCP overlay is injected in memory for that launch and keeps the user's other inline config. If browser preparation fails, the main runtime still starts and this server is not registered.
- This does not isolate another Node process running as the same app UID. A process that can already read the private runtime home can read what that home contains.

## Verification

Verified again after integrating the v0.0.6 baseline for the v0.0.7 release candidate:

- 471 JVM tests, 15 Node adapter tests, 9 host-runner helper tests, and 46 Python tests passed.
- Debug APK, instrumentation APK, and Android lint passed.
- Android emulator with WebView 124: all 17 smoke checks passed, including profile Cookie/storage isolation, document-start support, absent host bridge, snapshot, click, text input, stale refs, generation invalidation, fresh-view navigation, viewport PNG, loopback refusal, and POST remaining refused after a finished callback.
- Host Node running the production MCP adapter → same-port ADB forwarding → emulator production RPC/controller → real WebView: all 26 end-to-end checks passed. These include actual text/value changes, a single click's visible effect, observed scrolling, viewport PNG, replacement navigation, explicit takeover, reauthorization, background revocation, and credential/forward cleanup. Visual inspection of the returned PNG confirmed the typed marker, button, click count, and scroll position.
- This bridge test uses an `androidTest`-only ready runtime ID and native grant commands. It does not run OpenCode or an Android guest runtime, send a model prompt, or weaken production authorization. It is not an arm64 runtime end-to-end result.
- Manual public HTTPS browsing worked on the emulator. Full local-runtime control on an arm64 phone, React controlled forms, renderer crash recovery, and hardware-layer screenshot fidelity remain unverified. Plain-input tests do not establish React compatibility.

Existing CI, from the repository root. The default instrumentation APK uses `AndroidJUnitRunner`:

```bash
python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --stacktrace
```

Node adapter tests, with Node on `PATH`. This adds no package dependency:

```bash
node --test scripts/tests/browser-mcp.test.cjs scripts/tests/verify_browser_mcp_emulator.test.cjs
```

Debug APK path, relative to the repository:

```text
app/build/outputs/apk/debug/
```

Instrumentation is not part of the current CI workflow. Start with a dedicated emulator; a physical device is optional. Set `$SERIAL` explicitly. The smoke runner is not in the default instrumentation APK. Build that APK with the boolean property, then name the runner on the `am instrument` command:

```bash
./gradlew :app:assembleDebugAndroidTest -Pandchamber.browserSmokeRunner=true
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w io.andchamber.app.test/io.andchamber.app.browser.BrowserSmokeInstrumentation
```

Set `$SERIAL` to an emulator or device dedicated to testing. The native runner reports per-check results and `summary=PASS`; a successful `adb` exit alone does not establish that the checks passed. Do not record a personal path, address, or device id in this file.

### Host-to-emulator MCP bridge

The host runner only accepts `emulator-N` serials. Install both APKs first. The instrumentation APK must be the smoke-runner build from the command above. Then start the test-only bridge and immediately run its host client:

```bash
OUT=$(mktemp -d /tmp/opencode/browser-mcp-e2e.XXXXXX)
adb -s "$SERIAL" shell am instrument -w -r -e mcpBridge true \
  io.andchamber.app.test/io.andchamber.app.browser.BrowserSmokeInstrumentation \
  > "$OUT/instrumentation.log" 2>&1 &
runner_pid=$!
printf '%s\n' "$runner_pid" > "$OUT/instrumentation-host.pid"
node scripts/verify_browser_mcp_emulator.cjs --serial "$SERIAL" --out "$OUT"
host_status=$?
wait "$runner_pid"
runner_status=$?
test "$host_status" -eq 0 && test "$runner_status" -eq 0
```

Check `result.json` for `PASS` with no failed checks and the instrumentation log for `BRIDGE_FINISHED`. The latter only means that native cleanup completed, not that the host checks passed. `capture.png` is the actual MCP-returned viewport image. Temporary credentials are private and removed by the runner; only its own ADB forwards are removed. The fixture has a 140-second lifetime and the host reserves time for cleanup.
