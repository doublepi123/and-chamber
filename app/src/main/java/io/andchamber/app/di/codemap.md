# app/src/main/java/io/andchamber/app/di/

## Responsibility

The only Koin composition root: `AppModule.kt` declares `val appModule = module { ... }`. It builds every long-lived object (OkHttp, stores, notification pipeline, runtime controller, OpenChamber server adapter, web session authenticator) from Android `Context`, filesDir, nativeLibraryDir and assets. No business logic lives here beyond wiring and two small helpers.

## Design

Koin graph (`single` declarations, all process-wide):

- `OkHttpClient`: connect 5 s, read 10 s. Shared by `VerifiedRuntimeDownloader` and `DefaultWebSessionAuthenticator`.
- `WebSessionProfiles()`: hands out one WebView profile binding per (target id, canonical origin). It is the only WebView cookie path; there is no global `CookieManager` fallback. Legacy default-profile data is neither cleared nor migrated. Removing a remote clears only that server's named profile after its write gate drains.
- `ScreenAwakeController`: one process-wide preference/navigation reader in `ui_prefs`, used by `MainActivity` for the optional foreground-only local-runtime screen hold and by Home for its switch.
- `HttpDownloadStarter` → `AndroidHttpDownloadStarter(context)`: a single application-scoped starter, so a download keeps running after its page is gone. It has its own default scope.
- `RemoteServerStore` → `EncryptedRemoteServerStore.create(context)`.
- `OpenChamberNotifier(context)`.
- `LocalApiClient`:
  - `baseUrl = { (LocalRuntimeController.state.value as? RuntimeState.Running)?.url }`. A stopped or restarting server is never called.
  - `password = { RuntimeSecrets.existingUiPassword(paths) }`.
  - `localReady = LocalReadiness { OpenChamberServer.readyLaunchId() }`. Without it, every request is denied by default.
- `NotificationSink(get(), ProcessForeground, get())`: notifier, foreground source, API.
- `NotificationObserver(runtime, notifier, api, bootstrap = NotificationSettingsBootstrap(api, store), watch = RuntimeErrorWatch(Titles(...)))`. Titles come from `R.string.alert_*`.
- `NotificationBootstrapStore`: SharedPreferences `andchamber_notify`, key `notifications_bootstrapped` (private const in this file).
- `SharedStorageControllerImpl` (paths, `SharedStorageAccess.defaultHostRoot()`, `PrefsSharedStoragePrefs`, `SharedStoragePermission`); also bound as `SharedStorageController`.
- `NativeBrowserPresenter` (Main dispatcher scope), `ProviderPageRouter(presenter)` and `LocalBrowserProviderBinding(presenter, router)`; the binding is also bound as `BrowserToolHost`. The binding owns the loopback tool server for one launch and follows presenter transport eligibility.
- `OpenChamberServer` → `ProotOpenChamberServer`:
  - `suite = EmbeddedCommandSuite(nativeDir, paths).resolve()`.
  - `scanner = ProcessScanner(myUid, myPid)`, `health = HealthProbe.Loopback`.
  - `signal = Process.killProcess`.
  - `refreshDns = ResolvConf.write(rootfs, AndroidSystemDnsProvider.dnsServers())`: one write before each launch.
  - `dnsMonitor = DnsRefresherMonitor(DnsRefresher(AndroidSystemDnsProvider, AndroidNetworkEvents, reporter → Log.w("AndChamberDns")))`: live refresh while the server runs. The server calls `start(rootfs)` once ready and `stop()` first in its own `stop()`, before process teardown.
  - `cleanBeforeStart` → `ProotStaleServerCleaner`.
  - `notificationSink = { NotificationSink.accept(line) }`.
  - `browserPluginAssets` reads the two `browser-tool/` assets; a blank asset degrades that launch only.
  - `browserTool = get()` starts and stops the loopback tool server with the launch.
  - `bindsForStart` / `onStarted` / `onStopped` → `SharedStorageControllerImpl.prepareForStart` / `recordStarted` / `recordStopped`.
