# Repository Atlas: AndChamber

## Project Responsibility

AndChamber is an Android app (`io.andchamber.app`, Kotlin + Jetpack Compose, minSdk 26, target 35) that runs the OpenChamber web server (OpenChamber 2.4.0 + OpenCode 2.0.32) natively on the phone and shows it in a profile-isolated full-screen WebView (AndroidX WebKit 1.14.0). It builds an Alpine Linux 3.24 userland (Node.js 24) in app-private storage, runs it under PRoot (no root), keeps the server alive with a foreground service, signs the bound WebView profile in automatically, bridges OpenChamber's notifications to Android notifications with quick replies, and can also connect to remote OpenChamber servers.

Key constraints:
- On-device mode runs on arm64-v8a and x86_64 (PRoot 5.1.107.98 rewrites the guest's raw `fork` to `clone`, which Android's x86_64 app seccomp policy allows). Other ABIs can only use remote servers. The x86_64 gate is the emulator probe in `docs/testing-x86-runtime.md`; it is not yet verified on a physical x86_64 device.
- OpenCode stays on v2 (OpenChamber requires ≥ 2.0.20); no downgrades.
- Optional shared storage `/sdcard/AndChamber`: `projects/` → `~/projects`, `opencode/` → `~/.config/opencode`. Secrets (`service.json`, `auth.json`, session DB, OpenChamber data) and `node_modules` stay private. The private workspace is `~/projects-private`.
- Guest paths: home `~` = `/root` (bind of `<runtime>/home`), server working directory `/root`.
- All embedded pages require `WebViewFeature.MULTI_PROFILE`: unsupported or failed binding blocks page loading before authentication. Home, notifications and the local runtime remain available; remote addresses can be opened explicitly in a browser without adding credentials.
- Profiles are keyed by target ID and canonical origin. Old default-profile data is neither cleared nor migrated; isolation between target profiles does not change browser Cookie rules for subresources inside one profile.
- Native HTTP downloads use controlled redirects and the app process; they do not resume after process death. Unknown notification-send outcomes preserve the draft and session entry instead of automatically replaying the operation. Reply and permission PendingIntents have explicit per-notification/session/directory identities, so one notification cannot overwrite another's action.
- Removing a remote server clears only its current named WebView profile after started cookie writes drain; failed cleanup keeps the saved server. Optional screen awake is off by default and holds the foreground Activity window only while the local runtime is Running and no remote page is shown.
- Launch can restore the last used OpenChamber instance, and every instance remembers its own last page, so switching between instances returns to where each one was left. A launch with a remembered remote opens that remote; otherwise (a remembered local instance, nothing remembered, or Home/the native phone page left last) it starts the installed local runtime if needed and opens local chat. An app-icon tap on Home or the native phone page counts as a new launch, while a chat already in front is resumed as-is. Once phone control is enabled, paired and has a standing scope, opening local chat also reconnects the device in the background (one bounded discovery and TLS connect, never by opening the native phone page). Cloudflare Access handoff stops the old page before reading cookies and logging in, so a late 401 cannot delete the new login cookie.
- PRoot JNI libraries are compiled from pinned sources with NDK 29.0.14206865. That source build is not a claim that the whole 0.x pre-release is reproducible, and this is not an F-Droid build.
- Phone control is a separate, default-off Android 11+ experiment for a trusted local runtime. The person saves one bounded standing scope (allowed apps/actions/window/per-task duration; both duration selectors can be explicitly permanent); the first valid chat request binds a runtime+session (another chat may take over the session binding once the task has ended), every task gets a fresh epoch and short lease (or a permanent lease flag with no countdown), and only preauthorized apps are openable (no arbitrary intents, shell or URI surface). The `andchamber_phone` plugin never authorizes itself. Pairing/connect setup uses a bounded native discovery service over Android's two official ADB service types, a local-address-only endpoint and a loopback bind probe (EADDRINUSE only); the notification then accepts only the six-digit code and a fresh connect search performs one automatic TLS connect. Discovery never scans ports, reads shell properties or adds nearby/location runtime permission; Android 13 + T extension SDK 7 uses continuous service-info updates, older devices use bounded legacy resolve. Production readiness uses a bounded native RPC registration probe (`POST /api/rpc/andchamber-phone/ready`) after the plugin transform and tool list; a runtime restart happens only after native confirmation. Reading ordinary local chat does not revoke, while entering the real native phone page does. After a runtime change with no task, a native `Paused` reason can be cleared only by an explicit person-confirmed no-task resume when connection, tool and stop entry are ready; it neither grants nor launches and unknown outcomes still require checking. The handoff contract exposes read-only `taskRetained`/`taskState`/`continuationState`/`continuationFailure` from the same bound task facts: `NeedUser` and person `TakenOver` are distinct, `NotResumed` means no wake and no recovery grant (or a confirmed exact rollback) and may be retried by a fresh person confirmation, a post-wake `Failed` is never re-sent, and a failed continuation keeps the same task key and latest attempted epoch receipt instead of abandoning it. It adds no tool input action/schema, permission or overlay grant. Startup/continuation observation shares one 15 s maximum (including overlay wait and real remaining) and retries only explicit window transitions; busy or failed reads surface fixed `HIERARCHY_NOT_IDLE`/`READ_TIMEOUT` with `startFailureStage`, a read timeout performs a same-call logical disconnect/revoke without claiming the physical close, and the hard watchdog close remains. Covered API 35/x86_64 emulator and API 37/arm64 tablet flows passed [acceptance](docs/testing-phone-control.md); the new auto-port, permanent, question-bridge and handoff flows are API 35/x86_64 emulator evidence only, and broad vendor compatibility, TalkBack and real-model reasoning remain unverified. See [phone control](docs/phone-control.md).
- Optional system phone-progress overlays have their own permission/demo, bounded display service and real-window execution/capture barrier. Notifications remain independent; task progress separates native facts from unverified model narration. The same surface can project a current-task single-choice question or bounded Ready/Error/Goal/RestartInterrupted notice from local stdout notification events, fenced by task/session/launch/form schema; unknown sends are not retried and complex forms open the chat. This is distinct from the in-app browser window.

