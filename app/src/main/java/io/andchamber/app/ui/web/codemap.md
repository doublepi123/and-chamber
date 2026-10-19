# app/src/main/java/io/andchamber/app/ui/web/

## Responsibility

Full-screen OpenChamber web UI for one server (`Routes.WEB`, `web/{targetId}`). Owns the WebView pool, session preparation and page loading, navigation policy, the JS bridge, downloads, file and microphone requests, and the overlay panels. It hosts OpenChamber's own web UI; it does not implement it.

Files:
- `ChamberWebView.kt`: `ChamberWebView` (one WebView bound to one profile, clients, bridge, helper script), `WebViewPool`, `WebHost`, `originOf()`.
- `WebDependencies.kt`: `rememberWebDependencies()` (app singletons `WebSessionProfiles` and `HttpDownloadStarter` from Koin; preview stand-ins in inspection mode), `bindWeb(profiles, target, obtain)`.
- `IsolationPanel.kt`: the isolation-failure panel, `browserAddressFor()` and `openAddressInBrowser()`.
- `WebScreen.kt`: `WebScreen` (profile bind, session prep, overlays, BackHandler, insets), `OverlayContent`, `LocalServerPanel`, `WebLifecycleEffect`, `WebHostEffect`.
- `WebPanels.kt`: `StatusPanel`, `PanelAction`, `ConnectingPanel`.
- `EdgeHandle.kt`: draggable edge tab with a menu (Home, Reload, Open in browser).
- `NotifyVisibility.kt`: `LocalPageVisibilityEffect(isLocal, overlayShown)` reports to `notify.LocalPageVisibility` while the local page is RESUMED (this navigation entry) with no overlay; clears only its own report on dispose. OpenChamber events are then left to the page (see notify codemap for `requireHidden`).

## Design

- `WebViewPool(downloads)` keeps at most one `ChamberWebView` (`current`). `obtain(context, binding, darkTheme, backgroundColor)` reuses it only for the same identity (target id plus canonical origin). A different identity, including the same target at a new origin, destroys it and binds a new WebView to its own profile. `peek` and `discard` still match on target id. `discard` and `destroyAll` are called from the root. The pool lives in the root, so a page survives a trip to Home. `handleFraction` (edge tab position) is in-memory only.
- Profile first: `WebScreen` calls `bindWeb` (`profiles.acquire(target)`, then `pool.obtain`) before anything else. An `acquire` or bind failure is a `WebIsolationException` (or a `RuntimeException` from creating the WebView, mapped to `ProfileBindingFailed`). Either way there is no WebView, no sign-in and no load. Old profiles are not cleared or migrated.
- Isolation failure: `WebOverlay.Isolation` shows `IsolationPanel` (per failure kind). A failure never falls back to another profile, never reads a password, and never loads the page. Home, the background service and notifications are unaffected. "Open in browser" is offered only for a remote server whose saved address is a valid canonical origin without userinfo, and it passes only that address to `ACTION_VIEW`. Local targets never get the browser entry.
- `ChamberWebView.isolationFailure` is Compose state. If a renderer crash cannot rebind the profile, the failure shows up here and the page is not reloaded.
- The view is detached and reattached via `attachableView()`. `AndroidView(onRelease = {})` never destroys it. `key(web, web.generation)` recreates the AndroidView after a renderer crash.
- Session identity: `sessionKey` (`local|sinceEpochMs|baseUrl` or `remote|id|baseUrl|passwordHash`). A changed key re-runs `auth.prepare` and reloads. `invalidateSession()` forces this on retry.
- Origin lock: `origin` is the binding's canonical origin (`scheme://host:port`, default ports filled in, set from the profile binding and never re-derived from a URL). `mainTrusted` is true only while the main frame is on that origin. `routeOutside` sends other http(s) to the browser, drops top-level `blob:`, `data:`, `javascript:` and `null` loads, and handles `intent:` with a fallback. A foreign main frame is stopped and reloaded, up to `MAX_FOREIGN_RECOVERIES`.
- JS bridge `AndChamberBridge` is attached only while the main frame is trusted, and removed otherwise. Every call must carry `bridgeToken`, a UUID rotated on each page start and origin change. Methods: `saveFile(token, dataUrl, name, mime)` (size capped by `MAX_BRIDGE_CHARS`) and `themeColor(token, rgb)`.
- Helper script (`pageHelperScript`), injected after `onPageFinished` on trusted pages: routes blob-anchor downloads to `fetchAndSave`, delays `URL.revokeObjectURL` by 60 s, and reads the computed background through a canvas, reporting it as `pageColor`.
- Hardening: mixed content never allowed, file and content access off, third-party cookies off, multi-window and zoom off, WebView insets consumed (Compose pads the page instead). Debuggable builds enable WebView debugging.
- Crash policy: `replaceAfterCrash()` recreates the view and reloads `currentUrl`, up to `MAX_AUTO_RELOADS` (2) per `CRASH_WINDOW_MS` (60 s). Beyond that, `pageError` is shown.
- Insets: `systemBars ∪ displayCutout ∪ imeAnimationTarget` pad the page container. The IME target is used, not the animated value, so the page resizes once per keyboard transition.
- `WebOverlay` (sealed: `Missing`, `Local`, `Unreachable`, `Connecting`, `PageError`) is shown in `AnimatedContent` keyed on `kind()`, so live progress does not restart the fade. `blockTouches()` swallows input under an overlay.
- `EdgeHandle` takes no layout space, fades to 50% after 3 s at rest, and is in `systemGestureExclusion`. "Open in browser" is offered only for remote servers, since the local password is internal.
- Permissions: `RECORD_AUDIO` is requested only if declared in the manifest, and only for the server origin. The file chooser maps `accept` to MIME types and supports multi-select. A pending chooser callback is always answered.
- Downloads: http(s) downloads go to `HttpDownloadStarter` (see `data/codemap.md`), which applies the bound profile's cookie hop by hop. The app's own toast text is kept. `data:` and `blob:` are saved to `MediaStore.Downloads` (API 29+) or app external files (older). Names go through `safeFileName`.
- Renderer recovery keeps the same binding and profile. Only a rebind failure changes the state, to `isolationFailure`.
- Status bar: `pageColor` drives the bar color while no overlay is up, and the theme background otherwise. The color is reported to the root via `onSystemBarColor`.