- `LocalRuntimeController` → `DefaultLocalRuntimeController`:
  - `installer = RuntimeInstallerImpl(paths, abi, manifest from assets, suite, VerifiedRuntimeDownloader(okHttp), shim asset "android-shim.cjs", dnsProvider)`.
  - `foregroundStarter = { RuntimeService.request(context, op) }`.
  - `killOwned = killOwnedRuntime(scanner, prootPath, rootfs)`.
  - Calls `.also { it.initialize() }`.
- `WebSessionAuthenticator` → `DefaultWebSessionAuthenticator(client, localPassword, localReady = { OpenChamberServer.readyLaunchId() })`. Cookies are no longer a constructor argument: they arrive per call through `prepare(target, cookies)`, from the target's bound profile.

Private helpers: `primaryAbi()` (first of `Build.SUPPORTED_ABIS`), `KEY_BOOTSTRAPPED`.

Note the lambda-based wiring: the controller and the notification sink reach each other through `get()` inside lambdas, so construction order does not create a cycle at build time.

## Flow

1. `AndChamberApplication.onCreate` → `startKoin { androidContext; modules(appModule) }`.
2. `get<NotificationObserver>()` (in `Application.onCreate`) resolves its constructor arguments eagerly: `LocalRuntimeController` (so `OpenChamberServer`, the installer and `initialize()` run at this point, not at first UI use), `OpenChamberNotifier`, `LocalApiClient`, and `NotificationSettingsBootstrap`. `NotificationSink` is not built here; it is resolved lazily inside the server's `notificationSink` lambda.
3. `MainActivity` then resolves the same `LocalRuntimeController` singleton, plus `RemoteServerStore`, `WebSessionAuthenticator` and `SharedStorageController`.
4. Server stdout line → `ProotOpenChamberServer` → `NotificationSink.accept(line)` (lookup via `get<NotificationSink>()` at call time).
5. Local login → `DefaultWebSessionAuthenticator.prepare` → `localReady` → `OpenChamberServer.readyLaunchId()`.
6. Start/stop transitions → `storage.prepareForStart()` / `recordStarted` / `recordStopped` for shared storage binds.

## Integration

- Depends on every package under `io.andchamber.app`: `data/` (`LocalApiClient`, `DefaultWebSessionAuthenticator`, `EncryptedRemoteServerStore`, `LocalReadiness`, `WebSessionProfiles`, `AndroidHttpDownloadStarter`, `HttpDownloadStarter`), `notify/` (`NotificationObserver`, `NotificationSink`, `NotificationBootstrapStore`, `NotificationSettingsBootstrap`, `RuntimeErrorWatch`, `OpenChamberNotifier`, `ProcessForeground`), `service/RuntimeService.kt`, `runtime/` (`DefaultLocalRuntimeController`, `RuntimePaths`, `RuntimeSecrets`, `RuntimeState`, `ForegroundStarter`), `runtime/install/` (`RuntimeInstallerImpl`, `VerifiedRuntimeDownloader`), `runtime/process/` (`ProotOpenChamberServer`, `ProcessScanner`, `HealthProbe`, `EmbeddedCommandSuite`, `ProotStaleServerCleaner`, `killOwnedRuntime`), `runtime/network/` (`AndroidSystemDnsProvider`, `ResolvConf`, `AndroidNetworkEvents`, `DnsRefresher`, `DnsRefresherMonitor`, `RuntimeDnsReporter`), `runtime/manifest/RuntimeManifestReader.kt`, `runtime/storage/` (`SharedStorageControllerImpl`, `SharedStorageAccess`, `PrefsSharedStoragePrefs`, `SharedStoragePermission`).
- Resources: `R.string.alert_setup_failed`, `alert_start_failed`, `alert_stopped`, `alert_opencode_error` (RuntimeErrorWatch titles).
- Assets: `RuntimeManifestReader.ASSET_NAME`, `android-shim.cjs`.
- Android: `nativeLibraryDir` (the bundled `proot` binary), `filesDir`, `Os.getuid/getpid`, `Process.killProcess`.
- Consumers: `AndChamberApplication.kt` (`startKoin`, `get<NotificationObserver>`), `MainActivity.kt` (`get<LocalRuntimeController>`, `RemoteServerStore`, `WebSessionAuthenticator`, `SharedStorageController`), `ui/` via Koin `get()`.
- No HTTP endpoints are defined here; the endpoints are used by the classes it constructs (see `data/codemap.md`).
