# app/src/main/java/io/andchamber/app/notify/

## Responsibility

Turns OpenChamber's desktop-notification events and AndChamber's own runtime errors into Android notifications, and lets the user act on them (reply, permission decision, form answer) without opening the app. Also owns the one-time bootstrap that turns on OpenChamber's `nativeNotificationsEnabled`, and the pending "open this session" link from a notification tap.

## Design

Pipeline for OpenChamber events (stdout from the server):

- `NotificationSink.accept(line)`: entry point, called by `ProotOpenChamberServer` through `notificationSink`. Runs off the pump thread on `Dispatchers.IO`.
- `NotificationPayload.parseLine`: accepts only lines starting with `[OpenChamberDesktopNotify] `. The JSON must have a non-blank `tag`. Fields: `kind`, `title`, `body`, `tag`, `sessionId`, `directory`. Unknown kinds map to `Kind.Unknown`.
- `NotifyPolicy.deliveryFor(kind, inForeground)`: `Suppressed` when the app is foreground (web UI already shows a toast); otherwise `Post(channel)`.
  - `channelFor`: Ready/Goal → AgentFinished; Question/Permission → NeedsInput; Error/RestartInterrupted → Errors; Plugin/Unknown → Other.
- `AppForeground` (`ForegroundState.kt`): `ProcessForeground` reads `ProcessLifecycleOwner` and is true at least at STARTED.
- `ActionPlan.forEvent(event)` decides the actions from the kind alone:
  - Ready, Goal, Error with a session → `Reply` (RemoteInput).
  - Permission with a session and a `permission-<sid>:<rid>` tag → `Permission`. Otherwise `OpenOnly`.
  - Question → `Question` (needs the pending form first).
  - Plugin, RestartInterrupted, Unknown, or no session → `OpenOnly`.
- `OpenChamberNotifier`:
  - Channels `agent-finished` (DEFAULT), `needs-input` (HIGH), `errors` (HIGH), `other` (DEFAULT). `ensureChannels()` re-applies names on every call and keeps user importance.
  - Notification id = `NotificationPayload.notificationId(tag)`: FNV-1a over UTF-8, non-negative. The same tag replaces the earlier notification.
  - Content: small icon `ic_notification`, accent `#EDB449`, BigTextStyle, `CATEGORY_MESSAGE`, auto-cancel.
  - Tap intent: `MainActivity` with `ACTION_OPEN_SESSION`, extras `andchamber.open_session` and `andchamber.open_directory`.
  - Action intents: `NotificationActionReceiver` broadcasts with extras `session_id`, `directory`, `notification_id`, plus `request_id`/`decision` or `form_id`/`field_key`/`value`. RemoteInput key `reply_text`.
  - `postAlert(RuntimeAlert)`: Errors channel, id from `alert:<key>`, tap opens Home.
  - `updateStatus(id, channel, text)`: in-place "Sent" or error text, `setOnlyAlertOnce`.
  - `canPost()`: POST_NOTIFICATIONS on API 33+.
- Question path, `NotificationSink.postQuestion`:
  - Calls `LocalApiClient.pending()`. Requires exactly one form for the session (`PendingForms.singleFormFor`); with several, it opens the app.
  - `PendingForms.classifyForm` → `FormAnswer`:
    - `Choice` (single `string` field, 1 to 3 options with value and label) → one button per option.
    - `Text` (single string field, no options) → RemoteInput reply.
    - `OpenOnly` (multi-field, `when`/`hidden`, non-string, `global` session, too many options, missing ids) → tap to open.
- Action receiver, `NotificationActionReceiver` (`BroadcastReceiver` + `KoinComponent`, `exported=false`):
  - `onReceive` calls `goAsync()`, then runs `handle` on `Dispatchers.IO` and calls `finish()`.
  - `ACTION_REPLY`: reads RemoteInput text, `POST /api/session/:sid/prompt`. On success shows "Sent", waits 3 s, cancels. On failure shows the reason (at most 120 chars) on the Errors channel and keeps the text.
  - `ACTION_FORM`: checks the form is still pending and answerable (`PendingForms.classify`). If not, it cancels silently. Otherwise `POST /api/session/:sid/form/:fid/reply` with `{answer:{key:value}}`.
  - `ACTION_PERMISSION`: checks `PendingPermission.find`. If gone, cancels silently. Otherwise `POST /api/session/:sid/permission/:rid/reply` with `{decision: once|always|reject}`.
