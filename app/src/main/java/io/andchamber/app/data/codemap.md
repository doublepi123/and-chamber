# app/src/main/java/io/andchamber/app/data/

## Responsibility

Network and persistence adapters for remote and local OpenChamber servers, used by the UI and by the notification bridge:

- `WebSessionAuthenticatorImpl.kt`: prepares a server for the WebView (health check, login, cookie hand-off). Implements `runtime.WebSessionAuthenticator`. Also defines `WebCookieSink` and `LocalReadiness`.
- `LocalApiClient.kt`: loopback-only HTTP client used by notification actions and by `NotificationObserver`/`NotificationSettingsBootstrap`. Holds an in-memory cookie session. Also defines `ApiResult` and `MemoryCookieJar`.
- `EncryptedRemoteServerStore.kt`: persists the list of remote servers (`ServerTarget.Remote`, including the UI password) in EncryptedSharedPreferences. Implements `runtime.RemoteServerStore`.

## Design

### WebSessionAuthenticatorImpl (`DefaultWebSessionAuthenticator`)

- Constructor: `client`, `cookies: WebCookieSink`, `localPassword`, `localReady: LocalReadiness`, `remoteStoredPassword`.
- Two OkHttp clients: `probeClient` (follows redirects, used for `/health` and `GET /auth/session`), `loginClient` (`followRedirects(false)`, `followSslRedirects(false)`, used only for `POST /auth/session`).
- Local targets are trusted only while `LocalReadiness.runningLaunchId()` returns the same non-null id before and right before the POST. A restart in between yields `Unreachable("...restarted; try again")`. Null id yields `Unreachable("not running")`.
- Transport rule: a remote target without `allowInsecureHttp` is refused unless the URL is https or loopback (`HttpUrl.isTransportSafe`). Refusal happens before any login request.
- Results: `WebSessionResult.Ready(base)`, `NeedsLogin(base)`, `Unreachable(reason)`.
- Set-Cookie headers are copied unchanged into `WebCookieSink` (`CookieManager` in DI) and flushed. OpenChamber names the cookie `oc_ui_session_<port>`, which keeps two servers on one host apart.
- Login body: `{"password": ..., "trustDevice": true}`. 401 and 3xx map to `NeedsLogin`; other non-2xx map to `Unreachable`.

### LocalApiClient

- `baseUrl: () -> String?` is the live server URL (from `RuntimeState.Running`), so a stopped server is never called. `password: () -> String?` reads the UI password from `RuntimeSecrets.existingUiPassword`.
- Non-loopback hosts are refused (`ApiResult(0, ...)`). Redirects are never followed.
- `send(request: ApiRequest, directory)`:
  1. Login if `MemoryCookieJar.hasSession` is false (`POST /auth/session`).
  2. Execute the request. Header `x-opencode-directory` is set to a JS-`encodeURIComponent` style value (`+` becomes `%20`).
  3. On 401: clear the jar, log in again, retry once.
- `getJson(path)` and `pending()` (`GET /api/sessions/status`, returns its `pending` object) return null on failure.
- Timeout default 8 s (connect, read, call).
- `MemoryCookieJar`: in memory only, replaces cookies by name, nothing persisted.

### EncryptedRemoteServerStore

- File `andchamber_remote_servers`, key `servers`. Master key AES256_GCM; keys AES256_SIV; values AES256_GCM.
- Persisted format: JSON list of `StoredServer(id, displayName, baseUrl, uiPassword?, allowInsecureHttp=false)`, `ignoreUnknownKeys = true`.
- `servers: StateFlow<List<ServerTarget.Remote>>` is the source of truth in memory. `upsert` and `remove` are `@Synchronized`, write-through, then update the flow. A decode failure yields an empty list.
- Created in DI via `EncryptedRemoteServerStore.create(context)`.

## Flow

WebView login (`WebSessionAuthenticator.prepare(target)`):
1. Parse base URL; reject if invalid.
2. `GET {base}/health` must return `{"status":"ok"}`, else `Unreachable`.
3. Local only: `launchId = localReady.runningLaunchId()`; null → `Unreachable`.
4. Pick password (local: `localPassword()`; remote: stored `uiPassword`).
5. No password → `GET /auth/session`: 200 → `Ready`, else `NeedsLogin`.
6. Local: re-check the launch id is unchanged, else `Unreachable`.
7. Remote transport check (see above).
8. `POST /auth/session` (no redirects) → store cookies → `Ready`, or `NeedsLogin` (401/3xx), or `Unreachable`.

Notification action (`NotificationActionReceiver` → `LocalApiClient.send`):
1. Login if no `oc_ui_session*` cookie is held.
2. Execute the `ApiRequest` (for example `POST /api/session/:sid/prompt`).
3. 401 → re-login once and retry.
4. Return `ApiResult(status, body)`; the caller decides the notification update.

Remote server CRUD: UI calls `upsert`/`remove` → JSON re-encoded → EncryptedSharedPreferences → `servers` flow emits.

## Integration

- `di/AppModule.kt` constructs: `DefaultWebSessionAuthenticator` (with `LocalReadiness { get<OpenChamberServer>().readyLaunchId() }`), `LocalApiClient` (baseUrl from `LocalRuntimeController.state`, password from `RuntimeSecrets`), `EncryptedRemoteServerStore.create`, and the `WebCookieSink` object over `CookieManager`.
- Interfaces implemented from `runtime/`: `WebSessionAuthenticator`, `RemoteServerStore`. Types used: `ServerTarget` (Local/Remote), `WebSessionResult`, `RuntimeSecrets`, `RuntimePaths`, `RuntimeState`.
- Consumers: `MainActivity` and `ui/` (through `WebSessionAuthenticator` and `RemoteServerStore`); `notify/NotificationActionReceiver.kt`, `notify/NotificationObserver.kt`, `notify/NotificationSettings.kt`, `notify/NotificationSink.kt` (through `LocalApiClient`, which imports `notify.ApiRequest` from `notify/PendingPermissions.kt`).
- OpenChamber HTTP endpoints:
  - `GET /health` (`WebSessionAuthenticatorImpl`, `NotificationObserver`).
  - `GET|POST /auth/session` (login and session probe).
  - `GET /api/sessions/status` (`LocalApiClient.pending`, used by notify for form and permission checks).
  - Any `ApiRequest` built by `notify/NotificationActions`, such as `/api/session/:id/prompt`, `/permission/:rid/reply`, `/form/:fid/reply`, and `/api/config/settings`.
- Networking: `app/src/main/res/xml/network_security_config.xml` governs cleartext; the code-level plain-HTTP guard is in `WebSessionAuthenticatorImpl`.
