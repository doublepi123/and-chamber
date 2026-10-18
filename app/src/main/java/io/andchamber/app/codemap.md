# app/src/main/java/io/andchamber/app/

## Responsibility

Package root of the AndChamber Android app. Two top-level classes wire the process together:

- `AndChamberApplication.kt`: process entry. Starts Koin with `appModule`, then starts `NotificationObserver` once (runtime error alerts and the one-time notification bootstrap).
- `MainActivity.kt`: the single launcher activity. Hosts Compose `AndChamberApp(runtime, remotes, auth, storage)` with dependencies resolved from Koin. Also turns a notification tap (`ACTION_OPEN_SESSION`) into `OpenSessionLink.offer(...)`.

Everything else lives in subpackages:

- `data/`: local API client (`LocalApiClient`), WebView login (`WebSessionAuthenticatorImpl.kt`), encrypted remote server list (`EncryptedRemoteServerStore`).
- `di/`: Koin module `appModule` (the only composition root).
- `i18n/`: `UserMessages`, maps runtime keys to string resources.
- `notify/`: OpenChamber stdout notification bridge, native notifications, notification actions, runtime alerts.
- `service/`: `RuntimeService` (foreground service), its pure `RuntimeServicePolicy`, and `WakeHold`.
- `runtime/`: runtime controller, install, embedded PRoot process, shared storage, network DNS, manifest, fs helpers (see `runtime/codemap.md`).
- `ui/`: Compose screens, theme, components, web view, previews (see `ui/codemap.md`).

## Design

- Composition root is `di/AppModule.kt`. Koin `single`s only. Runtime lookups via `get()`/`inject()` happen in `AndChamberApplication`, `MainActivity`, `service/RuntimeService` (`inject`), and `notify/NotificationActionReceiver` (`KoinComponent`).
- Process-wide singletons: `OkHttpClient` (connect 5 s, read 10 s), `RemoteServerStore`, `OpenChamberNotifier`, `LocalApiClient`, `NotificationSink`, `NotificationObserver`, `SharedStorageControllerImpl`, `OpenChamberServer`, `LocalRuntimeController`, `WebSessionAuthenticator`.
- `OpenSessionLink` is an `object` (process-wide `MutableStateFlow`) so a tap that launches a fresh activity still reaches the UI. It is consumed once via `take()`.
- `MainActivity` reads a notification intent only when `savedInstanceState == null` (cold start) or in `onNewIntent` (singleTop). Intents with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (Recents replay) are ignored.
- Manifest (`app/src/main/AndroidManifest.xml`):
  - Permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECORD_AUDIO (WebView voice input), WAKE_LOCK, MANAGE_EXTERNAL_STORAGE; READ_EXTERNAL_STORAGE (maxSdk 32), WRITE_EXTERNAL_STORAGE (maxSdk 29).
  - Application: `allowBackup=false`, `largeHeap=true`, `networkSecurityConfig=@xml/network_security_config`, `localeConfig=@xml/locales_config`, `requestLegacyExternalStorage=true`.
  - `MainActivity`: exported, `launchMode=singleTop`, `windowSoftInputMode=adjustResize`, LAUNCHER filter.
  - `notify.NotificationActionReceiver`: `exported=false` (only own notifications reach it).
  - `service.RuntimeService`: `exported=false`, `foregroundServiceType=specialUse` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.

## Flow

1. Cold start: `AndChamberApplication.onCreate` → `startKoin(appModule)` → `get<NotificationObserver>().start()`.
2. Activity start: `MainActivity.onCreate` → `enableEdgeToEdge` → `offerSessionFrom(intent)` if fresh → `setContent { AndChamberApp(...) }` with `LocalRuntimeController`, `RemoteServerStore`, `WebSessionAuthenticator`, `SharedStorageController` from Koin.
3. Notification tap while app open: `onNewIntent` → `offerSessionFrom` → `OpenSessionLink.offer(sessionId, directory)` → UI takes it and opens `<base>/?session=<id>`.
4. Notification tap, cold: `onCreate` (not `savedInstanceState`) → same offer.
5. Process-wide alerts: see `notify/codemap.md`.

## Integration

- Depends on `runtime/` (controllers, `RuntimePaths`, `RuntimeSecrets`, `ProcessScanner`, `HealthProbe`, storage, installer) and `ui/` (`AndChamberApp`).
- `di/AppModule.kt` wires `data/`, `notify/`, `service/`, `runtime/*`, and `i18n` consumers.
- `service/RuntimeService.kt` is started only through `RuntimeService.request` (from `runtime/` via `ForegroundStarter`).
- `notify/NotificationSink.kt` receives server stdout lines through `ProotOpenChamberServer`'s `notificationSink` callback.
- Local OpenChamber HTTP endpoints used from this folder tree (loopback only): `/auth/session`, `/health`, `/api/sessions/status`, `/api/config/settings`, `/api/session/:id/prompt`, `/api/session/:id/permission/:rid/reply`, `/api/session/:id/form/:fid/reply`.
- Manifest is consumed by the Android system; `res/` strings are referenced as `R.string.*` across `notify/`, `service/`, `i18n/`, and `di/`.
