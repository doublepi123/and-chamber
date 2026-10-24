# app/src/main/java/io/andchamber/app/ui/browser/

## Responsibility

The in-app floating browser over the local chat. One public-https page the user can browse, and that
an agent may use by default after a foreground open passes the current grant checks. Takeover or hiding
revokes control; restoring the window does not restore a grant. It is not the OpenChamber page, not the
WebView pool, and not a `WebSessionProfiles` profile.

Files:
- `NativeBrowserPresenter.kt`: the single authority. Owns pages, tabs, grants, connection ids and
  transport eligibility; implements the provider page executor. Per-page recovery separates an
  unconfirmed action (observation only) from an invalid document (reopen required).
- `BrowserFloatWindow.kt`, `BrowserFloatPage.kt`, `BrowserFloatLayout.kt`, `BrowserHostSurface.kt`:
  the Compose window, its header/menu/status and the host surface that reports the console session.
- `BrowserSession.kt`: one WebView, profile bind, clients, viewport capture.
- `BrowserDesktopViewport.kt`: bounded, paired desktop CSS dimensions (1920 × 1080 by default),
  retained per tab independently of phone window presentation.
- `BrowserSubmitNavigation.kt`: one-shot native Enter navigation receipts; callbacks report an event,
  while the original presenter action owns policy checks and confirmation. GET uses controlled
  replacement; verified same-document URL navigation retains the existing view and grant.
- `BrowserSiteData.kt`: the "Clear site data" action. Revokes the agent grant, then clears this
  profile's cookies and JavaScript storage, then the shown page's cache and history, then reloads it as
  a person's navigation. If the page cache/history step fails after cookies and storage are gone, the
  result is partial and the page is not reloaded.
- `BrowserPageDriver.kt`: the single `BrowserDriver` implementation.
- `BrowserDriverLogic.kt`: pure URL, ref, script-template and snapshot rules. Unit-tested without a
  WebView.
- Support: `BrowserGeometry.kt`, `BrowserTouchFocus.kt`, `MouseInput.kt`, `ScrollMemory.kt`,
  `WindowRestoreTargetGate.kt`, `PixelCopyBridge.kt`, `BrowserRetiredPage.kt`.
- `FixedBrowserViewportLayout.kt`: a fixed phone-sized or configured desktop child viewport and native presentation transform;
  dragging, resizing and full-screen switching change the display rectangle, not the page layout.
  The page is fitted with one uniform scale, up or down (never cropped or stretched), so a desktop
  canvas smaller than a tablet's full screen is enlarged and only the aspect difference is banded.
  With the keyboard open the page keeps the keyboard-closed scale (`keyboardViewportTransform`,
  reference area from the window layout): it is cut by the container and moved so the tapped point
  is above the keyboard, never shrunk. Capture then refuses, because the whole page is not visible.
  `NativeFrameCommitGate` reports a presentation only after the platform commits its frame (API 29+).

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
  OpenChamber URL and saved remotes. A submit's controlled continuation additionally preflights DNS
  on `Dispatchers.IO` before loading its replacement.
  Subresource and frame loads are syntax-checked in `shouldInterceptRequest` and replaced with an empty
  403 when refused. This is not a complete outbound firewall.
- A controlled top-level GET always replaces the WebView with a new one on `andchamber_browser_v1`.
  Each view has an immutable `viewId` and one navigation ticket. Only the latest ticket may replace
  the holder. `DOCUMENT_START_SCRIPT` installs a read-only document id before the first load. Without
  that feature, automation fails closed. Ready requires a probe of that witness on the current view
  and ticket, plus a policy-allowed URL. Callback URLs do not prove the document. A POST or an unknown
  main-document start is NotReady and cannot be saved by a later finished event. Page-reported text is
  not treated as verified. Caller values are inserted once as one JSON argument. Type uses the native
  input value setter. `submit:true` adds one native Enter for a safe single-line input; the original
  action waits for a native GET receipt and a new ready/granted view, or a verified same-document
  URL navigation under its original grant. Native history callbacks are candidates only: fresh
  URL/witness and native URL checks must agree with the exact armed Enter identity. Same-document
  success says navigation was observed, not that SPA data or a business action completed; old refs
  expire without reloading or repeating Enter. POST,
  credential/file-bearing forms and unsupported submitter overrides are refused, with no automatic
  second Enter. Other site-driven navigation needs the user to take over.