## System Entry Points

| File | Role |
|---|---|
| `app/src/main/java/io/andchamber/app/AndChamberApplication.kt` | Process entry: starts Koin (`di/AppModule.kt`) and the notification observer |
| `app/src/main/java/io/andchamber/app/MainActivity.kt` | Single launcher activity (singleTop): hosts Compose `AndChamberApp`, turns notification taps into `OpenSessionLink`, owns the optional foreground local-runtime screen hold |
| `app/src/main/java/io/andchamber/app/service/RuntimeService.kt` | `specialUse` foreground service that runs runtime operations and keeps the server alive |
| `app/src/main/AndroidManifest.xml` | Components, permissions (FGS, notifications, all-files access, microphone), locale config |
| `app/src/main/assets/runtime-manifest.json` | Pinned runtime: Alpine rootfs per ABI (URL + SHA-256), OpenChamber/OpenCode versions, port 3000 |
| `app/build.gradle.kts` | App build; offline Exec task `buildAndChamberRuntimeNative` compiles PRoot JNI libs from prepared sources (NDK 29.0.14206865); Sync task `syncNativeLicenseAssets` packages `third_party/native-runtime/` as `assets/licenses/native/` in every variant; env-driven release signing (`ANDCHAMBER_*`); debug-only `andchamber.testApplicationId` override |
| `gradle.properties` | Canonical release version as literal `andchamber.versionName` / `andchamber.versionCode` (currently 0.0.39 / 39); the release workflow checks them against the tag |
| `build.gradle.kts`, `settings.gradle.kts` | AGP 8.5.2, Kotlin 2.0.21; single `:app` module |
| `.github/workflows/ci.yml` | Push/PR: offline Python tests, JVM unit tests and debug APK |
| `.github/workflows/release.yml` | Tag `v*`: Python/JVM tests, signed release APK, `apksigner` verify, sha256, GitHub release (notes from `docs/release-notes/v<ver>.md`) |

## Architecture at a Glance

