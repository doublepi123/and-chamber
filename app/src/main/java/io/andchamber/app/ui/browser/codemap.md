# app/src/main/java/io/andchamber/app/ui/browser/

## Responsibility

The in-app floating browser over the local chat. One public-https page the user can browse, and that
an agent may use only after the user taps **Allow agent**. It is not the OpenChamber page, not the
WebView pool, and not a `WebSessionProfiles` profile.

Files:
- `NativeBrowserPresenter.kt`: the single authority. Owns pages, tabs, grants, connection ids and
  transport eligibility; implements the provider page executor.
- `BrowserFloatWindow.kt`, `BrowserFloatPage.kt`, `BrowserFloatLayout.kt`, `BrowserHostSurface.kt`:
  the Compose window, its header/menu/status and the host surface that reports the console session.
- `BrowserSession.kt`: one WebView, profile bind, clients, viewport capture.
- `BrowserSiteData.kt`: the "Clear site data" action. Revokes the agent grant, then clears this
  profile's cookies and JavaScript storage, then the shown page's cache and history, then reloads it as
  a person's navigation. If the page cache/history step fails after cookies and storage are gone, the
  result is partial and the page is not reloaded.
- `BrowserPageDriver.kt`: the single `BrowserDriver` implementation.
- `BrowserDriverLogic.kt`: pure URL, ref, script-template and snapshot rules. Unit-tested without a
  WebView.
- Support: `BrowserGeometry.kt`, `BrowserTouchFocus.kt`, `MouseInput.kt`, `ScrollMemory.kt`,
  `WindowRestoreTargetGate.kt`, `PixelCopyBridge.kt`, `BrowserRetiredPage.kt`.

## Design

- The presenter is created once in `di/AppModule.kt`. The local web route resolves it from Koin and
  passes it to `BrowserHostSurface` / the floating window. No other component creates a second one.
- Profile name is the fixed `andchamber_browser_v1`. A fresh WebView is bound with
  `WebViewCompat.setProfile` before settings, clients or any load. `MULTI_PROFILE` missing or a bind
  failure destroys the view and shows the unavailable body. There is no fallback to `Default`, the
  pool, or a server profile.
- Hardening after a successful bind: JavaScript on, file and content access off, mixed content never,
  third-party cookies off on this profile's cookie manager, safe browsing on, DOM storage on (this
  profile's `localStorage`/`sessionStorage`; legacy WebSQL stays off), legacy database storage off. SSL
  errors are cancelled. Permission, popup, file chooser, download and new-window requests are refused.
  No `addJavascriptInterface`, no `setWebContentsDebuggingEnabled`, no global `CookieManager`, no proxy
  override, no `pauseTimers`.
- Navigation allow-list is `BrowserUrlPolicy` (public https only) plus forbidden origins for the local
  OpenChamber URL and saved remotes. The driver preflights DNS off the main thread before `loadUrl`.
  Subresource and frame loads are syntax-checked in `shouldInterceptRequest` and replaced with an empty
  403 when refused. This is not a complete outbound firewall.
- A controlled top-level GET always replaces the WebView with a new one on `andchamber_browser_v1`.
  Each view has an immutable `viewId` and one navigation ticket. Only the latest ticket may replace
  the holder. `DOCUMENT_START_SCRIPT` installs a read-only document id before the first load. Without
  that feature, automation fails closed. Ready requires a probe of that witness on the current view
  and ticket, plus a policy-allowed URL. Callback URLs do not prove the document. A POST or an unknown
  main-document start is NotReady and cannot be saved by a later finished event. Page-reported text is
  not treated as verified. Caller values are inserted once as one JSON argument. Type uses the native
  input value setter. Password and file fields are refused. Forms and pages that navigate themselves
  need the user to take over.
- Capture uses `PixelCopy.request(window, webViewRect, ...)`. The rectangle must be exactly the WebView
  and fully inside the window; otherwise capture is refused, so the rest of the app is not copied.
- Agent control is off by default. A grant binds one runtime launch, connection id, console session
  and visible tab. The plugin tool server connects only while the presenter reports
  `transportEligible`; losing eligibility (background, console change, detach) clears the connection
  and revokes the grant. A reconnect never restores a grant. A touch on the page, Take over, or the
  window closing revokes as before.
- Test entry points (`attachHostForTest`, `installPageDecorator`, `beginForTest`,
  `acceptProbeForTest`) refuse to run outside a debug build.

## Flow

1. `BrowserHostSurface` reports the trusted console's session id; the presenter revokes on an owner
   change. The console page also offers "Open web page" for a public `https` URL.
2. The agent calls the plugin tool `andchamber_web`; the App tool server maps it to a
   `ProviderRequest` and the presenter executes it.
3. A page opens unauthorized. Snapshot, click, type, scroll, capture and resize are refused until the
   user taps **Allow agent** in the window.
4. Take over / Turn off: revoke. Leaving the local chat revokes through transport eligibility.

## Integration

- `ui/web/WebScreen.kt` and `ui/web/ChamberWebView.kt` pass the console session id and the
  "Open web page" action; they do not create a page.
- `browser/BrowserContract.kt` is the frozen driver contract. This folder does not modify it.
- `browser/BrowserUrlPolicy.kt` is called, not modified. DNS preflight runs on `Dispatchers.IO`.
- `runtime/process/LocalBrowserProviderBinding.kt` owns the loopback tool server for one launch.
- Strings live in `values/strings.xml` and `values-zh/strings.xml` under the `browser_` keys.