- Submit uses a work deadline before the transport's hard timeout, leaving bounded time for recovery
  classification and its reply. Cancel still propagates immediately; final Session disarming runs on
  Main and only for the original request/page. A URL change during typing, before Enter, is not a
  submission receipt.
- Desktop layout uses an exact fixed native child canvas for the configured CSS dimensions and a
  document-start viewport width/initial scale derived from real DPR; desktop overview auto-shrink is
  disabled. Agents may choose mobile/desktop and paired custom desktop dimensions on open/resize.
  Native dimensions and CSS/visual viewport probes remain separate; presentation changes do not
  resize the page. A true mode/resolution change uses the presenter's owned reload/invalidation path,
  not a second UI invalidation.
- Capture samples the native viewport wrapper's presentation (`BrowserViewportHost.presentationSnapshot()`),
  maps the drawn page to whole window pixels, and requires the complete page to be inside the window and
  unclipped; otherwise capture is refused, so the rest of the app is not copied. The live tuple must also
  equal the one the platform confirmed with a real frame commit (`registerFrameCommitCallback`, API 29+);
  a new revision or pre-draw alone is not proof that the window buffer changed. On older APIs or software
  windows capture fails closed. A presentation move only discards that attempt (bounded retry); it never
  clears refs or revokes a grant.
- Explicit foreground opens default to agent control once ready. A grant binds one runtime launch, connection id, console session
  and visible tab. The plugin tool server connects only while the presenter reports
  `transportEligible`; losing eligibility (background, console change, detach) clears the connection
  and revokes the grant. A reconnect never restores a grant. Explicit Take over or the
  window closing revokes control. Page scrolling and window dragging/resizing do not revoke it.
- Test entry points (`attachHostForTest`, `installPageDecorator`, `beginForTest`,
  `acceptProbeForTest`) refuse to run outside a debug build.
- Snapshot/capture errors and missing scroll replies do not alone invalidate the page. An uncertain
  dispatched click/type restricts it to snapshot/capture under the existing verified grant, without
  replaying that action. Only explicit user resume can clear the action uncertainty after rechecking
  identity; document-invalid states require a fresh load. `NativeBrowserState.requiresReopen` and
  `actionOutcomeUnknown` drive the recovery UI, never website text.

## Flow

1. `BrowserHostSurface` reports the trusted console's session id; the presenter revokes on an owner
   change. The console page also offers "Open web page" for a public `https` URL.
2. The agent calls the plugin tool `andchamber_web`; the App tool server maps it to a
   `ProviderRequest` and the presenter executes it.
3. A ready foreground open requests a grant using the same connection, owner and document checks as
   the person's control action. Snapshot, click, type, scroll, capture and resize still require it.
4. Take over / hide / close revoke. Leaving the local chat revokes through transport eligibility.
   Restoring presentation alone does not restore control.

## Integration

- `ui/web/WebScreen.kt` and `ui/web/ChamberWebView.kt` pass the console session id and the
  "Open web page" action; they do not create a page.
- `browser/BrowserContract.kt` is the shared driver contract, including TYPE submit and RESIZE.
- `browser/BrowserUrlPolicy.kt` is called, not modified; submit DNS preflight runs on `Dispatchers.IO`.
- `runtime/process/LocalBrowserProviderBinding.kt` owns the loopback tool server for one launch.
- Strings live in `values/strings.xml` and `values-zh/strings.xml` under the `browser_` keys.