```text
MainActivity ──► ui/ (Compose: home, web WebView, independent browser, launch flow)
                   │  last-instance restore and Cloudflare Access gateway handoff stay in ui/web
                   │  runtime contract (LocalRuntimeController, SharedStorageController,
                   │  RemoteServerStore, WebSessionAuthenticator, WebSessionCookies)
                   │
                   │  Local chat ──► ui/browser (one public-https WebView, profile andchamber_browser_v1)
                   │                     │ ready foreground opens default to agent control; takeover revokes
                   │                     ▼
                   │                  browser/ (driver contract, URL policy, provider registry and page adapter)
                   │                     ▲
                   │                     │ loopback tool server, plugin tool andchamber_web, no CDP
                   │                     │
                   └─ runtime/process syncs the OpenCode plugin and writes the launch endpoint file
                   ▼
service/RuntimeService (FGS) ──► runtime/LocalRuntimeController (state machine, watchdog)
                                   ├─ runtime/install   (download → extract → apk/npm → node-pty → verify → atomic swap)
                                   ├─ runtime/process   (PRoot argv/env, OpenChamber server, readiness, process ownership)
                                   ├─ runtime/storage   (bind decisions, shared folder prep, git config for FUSE)
                                   ├─ runtime/network   (live system DNS → atomic resolv.conf)
                                   ├─ runtime/fs        (no-follow delete/size/copy)
                                   └─ runtime/manifest  (pinned versions)
OpenChamber stdout "[OpenChamberDesktopNotify] {json}" ──► notify/ ──► Android notifications
notification actions ──► launch-guarded data/LocalApiClient ──► OpenChamber HTTP (prompt, permission, form)
ui/web ──► data/WebSessionProfiles ──► bound WebSessionCookies ──► authentication and WebView
HTTP downloads ──► data/HttpDownloadStarter ──► MediaStore or app-external Downloads
```

## Directory Map

