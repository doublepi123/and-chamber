# app/src/main/java/io/andchamber/app/notify/

## Responsibility

Turns OpenChamber's desktop-notification events and AndChamber's own runtime errors into Android notifications, and lets the user act on them (reply, permission decision, form answer) without opening the app. Also owns the one-time bootstrap that turns on OpenChamber's `nativeNotificationsEnabled`, and the pending "open this session" link from a notification tap.

## Design

Pipeline for OpenChamber events (stdout from the server):

- `NotificationSink.accept(line)`: entry point, called by `ProotOpenChamberServer` through `notificationSink`. Runs off the pump thread on `Dispatchers.IO`.
- `NotificationPayload.parseLine`: accepts only lines starting with `[OpenChamberDesktopNotify] `. The JSON must have a non-blank `tag`. Fields: `kind`, `title`, `body`, `tag`, `sessionId`, `directory`, `requireHidden` (default true; only a JSON `false`, OpenChamber's "always" mode, turns it off). Unknown kinds map to `Kind.Unknown`.
- Sink: `NotificationSink` leaves an event to the page only when `NotifyPolicy.leaveToPage(event.requireHidden, ProcessForeground.isInForeground())` is true. So an event is suppressed only when OpenChamber marks it `requireHidden` AND the local page is on screen. In "always" mode (`requireHidden` false) it is posted even with the page visible.
- `NotifyPolicy.deliveryFor(kind, localPageShowing)`: `Suppressed` only while the local OpenChamber page is on screen; otherwise `Post(channel)`. Home, a remote server's page, any overlay, an unpainted page and the background all post.
  - `localPageShowing(isLocal, resumed, overlayShown)` = local && RESUMED && no overlay (pure).
  - `channelFor`: Ready/Goal → AgentFinished; Question/Permission → NeedsInput; Error/RestartInterrupted → Errors; Plugin/Unknown → Other.
- `AppForeground` (`ForegroundState.kt`): `ProcessForeground` (symbol kept for DI) = `LocalPageVisibility.isShowing()` && process ≥ STARTED. It no longer means "app in foreground".
  - `PageVisibility`: synchronized single-owner report; `report(owner, true)` takes over, `report(owner, false)` clears only its own. Starts false, memory only, so a missed report or a restart posts (fail-open).
  - Reported by `ui/web/NotifyVisibility.kt` `LocalPageVisibilityEffect`, called once from `WebScreen` after the overlay is decided.
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
  - Reply and permission PendingIntents come from `NotificationActionIdentity`: an explicitly encoded data URI includes action, notification id, session, optional directory and permission request/decision. The request-code hash is auxiliary only, so equal hashes cannot merge actions; `FLAG_UPDATE_CURRENT` still updates only the exact same action, and RemoteInput stays mutable.
  - A multi-field form ([FormAnswer.Fields]) gets one "Fill form" action (`R.string.action_fill_form`) instead of broadcast buttons. Its PendingIntent is built inside `revisions.publish` from the same identity URI, with the form id in `request` and that write's revision token in `decision`, so every post gets its own activity PendingIntent. `NotificationActionIdentity.activityPendingIntent` sends it to `NotificationFormActivity` (`ACTION_ANSWER_FORM`, immutable) with the session, directory, notification id, form id and the revision as `andchamber.revision`. The notification also keeps extras `andchamber.form_style=fields` and `andchamber.form_id`, so a later keep can rebuild the action for its own revision. Choice, Text and OpenOnly keep their previous actions.
  - `postAlert(RuntimeAlert)`: Errors channel, id from `alert:<key>`, tap opens Home.
  - `updateStatus(id, channel, text): String?`: in-place "Sent" (success path only), `setOnlyAlertOnce`. It writes a new revision as pending-Sent and returns the token, or null when posting is not allowed.
  - Every write goes through `NotificationRevisionGate.publish(id, sent)` under one lock. A write records its token as `latestWritten[id]` before writing and stores it in extras `andchamber.revision`. `cancelIfCurrent(id, token)` and `forgetSent(id, token)` are the Sent clean-up entry points; `cancel(id)` is the explicit removal and drops that id's pending entry and latest token.
  - Revision-scoped actions for the form screen: `NotificationRevisionGate.ifCurrent(id, token, write)` runs `write` only when the id's `latestWritten` token is not newer than `token` (no local record falls back to trusting the system, so actions survive a process restart) and the system's current revision is exactly `token`. Both checks and the write happen under the gate lock (no TOCTOU; a failed read runs nothing), so a newer publish blocks the old action even while the system still shows the old notification. It ignores the pending-Sent table, so a plain `post(sent = false)` matches too. `cancelIfRevision(id, token)` wraps an explicit cancel and returns whether it ran; `keepIfRevision(id, token, plan)` wraps a keep and returns the new revision, or null when stale or posting is not allowed.
  - `keep(id, KeepPlan): String?`: after an action didn't go through; returns the new revision of the kept notification (null if not posted). Rebuilds from `activeNotifications` (`NotificationCompat.Builder(context, original)`), so title, channel, session tap and, if `keepActions`, the original buttons and RemoteInput stay. A Fields form is special: the copied action carries the old revision, so it is cleared and, when the form id is known and `keepActions` is true, re-created for this keep's token from `plan.sessionId`/`plan.directory`/`id`/`andchamber.form_id`. Without a form id no Fill form action is kept. Status replaces the previous one (base text kept in extras `andchamber.original_body` since `post()`). A draft goes to `setRemoteInputHistory` and extras `andchamber.draft`. Swiped away: a minimal Errors notification that still opens the session; lost buttons are never re-created. The read of the old notification and the write of the kept one happen under the same revision lock, and the kept notification gets a new revision (so an older Sent clean-up cannot remove it). Never sends anything.
  - `canPost()`: POST_NOTIFICATIONS on API 33+.
- Question path, `NotificationSink.postQuestion`:
  - Calls `LocalApiClient.pending()`. Requires exactly one form for the session (`PendingForms.singleFormFor`); with several, it opens the app.
  - `PendingForms.classifyForm` → `FormAnswer`:
    - `Choice` (single `string` field, 1 to 3 options with value and label) → one button per option.
    - `Text` (single string field, no options) → RemoteInput reply.
    - `Fields` (2 to `MAX_FORM_FIELDS` = 8 fields, all `string`, no `options`/`when`/`hidden`, not a `global` session) → one "Fill form" action that opens `NotificationFormActivity`. `FormTextField` keeps each field's key, title (fallback key), description, required (default false), placeholder and default.
    - `OpenOnly` (other multi-field forms, `when`/`hidden`, non-string, `global` session, too many fields or options, missing ids) → tap to open.
- Form screen, `NotificationFormController.kt` (used by `NotificationFormActivity`):
  - `NotificationFormTransport` is the only dependency (`pending()` + `send(request, directory)`); `LocalApiNotificationFormTransport` adapts `LocalApiClient`.
  - `load()`: failed read → `CheckFailed`; form no longer pending → `Gone`; `Fields` → `Ready`; other classification → `Unavailable`.
  - `submit(form, values)`: keeps only the form's fields, trims values and drops blank ones. Missing required values → `KeepSendFailed` with the first field's title (or key), without calling the API. Otherwise it reuses `ActionDecisions`: pending check → one POST of the whole answer map (`NotificationActions.formReply(sessionId, formId, answers)`) → 2xx means `Cancel`; a refusal or unknown result gets a second pending check via `afterButton`, never a resend. A failed query is never `Gone`. `NotificationFormSubmission.reason` is only that local field label: a failed response's `body` is never returned, since it can echo the answer or other sensitive text.
- Form screen UI, `NotificationFormActivity.kt` + `NotificationFormScreen.kt`:
  - Manifest: `exported=false`, `singleTask`, own empty `taskAffinity`, `excludeFromRecents`, `adjustResize`. `Target.from(intent)` reads `ACTION_ANSWER_FORM` + the receiver's `session_id`/`directory`/`notification_id`/`form_id` extras and the `andchamber.revision` extra; anything missing (including the revision) finishes at once. A new intent for another form or revision recreates the screen. The screen gets `stateKey = "<sessionId>:<formId>"`: revision is only a notification-side-effect identity, so a keep (or a reopen/process restore) does not clear what was typed; different form ids stay strictly apart.
  - `NotificationFormModel` (Compose state, main thread): `start()` loads once (kept in a `ViewModel` across rotation); `send(values)` returns `Started`/`Ignored`/`Missing(keys)` and never starts a second submit while one is in flight. The submit runs in a process-wide main scope so it finishes, and updates the notification, even if the screen is closed.
  - `NotificationFormEffects` maps the outcome to the notifier, revision-scoped: `Cancel` and `Gone` → `cancelIfRevision(id, currentRevision)` and close; load `CheckFailed`/`Unavailable` and every Keep step → `ActionDecisions.keepPlan(step, FailureContext(sid, dir, draft = null), status)` passed to `keepIfRevision(id, currentRevision, plan)`, updating `currentRevision` to the returned token. So an answer from an older form cannot cancel or overwrite a newer notification on the same id, while a retry or a later success after a keep still matches the kept revision. A `KeepSendFailed` with a reason is only the local required-field title (`reply_failed`); without one it uses the fixed `form_send_refused`, never the server body. The `stateKey` is `session:form`, so a keep never clears what was typed. The typed answers never reach the notification or the log.
  - `NotificationFormScreen`: top bar with Close, form title and intro, one block per field (title, `Required` badge, description, `OutlinedTextField` with placeholder, `defaultValue` prefilled), a bottom bar pinned above the keyboard with the last status and one Send button (disabled + "Sending…" while in flight). Answers live in `rememberSaveable`, so a failure or rotation never clears them. A blank required field is flagged, focused and scrolled into view. IME Next moves between fields; the last field's IME action is Send. Width is capped (560dp) for tablets/landscape; the content scrolls on short screens.
  - Test tags in `NotificationFormTags` (exposed as resource ids). Device test: `androidTest/.../notify/NotificationFormScreenInstrumentationTest.kt` (fake load/submit, no server).
- Action receiver, `NotificationActionReceiver` (`BroadcastReceiver` + `KoinComponent`, `exported=false`):
  - `onReceive` calls `goAsync()`, then runs `handle` on `Dispatchers.IO` and calls `finish()`.
  - Serialized per notification id (bounded in-process `Mutex` table, at most 64 entries); correctness still comes from re-reading pending state.
  - `ACTION_REPLY`: reads RemoteInput text, `POST /api/session/:sid/prompt` (body unchanged, no client id). 2xx: `updateStatus` shows "Sent" and returns its token; after 3 s the receiver calls `cancelIfCurrent(id, token)`. 3xx/4xx: `KeepSendFailed` (reason ≤ 120 chars). 0/5xx: `KeepResultUnknown` ("open the session and check before sending again"). The text is kept as a draft; it is never re-sent automatically.
  - "Sent" clean-up is authoritative in-process state, not a timer on the id. `NotificationRevisionGate` keeps one pending-Sent token per notification id (`NotificationRevisionState`, shared by the process), plus `latestWritten` (the last token this process wrote, recorded before the write). Every write to an id (`post`, `postAlert`, `updateStatus`, `keep`) and every explicit `cancel` drops that id's pending entry under the same lock before it writes; `cancel` also drops `latestWritten`. `cancelIfCurrent` consumes the entry only when it matches, and removes only when the system's extras (`andchamber.revision`) show the same token. A stale token changes nothing. `ifCurrent` (used by the form screen's `cancelIfRevision`/`keepIfRevision`) requires that no newer local token exists and that the system shows the token, under the same lock; a missing local record falls back to the system revision (after a process restart). The receiver calls `forgetSent` in a `finally` when the wait is interrupted, so no pending entry is left behind. A failed read removes nothing. This is local state: it does not track the system's own notification state beyond that extras check.
  - `ACTION_FORM` / `ACTION_PERMISSION`: `ActionDecisions.beforeSend(check(pending()))`: Pending → POST; Gone → cancel; NotAnswerable → keep, open-session only (`KeepOpenOnly`, no buttons); Unknown (`pending()==null`, query failed) → keep the buttons with "couldn't check, nothing was sent" (`KeepCheckFailed`). A query failure never cancels. After a failed POST, read again: Gone → cancel; otherwise keep with buttons (failed or unknown). Only `NotAnswerable` loses its buttons. A typed form answer is kept as a draft. `ACTION_FORM` stays for single-field `Choice`/`Text`; a `Fields` form is answered by the form screen, not by this receiver.
- Decisions, `ActionDecisions.kt` (pure): `PendingCheck`, `ActionStep`, `SendOutcome` (`outcomeOf(status)`), `FailureContext`, `KeepPlan`. `checkForm` treats `Fields` as pending. No step after a failure is ever Send.
- Request builders, `NotificationActions` (in `PendingPermissions.kt`): pure `ApiRequest(method, path, body)`. `formReply` has a single-field overload and a whole-map overload (one `{ answer: { key: value } }` body). Path segments are URL-encoded with `%20` for spaces.
- Pending-state parsing, `PendingPermissions.kt` and `PendingForms.kt`: read `GET /api/sessions/status` → `pending[sid].permissions[]` and `pending[sid].forms[]`. Parsing is pure and unit-tested; `missingRequiredFields` is the shared required-value check for the form screen.
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
3. `deliveryFor(kind, ProcessForeground.isInForeground())`. Suppressed (local page on screen) → drop.
4. `ActionPlan.forEvent`:
   - Question → `postQuestion` on IO: `pending()` → `singleFormFor` → `classifyForm` → `notifier.post(event, channel, questionAnswer)`.
   - Otherwise → `notifier.post(event, channel)` with Reply / Permission / OpenOnly actions.
5. Tap → `MainActivity.onNewIntent`/`onCreate` → `OpenSessionLink.offer` → UI opens the session.
6. Action tap → `NotificationActionReceiver.onReceive` → `goAsync` → per-id lock → (`pending()` check) → `LocalApiClient.send` → `updateStatus` ("Sent", then 3 s and `cancelIfCurrent`), `cancel`, or `keep(KeepPlan)`. Only a `NotificationRevisionGate` pending-Sent match removes an id after a delay; any newer write to that id makes the old clean-up a no-op.
7. Fill form tap → `NotificationFormActivity` (immutable PendingIntent carrying the revision) → `NotificationFormController.load()` → the whole form → `submit()` once with every field → `cancelIfRevision`, or `keepIfRevision` and the screen stays open with a fixed status (the returned revision is kept for a later retry). A keep re-creates the Fill form action with its new revision, so reopening still works; an answer whose revision was replaced by a newer notification on the same id changes nothing. No automatic resend.

Runtime alert:
1. `RuntimeState` change → `NotificationObserver` → `RuntimeErrorWatch.onState` → `postAlert`.
2. Running → `bootstrapOnce()` (`ensureEnabled`) and `watchHealth()` (every 15 s).
3. Health error → `onHealth` → `postAlert` (deduped).

## Integration

- Depends on `data/LocalApiClient.kt` (HTTP, login, cookie jar), `runtime/` (`LocalRuntimeController`, `RuntimeState`, `FailurePhase`), `service/` (`RuntimeService` is not used directly here, but the foreground notification is its own channel), `MainActivity`, `NotificationFormActivity` (multi-field form screen; it uses `NotificationFormController`), and `R` strings (`channel_*`, `action_*` including `action_fill_form`, `reply_sent`, `reply_failed`, `alert_*`).
- Wired in `di/AppModule.kt`: `OpenChamberNotifier`, `NotificationSink(get(), ProcessForeground, get())`, `NotificationObserver(...)`, `NotificationBootstrapStore`, `NotificationSettingsBootstrap` (inside the observer).
- Started by `AndChamberApplication.onCreate`.
- Receives stdout from `runtime/process/ProotOpenChamberServer` through the `notificationSink` lambda.
- Manifest: `NotificationActionReceiver` and `NotificationFormActivity` are declared with `exported="false"`. `POST_NOTIFICATIONS` is requested.
- UI consumes `OpenSessionLink.pending` and `OpenSessionLink.pathFor` (`ui/`), and feeds `LocalPageVisibility` from `ui/web/NotifyVisibility.kt`.
- OpenChamber HTTP endpoints used (loopback, via `LocalApiClient`):
  - `GET /health`: `NotificationObserver.watchHealth`.
  - `GET /auth/session` and `POST /auth/session`: login, inside `LocalApiClient.login`.
  - `GET /api/sessions/status`: `LocalApiClient.pending` (used by `NotificationSink.postQuestion`, `NotificationActionReceiver` form and permission checks).
  - `POST /api/session/:sid/prompt`: `NotificationActions.sendReply`.
  - `POST /api/session/:sid/permission/:rid/reply`: `NotificationActions.permissionReply`.
  - `POST /api/session/:sid/form/:fid/reply`: `NotificationActions.formReply`.
  - `GET` and `PUT /api/config/settings`: `NotificationSettingsBootstrap`.