## Flow

1. Root `Routes.web(targetId)` → `WebScreen` → `bindWeb`: `profiles.acquire(target)`, then `pool.obtain` returns the `ChamberWebView` bound to that profile. Initial `phase` is `Loaded` if `view.sessionKey == sessionKey`, else `Connecting`.
2. `LaunchedEffect(web, sessionKey, attempt)` runs only when the view is bound (`isolationFailure == null`, `view != null`, `sessionCookies != null`). It calls `auth.prepare(target, web.sessionCookies)`. `Ready(url)` or `NeedsLogin(url)` (OpenChamber shows its own sign-in at the same address) leads to `view.load(start)`. `Unreachable(reason)` gives `SessionPhase.Unreachable`. If the binding failed, no `prepare` call is made.
3. If a deep-link path is pending, the page loads `withSessionPath(start, link)` and the root's `onDeepLinkUsed()` clears it. `latestDeepLink` is a `rememberUpdatedState`, so the value read is the one current at load time.
4. Already-loaded same session: a second effect calls `navigateWithinSession(withSessionPath(base, link))`, which accepts only the same origin and returns false if no session is loaded.
5. `onPageFinished` (trusted origin) sets `committed` and `currentUrl`, clears history once per session, and injects the helper script. Overlay is null once committed and no error or phase overlay applies. The progress bar shows while `progress < 100`.
6. Local target: `target` is non-null only while `RuntimeState.Running`. The overlay is `WebOverlay.Local(state)` otherwise, rendered by `LocalServerPanel` (Starting, Stopping, Stopped with Start again, Failed with Try again or Home by `recoverable`, Installing, Detecting, not set up). When the server starts, a new `sinceEpochMs` changes `sessionKey`, which triggers `prepare` and `load`.
7. Back: when no overlay is up and `canGoBack()`, `goBack()`. For local with no overlay, `moveTaskToBack(true)`. Otherwise `onExit()`.
8. Renderer crash: `onRenderProcessGone` → `replaceAfterCrash()` → new view, `generation += 1`, reload of `currentUrl` with a toast until the limit, then `page_crashed_repeatedly`.
9. File chooser and microphone: `WebHostEffect` sets `web.host` to a `WebHost` backed by activity-result launchers. On dispose, `host` is cleared and pending callbacks are answered (null or false).

## Integration

- Driven by `ui/AndChamberApp.kt`, which passes `target`, `localState`, `checking`, `sessionKey`, `auth`, `pool`, `deepLinkPath` and the callbacks. The root also calls `pool.discard` on server removal and before repair or uninstall.
- Runtime contract: `WebSessionAuthenticator.prepare(target, cookies: WebSessionCookies)` → `WebSessionResult` (`Ready`, `NeedsLogin`, `Unreachable`). The cookies come from the bound profile; the target and origin must match. `ServerTarget.Local(baseUrl)` and `Remote(id, displayName, baseUrl, uiPassword, allowInsecureHttp)`. `RuntimeState` and `FailurePhase` drive `LocalServerPanel`.
- Notification: `withSessionPath` (`ui/LaunchDecision.kt`) builds `/?session=<id>`, the only deep-link touchpoint here.
- Uses `ui/` helpers `findActivity`, `rememberLastNonNull`, `displayAddress`, `components` (`ChamberMark`, `StatusDot`, `StatusTone`), `theme` tokens, `i18n.UserMessages.stepLabel`, and `R.string.*`.
- Android APIs: `WebView`, `WebViewCompat` / `WebViewFeature` (profiles), `MediaStore`, `ActivityResultContracts`, `LifecycleEventObserver` (resume and pause). `DownloadManager` is not used for page downloads.