- Request builders, `NotificationActions` (in `PendingPermissions.kt`): pure `ApiRequest(method, path, body)`. Path segments are URL-encoded with `%20` for spaces.
- Pending-state parsing, `PendingPermissions.kt` and `PendingForms.kt`: read `GET /api/sessions/status` → `pending[sid].permissions[]` and `pending[sid].forms[]`. Parsing is pure and unit-tested.
- Runtime alerts, `NotificationObserver` and `RuntimeErrorWatch`:
  - `NotificationObserver.start()` (called once from `AndChamberApplication`) collects `runtime.state` with `collectLatest`.
  - `RuntimeErrorWatch.onState`: Failed(Install) → "Setup failed", Failed(Start) → "OpenChamber didn't start", Failed(Runtime) → "OpenChamber stopped". Deduped by key. Stop never alerts.
  - While Running, `watchHealth` polls `GET /health` every 15 s. `RuntimeErrorWatch.onHealth` alerts on `lastOpenCodeError` when `openCodeRunning` is false. Repeats are suppressed until the error clears.
  - Stopped or NotInstalled: cancel the health job and reset the dedupe.
  - Alerts always post, even in the foreground (`NotifyPolicy.runtimeErrorDelivery`).
- Settings bootstrap, `NotificationSettings.kt` (`NotificationSettingsBootstrap`):
  - Runs on Running, guarded by a `SharedPreferences` flag (`andchamber_notify`, key `notifications_bootstrapped`) via `NotificationBootstrapStore`.
  - Reads `GET /api/config/settings`. If `nativeNotificationsEnabled` is undefined or null → `PUT /api/config/settings` with `true`, then marks done. If the user has set any value (true or false) → marks done and leaves it alone.
  - Errors are swallowed (`runCatching`); the flag is not set on a failed PUT, so it retries on the next Running.
- Open-session link, `OpenSessionLink` (object): `offer(sessionId, directory)` sets a `MutableStateFlow<Target?>`, `take()` clears it once, `pathFor(id)` returns `/?session=<urlencoded>`. Consumed by the UI.

## Flow

OpenChamber event:
1. Server prints `[OpenChamberDesktopNotify] {json}` → `NotificationSink.accept`.
2. Parse. Null → drop.
3. `deliveryFor(kind, foreground)`. Suppressed → drop.
4. `ActionPlan.forEvent`:
   - Question → `postQuestion` on IO: `pending()` → `singleFormFor` → `classifyForm` → `notifier.post(event, channel, questionAnswer)`.
   - Otherwise → `notifier.post(event, channel)` with Reply / Permission / OpenOnly actions.
5. Tap → `MainActivity.onNewIntent`/`onCreate` → `OpenSessionLink.offer` → UI opens the session.
6. Action tap → `NotificationActionReceiver.onReceive` → `goAsync` → `handle` → `LocalApiClient.send` → `updateStatus` or `cancel`.

Runtime alert:
1. `RuntimeState` change → `NotificationObserver` → `RuntimeErrorWatch.onState` → `postAlert`.
2. Running → `bootstrapOnce()` (`ensureEnabled`) and `watchHealth()` (every 15 s).
3. Health error → `onHealth` → `postAlert` (deduped).

## Integration

- Depends on `data/LocalApiClient.kt` (HTTP, login, cookie jar), `runtime/` (`LocalRuntimeController`, `RuntimeState`, `FailurePhase`), `service/` (`RuntimeService` is not used directly here, but the foreground notification is its own channel), `MainActivity`, and `R` strings (`channel_*`, `action_*`, `reply_sent`, `reply_failed`, `alert_*`).
- Wired in `di/AppModule.kt`: `OpenChamberNotifier`, `NotificationSink(get(), ProcessForeground, get())`, `NotificationObserver(...)`, `NotificationBootstrapStore`, `NotificationSettingsBootstrap` (inside the observer).
- Started by `AndChamberApplication.onCreate`.
- Receives stdout from `runtime/process/ProotOpenChamberServer` through the `notificationSink` lambda.
- Manifest: `NotificationActionReceiver` is declared with `exported="false"`. `POST_NOTIFICATIONS` is requested.
- UI consumes `OpenSessionLink.pending` and `OpenSessionLink.pathFor` (`ui/`).
- OpenChamber HTTP endpoints used (loopback, via `LocalApiClient`):
  - `GET /health`: `NotificationObserver.watchHealth`.
  - `GET /auth/session` and `POST /auth/session`: login, inside `LocalApiClient.login`.
  - `GET /api/sessions/status`: `LocalApiClient.pending` (used by `NotificationSink.postQuestion`, `NotificationActionReceiver` form and permission checks).
  - `POST /api/session/:sid/prompt`: `NotificationActions.sendReply`.
  - `POST /api/session/:sid/permission/:rid/reply`: `NotificationActions.permissionReply`.
  - `POST /api/session/:sid/form/:fid/reply`: `NotificationActions.formReply`.
  - `GET` and `PUT /api/config/settings`: `NotificationSettingsBootstrap`.
