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
| `open` | Open one public `https` URL in the in-app window; a ready foreground page defaults to agent control |
| `snapshot` | Read a text snapshot and element refs |
| `click` | Activate a ref from the latest snapshot |
| `type` | Type into a snapshot ref; optional `submit:true` sends one native Enter and confirms a supported GET navigation |
| `scroll` | Scroll `up` or `down` |
| `capture` | Capture the browser view as PNG |
| `resize` | Choose mobile/desktop page layout and an optional fixed desktop resolution |

The plugin reads `ANDCHAMBER_BROWSER_TOOL_FILE` on every call. If the file is absent or unreadable it
returns a plain "not available" text error. It never prints the token, and it does not retry on abort.

## How it works

1. The App starts the local runtime and, once it is ready, starts a loopback tool server on an
   ephemeral port. A per-launch token is written only into the guest endpoint file
   (`.local/state/andchamber/browser-tool.json`, mode 0600); it is never in the environment, the
   config overlay, or a log line.
2. The tool server accepts only `POST /v1/tool` and `POST /v1/cancel` with the bearer token from the
   endpoint file, loopback peers, and no `Origin` header.
3. The browser is agent-first. An explicit open defaults to agent control once the page is ready in
   the foreground. The presenter still verifies the current connection, chat owner, tab and document
   before granting control; opening a page does not bypass these checks.
4. A person can take over at any time. A page that is not ready or whose control was revoked remains
   undrivable. `open` reports `drivable:false` with `reason:"authorization_required"` when no current
   grant is available. A person's restore action alone does not restore a revoked grant. A later agent
   action can surface a hidden page as a small window and request control again, but cannot override
   an explicit takeover or bring the app forward from the background.
5. Control is revoked when the browser leaves the foreground, the activity pauses, the user explicitly
   takes over, the window is hidden, the connection changes, or the runtime stops. Scrolling the page,
   dragging the window and resizing it do not themselves revoke control.
   An explicit takeover cannot be overridden by agent opens or actions. The person can choose
   **Resume agent** or explicitly open another page to request control again. Ordinary page callbacks
   and foreground restoration never silently re-enable revoked control.

Sign-in cookies can remain in the browser's own profile, `andchamber_browser_v1`. They are not copied
from, or into, the OpenChamber page profile.

## Phone window interaction

- The window has a visible address bar, address editing and reload, full-screen/small-window switching,
  a hide action, and an explicit takeover action. Closing a page is separate from hiding its window.
- Mobile pages keep a phone-sized viewport. Desktop pages keep a separate fixed viewport, defaulting
  to **1920 × 1080 CSS pixels**. Resizing the small window scales its presentation,
  rather than repeatedly changing the website's responsive layout. Full screen uses the same page,
  document, refs and scroll position; switching presentation does not pause agent work.
- Ordinary scrolling, window dragging and resizing do not hand control away from the agent. Use the
  explicit takeover action when the agent should stop. A foreground agent action can surface a hidden
  window again, but cannot override that explicit takeover.
- Native touch and agent coordinates are measured in the unscaled page; the window transform maps
  them to its displayed page rectangle. Capture must validate that transformed rectangle rather than
  copying an unscaled WebView-sized area of the chat window.

### Agent-selected page layout

The agent can choose `viewport:"mobile"` or `viewport:"desktop"` for the site and task without asking
the person to select a layout. Desktop `open`/`resize` calls may include both integer `width` and
`height`: width 640–3840, height 360–2160. Invalid or one-sided sizes are refused, never clamped. Mobile
uses the device's available browser area and refuses custom desktop dimensions.

```json
{"action":"open","url":"https://example.com/","viewport":"desktop"}
{"action":"resize","viewport":"desktop","width":1366,"height":768}
```

