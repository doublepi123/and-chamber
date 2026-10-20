# app/src/main/java/io/andchamber/app/ui/browser/

## Responsibility

The independent in-app browser (`Routes.BROWSER`). One public-https page the user can browse, and that an agent may use only after the user turns control on. It is not the OpenChamber page, not the WebView pool, and not a `WebSessionProfiles` profile.

Files:
- `BrowserScreen.kt`: address bar, load and error line, control buttons, lifecycle revoke, `BrowserUnavailable`.
- `BrowserSession.kt`: one WebView, profile bind, clients, viewport capture.
- `BrowserPageDriver.kt`: the single `BrowserDriver` implementation.
- `BrowserDriverLogic.kt`: pure URL, ref, script-template and snapshot rules. Unit-tested without a WebView.

## Design

- The screen takes the app's `BrowserController` from `rememberBrowserDependencies()` (Koin). It does not construct a second one, and it does not start or stop the command listener. A missing controller (previews) shows `BrowserUnavailable` and loads nothing.
- Profile name is the fixed `andchamber_browser_v1`. A fresh WebView is bound with `WebViewCompat.setProfile` before settings, clients or any load. `MULTI_PROFILE` missing or a bind failure destroys the view and shows the unavailable body. There is no fallback to `Default`, the pool, or a server profile.
- Hardening after a successful bind: JavaScript on, file and content access off, mixed content never, third-party cookies off on this profile's cookie manager, safe browsing on, DOM storage off. SSL errors are cancelled. Permission, popup, file chooser, download and new-window requests are refused. No `addJavascriptInterface`, no `setWebContentsDebuggingEnabled`, no global `CookieManager`, no proxy override, no `pauseTimers`.
- Navigation allow-list is `BrowserUrlPolicy` (public https only) plus forbidden origins for the local OpenChamber URL and saved remotes. The driver preflights DNS off the main thread before `loadUrl`. Subresource and frame loads are syntax-checked in `shouldInterceptRequest` and replaced with an empty 403 when refused. This is not a complete outbound firewall.
- A controlled top-level GET always replaces the WebView with a new one on `andchamber_browser_v1`. The replacement does not call `attach`, so it does not revoke the lease. Each view has an immutable `viewId` and one navigation ticket. Only the latest ticket may replace the holder. `DOCUMENT_START_SCRIPT` installs a read-only document id before the first load. Without that feature, automation fails closed. Ready requires a probe of that witness on the current view and ticket, plus a policy-allowed URL. Callback URLs do not prove the document. A POST or an unknown main-document start is NotReady and cannot be saved by a later finished event. Enable and generation changes call `invalidateRefs` only. Page-reported text is not treated as verified. Caller values are inserted once as one JSON argument. Type uses the native input value setter. Password and file fields are refused. Forms and pages that navigate themselves need the user to take over.
- A navigation invalidates refs before `loadUrl`. Same-URL reload and a changed main-frame URL invalidate in `onPageStarted`. `execute` drops a result whose epoch no longer matches. `BrowserState.viewInvalidated` destroys the WebView and attaches a replacement, which is the only thing that clears that flag.
- Capture uses `PixelCopy.request(window, webViewRect, ...)`. The rectangle must be exactly the WebView and fully inside the window; otherwise capture is refused, so the rest of the app is not copied. A view with no size, or one that is detached or not shown, is refused. The software `draw` fallback can miss GPU layers. There is no device test for the pixels.
- Agent control is off by default. Enable needs a running local runtime and a non-null `readyLaunchId()`. Send the task in chat first: leaving this screen revokes the lease, so control cannot be enabled and then used from chat. Pause calls `setVisible(false)` immediately, then `disable`. A runtime drop or launch-id change calls `disable` only and does not mark the page invisible. Resume sets visible again and does not enable. Take over stays enabled while busy. A finger down, the Take over button and a manual Go call `takeOverFromUi()` on the main thread before the touch continues. `bindSession` installs the fatal callback on every view, including a timeout replacement. A callback from an older view does not clear the replacement. A renderer crash detaches and does not recreate an authorized view.

## Flow

1. In provider mode the Home card is hidden. `BrowserFloatPage` projects `NativeBrowserPresenter` above the local chat. It does not create a page or load a URL. The chat bridge only selects and closes tabs in that presenter.
2. If `browser == null`, show the not-ready panel. Else create the session, attach the driver, `setVisible(true)`.
3. Address bar Go: syntax plus DNS preflight, then load. A refusal stays on the current page and shows the refused line.
4. Allow agent: `enableFromUi()` only while local OpenChamber is running and the page is visible.
5. Take over / Turn off / Back: revoke. Back also leaves the route.
6. Commands arrive only through the controller, on the main dispatcher, as `BrowserRequest`.

## Integration

- `ui/AndChamberApp.kt` owns the route and passes `BrowserController?`, `RuntimeState`, `readyLaunchId` and the remote list. It does not create the controller.
- `browser/BrowserContract.kt` is the frozen controller and driver contract. This folder does not modify it.
- `browser/BrowserUrlPolicy.kt` is called, not modified. DNS preflight runs on `Dispatchers.IO`.
- Strings live in `values/strings.xml` and `values-zh/strings.xml` under the `browser_` keys.
