# app/src/main/java/io/andchamber/app/data/

## Responsibility

Network and persistence adapters for remote and local OpenChamber servers, used by the UI and by the notification bridge:

- `WebSessionAuthenticatorImpl.kt`: prepares a server for the WebView (health check, login, cookie hand-off). Implements `runtime.WebSessionAuthenticator`. Also defines `LocalReadiness`.
- `WebSessionOrigin.kt`, `WebSessionProfiles.kt`: per-target WebView isolation. `canonicalWebOrigin` normalizes the origin. `WebSessionProfiles` hands out a `WebProfileBinding` per (target, origin), and a `WebSessionCookies` capability is bound to that profile's `CookieManager`. There is no global `CookieManager` path.
- `HttpDownloads.kt`, `HttpDownloadPolicy.kt`, `HttpDownloadTransfer.kt`, `HttpDownloadDestinations.kt`: native http(s) downloads. Cookies come from the bound profile. The starter is `HttpDownloadStarter`, and `AndroidHttpDownloadStarter` is registered as a singleton in DI.
- `LocalApiLease.kt`: internal helper for `LocalApiClient`. It holds `LocalApiLease(launchId, canonical loopback origin)` and the same-origin URL checks.
- `LocalApiClient.kt`: loopback-only HTTP client used by notification actions and by `NotificationObserver`/`NotificationSettingsBootstrap`. Holds an in-memory cookie session. Also defines `ApiResult` and `MemoryCookieJar`.
- `EncryptedRemoteServerStore.kt`: persists the list of remote servers (`ServerTarget.Remote`, including the UI password) in EncryptedSharedPreferences. Implements `runtime.RemoteServerStore`.

## Design

### WebSessionAuthenticatorImpl (`DefaultWebSessionAuthenticator`)

- Constructor: `client`, `localPassword`, `localReady: LocalReadiness` (default denies everything), `remoteStoredPassword`. Cookies are not a constructor argument.
- `prepare(target, cookies: WebSessionCookies)`: the cookies must match the target id and canonical origin. Otherwise the call makes no request and reads no password. Authentication for one identity is serialized. Cookies are stored only while the coroutine is still active, and the store itself is `NonCancellable`.
- All authentication requests use `CookieJar.NO_COOKIES` and do not follow redirects. A 3xx on login maps to `NeedsLogin` and stores nothing.
- Local targets are checked against `LocalReadiness` three times: after health, before the POST, and after the POST and before cookies are stored.
- Two OkHttp clients: `probeClient` (follows redirects, used for `/health` and `GET /auth/session`), `loginClient` (`followRedirects(false)`, `followSslRedirects(false)`, used only for `POST /auth/session`).
- Local targets are trusted only while `LocalReadiness.runningLaunchId()` returns the same non-null id before and right before the POST. A restart in between yields `Unreachable("...restarted; try again")`. Null id yields `Unreachable("not running")`.
- Transport rule: a remote target without `allowInsecureHttp` is refused unless the URL is https or loopback (`HttpUrl.isTransportSafe`). Refusal happens before any login request.
- Results: `WebSessionResult.Ready(base)`, `NeedsLogin(base)`, `Unreachable(reason)`.
- Set-Cookie headers are written through the bound profile's `WebSessionCookies.storeCookies` and flushed there. OpenChamber names the cookie `oc_ui_session_<port>`, which keeps two servers on one host apart.
- Login body: `{"password": ..., "trustDevice": true}`. 401 and 3xx map to `NeedsLogin`; other non-2xx map to `Unreachable`.

### LocalApiClient

- `baseUrl: () -> String?` is the live server URL (from `RuntimeState.Running`), so a stopped server is never called. `password: () -> String?` reads the UI password from `RuntimeSecrets.existingUiPassword`.
- `localReady: LocalReadiness` defaults to deny. Every request is refused until DI injects a real readiness source (`OpenChamberServer.readyLaunchId()`).
- Lease: each send captures `LocalApiLease(launchId, canonical loopback origin)`. Before bytes leave, a network interceptor checks that the lease still matches and that the URL is on the same origin. Any change clears the cookie jar and fails the call with `ApiResult(0, ...)`. The lease is checked again after login, after the business call, and before a 401 re-login.
- Sends are serialized by a `Mutex`. Redirects are never followed. `resolveSameOrigin` rejects `//host`, absolute URLs and backslashes before any request.
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

WebView login (`WebSessionAuthenticator.prepare(target, cookies)`):
1. Parse base URL; reject if invalid.
2. `GET {base}/health` must return `{"status":"ok"}`, else `Unreachable`.
3. Local only: `launchId = localReady.runningLaunchId()`; null → `Unreachable`.
4. Pick password (local: `localPassword()`; remote: stored `uiPassword`).
5. No password → `GET /auth/session`: 200 → `Ready`, else `NeedsLogin`.
6. Local: re-check the launch id is unchanged, else `Unreachable`.
7. Remote transport check (see above).
8. `POST /auth/session` (no redirects) → store cookies → `Ready`, or `NeedsLogin` (401/3xx), or `Unreachable`.

Notification action (`NotificationActionReceiver` → `LocalApiClient.send`):
1. Check the lease (launch id and origin). Login if no `oc_ui_session*` cookie is held.
2. Execute the `ApiRequest` (for example `POST /api/session/:sid/prompt`).
3. 401 → re-login once and retry.
4. Return `ApiResult(status, body)`; the caller decides the notification update.

Remote server CRUD: UI calls `upsert`/`remove` → JSON re-encoded → EncryptedSharedPreferences → `servers` flow emits.

## Integration

- `di/AppModule.kt` constructs: `DefaultWebSessionAuthenticator` (with `LocalReadiness { get<OpenChamberServer>().readyLaunchId() }`), `LocalApiClient` (baseUrl from `LocalRuntimeController.state`, password from `RuntimeSecrets`, the same `localReady`), `EncryptedRemoteServerStore.create`, `WebSessionProfiles`, and the `AndroidHttpDownloadStarter` singleton.
- Interfaces implemented from `runtime/`: `WebSessionAuthenticator`, `RemoteServerStore`. Types used: `ServerTarget` (Local/Remote), `WebSessionResult`, `RuntimeSecrets`, `RuntimePaths`, `RuntimeState`.
- Consumers: `MainActivity` and `ui/` (through `WebSessionAuthenticator` and `RemoteServerStore`); `notify/NotificationActionReceiver.kt`, `notify/NotificationObserver.kt`, `notify/NotificationSettings.kt`, `notify/NotificationSink.kt` (through `LocalApiClient`, which imports `notify.ApiRequest` from `notify/PendingPermissions.kt`).
- OpenChamber HTTP endpoints:
  - `GET /health` (`WebSessionAuthenticatorImpl`, `NotificationObserver`).
  - `GET|POST /auth/session` (login and session probe).
  - `GET /api/sessions/status` (`LocalApiClient.pending`, used by notify for form and permission checks).
  - Any `ApiRequest` built by `notify/NotificationActions`, such as `/api/session/:id/prompt`, `/permission/:rid/reply`, `/form/:fid/reply`, and `/api/config/settings`.
- Networking: `app/src/main/res/xml/network_security_config.xml` governs cleartext; the code-level plain-HTTP guard is in `WebSessionAuthenticatorImpl`.