The chosen desktop resolution belongs to the tab and survives a controlled submit, reload, or a
mobile/desktop round trip. Changing the actual page viewport can reload an eligible controlled GET
and invalidates old refs; take a new snapshot. Configuration responses marked `geometry:"pending"`
are not DOM measurements. Snapshots expose the measured layout and visual viewports; the app does not
spoof device pixel ratio or `screen`. Desktop's minimum page scale is the configured baseline so wide
overflow content cannot expand its fixed viewport; pinch can zoom in but not out below that baseline.
WebView's density conversion can introduce about two CSS pixels of rounding; snapshots report the
actual measurements rather than replacing them with the requested values. A captured PNG is still
the displayed page rectangle, not a claim to an unscaled 1920 × 1080 screenshot.

### Type and Enter submission

`submit` absent or `false` keeps ordinary typing. For `submit:true`, the target must be an editable,
non-sensitive single-line input. Known POST forms, credential/file-bearing forms, unsupported default
submitter overrides and non-current-tab targets are refused before typing. The app fills the value,
rechecks the same input and focus, then sends exactly one native Enter DOWN/UP pair without forcing
the software keyboard open.

The input/Enter acknowledgement alone is not success. The original request waits for a supported
same-tab main-frame GET, preflights its URL and DNS off the main thread, replaces the view in the same
tab, and confirms the new document witness and grant. Old refs expire. POST and redirect loads are
not replayed as GET. No navigation, timeout, cancellation or an uncertain outcome is not reported as
a successful search and never causes an automatic second Enter. Arbitrary in-page handler completion
without a main-frame GET is not inferred from a DOM change.

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
- Use `open` or a supported `type(submit:true)` for top-level document navigation under agent control.
  Choose **Take over** before following other site-driven links or forms by hand; ordinary touching and scrolling no longer switch the
  page into manual-navigation mode. Unsupported POST/redirect documents are not made controllable
  merely by restoring the window.
- There is no production path that uses root or ADB. WebView remote debugging and CDP are not enabled.
- The driver does not promise a trusted gesture, a solved CAPTCHA, an iframe DOM, arbitrary `eval`,
  cookie export, file upload, or unlimited background use.
- Password and file fields are refused. Emulator checks confirmed viewport-sized PNG capture; video,
  WebGL and hardware-layer pixel fidelity have not been verified.
- Capture requires confirmation that the page's current presentation has reached a committed hardware
  frame. If that confirmation is unavailable (including Android versions without frame-commit
  callbacks), capture fails explicitly; page browsing and agent control do not fall back to software
  screenshots or copy a merely pre-drawn rectangle.
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
  session id, then drive a page granted for this launch. An explicit foreground open defaults to
  requesting a grant after readiness checks; the person's tap is not an authentication boundary.
  Grants remain bound to the connection, session and tab. Mitigations: a new token every launch,
  `127.0.0.1` only, and on stop the endpoint file is
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

The grant, fixed-viewport and site-data isolation regressions use the normal AndroidJUnitRunner
(rebuild the test APK without the smoke-runner property if it was enabled):

```bash
./gradlew :app:assembleDebugAndroidTest
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w \
  -e class io.andchamber.app.browser.NativeBrowserDefaultGrantInstrumentationTest,io.andchamber.app.browser.BrowserSiteDataIsolationInstrumentationTest \
  io.andchamber.app.test/androidx.test.runner.AndroidJUnitRunner
```

`BrowserSubmitInstrumentationTest` adds native Enter/GET arrival, special-character query encoding,
same-tab replacement/ref expiry, sensitive/POST refusal and hide-during-submit checks.
`BrowserDesktopViewportInstrumentationTest` reads the actual 1920 × 1080 and custom desktop layout,
visual viewport, CSS rulers, wide overflow and dynamic meta behavior; it also checks agent resize and
presentation-only window changes. Both use the same normal AndroidJUnitRunner and in-memory HTTPS
fixtures, not a synthetic grant or a manually constructed search URL.

The emulator regression set also covers the ten product scenarios and the six lifecycle/two
retirement scenarios in `scripts/verify_local_provider_product.cjs` and
`scripts/verify_local_provider_matrix.cjs`. These require the dedicated local-provider test package
and its installed runtime; they never install the runtime for a missing setup. POST scenarios make
an explicit human takeover and run last, so later cases cannot silently reclaim that choice.

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
