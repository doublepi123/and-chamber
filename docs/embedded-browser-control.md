# Embedded browser control

**Experimental. Verified on an Android emulator.** The arm64 phone runtime is not yet part of the
verified set.

This is a separate in-app browser, not the OpenChamber page and not an OpenChamber browser tool.
OpenCode reaches it through an App-injected OpenCode plugin tool named `andchamber_web`. The plugin
is a small directory under the guest home; the App syncs it on every start and names it in the
launch-only `OPENCODE_CONFIG_CONTENT` overlay. No patch to `@openchamber/web` is installed.

## What the agent can call

One tool, one action per call:

| Action | Purpose |
|---|---|
| `open` | Open one public `https` URL in the in-app window, then wait about a minute for the person's Allow |
| `snapshot` | Read a text snapshot and element refs |
| `click` | Activate a ref from the latest snapshot |
| `type` | Type into a ref from the latest snapshot |
| `scroll` | Scroll `up` or `down` |
| `capture` | Capture the browser view as PNG |
| `resize` | Resize the window |

The plugin reads `ANDCHAMBER_BROWSER_TOOL_FILE` on every call. If the file is absent or unreadable it
returns a plain "not available" text error. It never prints the token, and it does not retry on abort.

## How it works

1. The App starts the local runtime and, once it is ready, starts a loopback tool server on an
   ephemeral port. A per-launch token is written only into the guest endpoint file
   (`.local/state/andchamber/browser-tool.json`, mode 0600); it is never in the environment, the
   config overlay, or a log line.
2. The tool server accepts only `POST /v1/tool` and `POST /v1/cancel` with the bearer token from the
   endpoint file, loopback peers, and no `Origin` header.
3. The user asks in chat. A page opens unauthorized. The agent can open it, but snapshot, click,
   type, scroll and capture are refused until the person taps **Allow agent** in the window.
4. `open` does not return at once: after the page loads it waits up to about a minute for that tap.
   When the person allows the page during the wait, `open` answers `drivable:true`; otherwise it
   answers `drivable:false` with `reason:"authorization_required"` and the agent asks the person to
   tap Allow. The wait never grants anything; it only observes the presenter. Until the person allows
   it, the floating window says "The agent opened this page. Tap Allow agent to let it use the page."
5. Control is revoked when the browser leaves the foreground, the activity pauses, the user touches
   the page, the user takes over, the connection changes, or the runtime stops. A new grant is
   required after every revoke. There is no silent re-enable.

Sign-in cookies can remain in the browser's own profile, `andchamber_browser_v1`. They are not copied
from, or into, the OpenChamber page profile.

## Limits

- Pages must be public `https`. This is not a complete outbound firewall. Redirects, non-document
  requests and subresources have separate restrictions, and a refused subresource is not proof that
  nothing else left the device.
- A controlled GET navigation replaces the view, stays on this profile, and requires `MULTI_PROFILE`
  and `DOCUMENT_START_SCRIPT`. Missing support does not fall back to another profile or a weaker
  document identity check.
- POST, unrecognized redirects and complex login flows are not supported in this controlled browser.
  They are not replayed as GET. Use an external browser for unsupported flows; its cookies are not
  imported here.
- There is no production path that uses root or ADB. WebView remote debugging and CDP are not enabled.
- The driver does not promise a trusted gesture, a solved CAPTCHA, an iframe DOM, arbitrary `eval`,
  cookie export, file upload, or unlimited background use.
- Password and file fields are refused. Emulator checks confirmed viewport-sized PNG capture; video,
  WebGL and hardware-layer pixel fidelity have not been verified.
- The endpoint file is private to the runtime home. The App never edits the user's persisted
  `opencode.json(c)`. If browser preparation fails, the main runtime still starts and no plugin entry
  is injected.
- This does not isolate another Node process running as the same app UID. A process that can already
  read the private runtime home can read what that home contains.
- An upgrade removes only this App's own legacy browser-control files under the guest home. It never
  touches the user's config, shared storage, or the WebView profile.

## Limitations and accepted risks

These are known and accepted for this experimental feature. Each is a deliberate trade-off for this
round, not a promise that it will be fixed.

- **The endpoint token is readable inside the guest.** A process in the guest that can read the
  private runtime home can read `.local/state/andchamber/browser-tool.json`, including its bearer
  token. An agent command can read that file and call the loopback endpoint claiming any console
  session id, then drive a page a person already allowed for this launch. A page nobody allowed stays
  undrivable: the grant needs the person's tap in the window and is bound to the connection, session
  and tab. Mitigations: a new token every launch, `127.0.0.1` only, and on stop the endpoint file is
  deleted and the port closed, so a token from an earlier launch reaches nothing.
- **The chat drawer has no entry for this browser.** The App does not change OpenChamber's workspace drawer
  and exposes no bridge for OpenChamber's `presentAndroidBrowserTab`, so its Browser pane cannot open
  the floating window. Manual entry is the right-edge handle menu, **Open web page**, offered on the
  local console for a public `https` address.
- **OpenChamber's own desktop-layout iframe browser panel cannot be hidden.** At wide layouts
  OpenChamber shows its built-in browser panel. This App installs no patch and injects no CSS to hide
  or replace it. That panel is not the floating browser, and the agent tool never uses it.
- **Re-enabling OpenChamber's own web tool gives the agent two web tools.** The App writes
  `agentWebToolEnabled: false` into the instance settings only when that key is absent, and leaves a
  present value alone. If the person later sets it to true, the agent sees both OpenChamber's web
  tool and `andchamber_web`.
- **No hover in Mouse mode while an accessibility service is on.** Chromium hands hover events to
  accessibility, which consumes them, so the page receives no `pointermove`. The window says so, and
  clicks, drags and scrolling still work. See
  `.slim/deepwork/openchamber-browser-provider/phase-3-hover-a11y-exception.md`.
- **The chat session comes from the console URL.** `?session=<id>` is read from the console URL and
  refreshed on document commits and history-API navigation. A switch that only changes in-page state
  without changing the URL is not seen, so control stays tied to the previous session id until the
  URL changes.

## Verification

Existing CI, from the repository root:

```bash
python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --stacktrace
```

Node tests for the plugin and the in-page scripts, with Node on `PATH` (no package dependency):

```bash
node --test app/src/test/js/*.cjs scripts/tests/browser-tool-plugin.test.cjs
```

The offline gate-adapter tests can be run the same way:

```bash
node --test scripts/tests/*.test.cjs
```

Debug APK path, relative to the repository:

```text
app/build/outputs/apk/debug/
```

Instrumentation is not part of the current CI workflow. Start with a dedicated emulator; a physical
device is optional. Set `$SERIAL` explicitly. The smoke runner is not in the default instrumentation
APK. Build that APK with the boolean property, then name the runner on the `am instrument` command:

```bash
./gradlew :app:assembleDebugAndroidTest -Pandchamber.browserSmokeRunner=true
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w io.andchamber.app.test/io.andchamber.app.browser.BrowserSmokeInstrumentation
```

Set `$SERIAL` to an emulator or device dedicated to testing. The native runner reports per-check
results and `summary=PASS`; a successful `adb` exit alone does not establish that the checks passed.
Do not record a personal path, address, or device id in this file.