| Directory | Responsibility Summary | Detailed Map |
|---|---|---|
| `app/src/main/java/io/andchamber/app/` | Package root: Application (Koin + notification observer) and the single launcher activity | [View Map](app/src/main/java/io/andchamber/app/codemap.md) |
| `…/app/runtime/` | Lifecycle of the on-device runtime (install, start, stop, repair, restart, uninstall, crash recovery) and the contract types the UI and service depend on | [View Map](app/src/main/java/io/andchamber/app/runtime/codemap.md) |
| `…/app/runtime/install/` | Builds Alpine + Node + OpenChamber in staging, verifies it, activates it by atomic directory swap; verified rootfs download and extraction; interrupted-swap recovery | [View Map](app/src/main/java/io/andchamber/app/runtime/install/codemap.md) |
| `…/app/runtime/process/` | Runs, probes and stops the OpenChamber server inside PRoot: command lines, guest env, readiness/process identity, and the bounded native RPC phone-plugin registration probe | [View Map](app/src/main/java/io/andchamber/app/runtime/process/codemap.md) |
| `…/app/runtime/storage/` | Optional shared storage `/sdcard/AndChamber`: per-start bind decisions, shared folder preparation, git config for FUSE repos; secrets stay private | [View Map](app/src/main/java/io/andchamber/app/runtime/storage/codemap.md) |
| `…/app/runtime/fs/` | Tree operations that never follow symbolic links (delete, size, copy, replace) | [View Map](app/src/main/java/io/andchamber/app/runtime/fs/codemap.md) |
| `…/app/runtime/network/` | Lifecycle-bound network observation and atomic guest `/etc/resolv.conf` updates from active-network DNS | [View Map](app/src/main/java/io/andchamber/app/runtime/network/codemap.md) |
| `…/app/runtime/manifest/` | Reads and validates `runtime-manifest.json` (rootfs pins, versions, port) | [View Map](app/src/main/java/io/andchamber/app/runtime/manifest/codemap.md) |
| `…/app/service/` | `RuntimeService` foreground service: status notification, operation dispatch, wake lock, lifetime policy | [View Map](app/src/main/java/io/andchamber/app/service/codemap.md) |
| `…/app/notify/` | Native notification actions, visible-local-page suppression, failed-action/draft retention and versioned Sent cleanup; bootstrap and session links | [View Map](app/src/main/java/io/andchamber/app/notify/codemap.md) |
| `…/app/data/` | Profile-bound authentication, launch-guarded loopback API, controlled HTTP downloads and encrypted remote server persistence | [View Map](app/src/main/java/io/andchamber/app/data/codemap.md) |
| `…/app/di/` | Koin composition root (`appModule`) | [View Map](app/src/main/java/io/andchamber/app/di/codemap.md) |
| `…/app/i18n/` | Maps runtime message keys and install steps to string resources (English / Simplified Chinese) | [View Map](app/src/main/java/io/andchamber/app/i18n/codemap.md) |
| `…/app/browser/` | Embedded-browser lease, loopback command admission, and the private credential file. No WebView. Experimental; emulator fixture checks passed, arm64 runtime end-to-end validation pending. | — |
| `…/app/phone/` | Independent phone-control admission and bounded standing core, native ADB driver, loopback direct-start tool, RPC registration readiness, bounded auto-port pairing/connect discovery with bind validation, permanent standing durations, native pairing/foreground-service and overlay coordination; does not relax browser authorization | [View Map](app/src/main/java/io/andchamber/app/phone/codemap.md) |
| `…/app/ui/` | Compose root: navigation (home, web, browser), launch decision and cover, notification deep links, system bars, permission requests | [View Map](app/src/main/java/io/andchamber/app/ui/codemap.md) |
| `…/app/ui/home/` | Control-room home: local runtime card, shared-storage card, remote servers | [View Map](app/src/main/java/io/andchamber/app/ui/home/codemap.md) |
| `…/app/ui/web/` | Profile-bound WebView pool, session/page loading, navigation and JS bridge, HTTP download handoff, visibility reporting and isolation/error overlays | [View Map](app/src/main/java/io/andchamber/app/ui/web/codemap.md) |
| `…/app/ui/browser/` | Floating in-app browser window, its single WebView driver and the presenter that binds grants to the chat view. Ready foreground opens default to agent control; takeover and hiding revoke it. | [View Map](app/src/main/java/io/andchamber/app/ui/browser/codemap.md) |
| `…/app/ui/phone/` | Native opt-in, configure-once standing scope with explicit permanent duration choices, target/task/continuation state, auto-port discovery and question/notice presentation, stop/resume and unconfirmed result presentation; separate connection/control/task state | [Design and boundaries](docs/phone-control.md) |
| `…/app/ui/components/` | Stateless shared Compose widgets | [View Map](app/src/main/java/io/andchamber/app/ui/components/codemap.md) |
| `…/app/ui/theme/` | Material 3 color schemes, typography, shapes, extended `ChamberColors` | [View Map](app/src/main/java/io/andchamber/app/ui/theme/codemap.md) |
| `app/src/main/assets/` | Runtime manifest pins, the Node preload shim, and the App-injected OpenCode browser-tool and phone-tool plugins | [View Map](app/src/main/assets/codemap.md) |
| `scripts/` | Build-time tools: network source preparation for the native runtime, offline NDK compilation, the legacy Termux pipeline, and mirror health checks | [View Map](scripts/codemap.md) |
| `runtime_tools/` | Python library and SHA-256 lock files for the PRoot build (Termux lock and runtime source lock) | [View Map](runtime_tools/codemap.md) |
| `third_party/native-runtime/` | License texts (PRoot COPYING, talloc LGPL-3.0 and GPL-3.0, libandroid-shmem BSD-3-Clause) and the provenance record for native components; packaged into the APK by `syncNativeLicenseAssets` | [View README](third_party/native-runtime/README.md) |
| `third_party/phone-adb/` | ADB/SPAKE2/TLS dependency provenance, bundled license texts and rebuild/relink guidance | [View README](third_party/phone-adb/README.md) |
| `fdroid/` | Candidate F-Droid metadata and submission guide; local fixture build/scans passed, official build, review and signing remain pending | [View README](fdroid/README.md) |

## Not Mapped

- `app/src/test/` — JVM unit tests (runtime, storage, notifications, i18n string parity).
- `scripts/tests/` — offline Python tests for the native source build and the license assets, plus Node tests for the browser-tool plugin and the local-provider gate adapters (see [scripts/codemap.md](scripts/codemap.md)).
- `app/src/main/java/io/andchamber/app/ui/preview/` — Compose previews and fake contract implementations.
- `app/src/main/res/` — resources (`values/` English, `values-zh/` Simplified Chinese, launcher/notification icons).
- `docs/` — icon, screenshots, release notes, `docs/embedded-browser-control.md`, and the phone-control/testing/fixture documents. Phone acceptance distinguishes API 35/x86_64 emulator and covered API 37/arm64 tablet evidence; auto-port discovery, permanent durations, the question/notice bridge and the handoff flow are API 35 emulator evidence only, and broad OEM compatibility, TalkBack and real-model reasoning are not established.

Change detection excludes tests, documentation and translations. The current codemap utility skips dot-directories, so review `.github/workflows/` directly when checking mapping changes.
