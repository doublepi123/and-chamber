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
- Native HTTP downloads use controlled redirects and the app process; they do not resume after process death. Unknown notification-send outcomes preserve the draft and session entry instead of automatically replaying the operation.
- Launch can restore the last used OpenChamber instance, and every instance remembers its own last page, so switching between instances returns to where each one was left. Cloudflare Access handoff stops the old page before reading cookies and logging in, so a late 401 cannot delete the new login cookie.
- PRoot JNI libraries are compiled from pinned sources with NDK 29.0.14206865. That source build is not a claim that the whole 0.x pre-release is reproducible, and this is not an F-Droid build.

## System Entry Points

| File | Role |
|---|---|
| `app/src/main/java/io/andchamber/app/AndChamberApplication.kt` | Process entry: starts Koin (`di/AppModule.kt`) and the notification observer |
| `app/src/main/java/io/andchamber/app/MainActivity.kt` | Single launcher activity (singleTop): hosts Compose `AndChamberApp`, turns notification taps into `OpenSessionLink` |
| `app/src/main/java/io/andchamber/app/service/RuntimeService.kt` | `specialUse` foreground service that runs runtime operations and keeps the server alive |
| `app/src/main/AndroidManifest.xml` | Components, permissions (FGS, notifications, all-files access, microphone), locale config |
| `app/src/main/assets/runtime-manifest.json` | Pinned runtime: Alpine rootfs per ABI (URL + SHA-256), OpenChamber/OpenCode versions, port 3000 |
| `app/build.gradle.kts` | App build; offline Exec task `buildAndChamberRuntimeNative` compiles PRoot JNI libs from prepared sources (NDK 29.0.14206865); Sync task `syncNativeLicenseAssets` packages `third_party/native-runtime/` as `assets/licenses/native/` in every variant; env-driven release signing (`ANDCHAMBER_*`); debug-only `andchamber.testApplicationId` override |
| `gradle.properties` | Canonical release version as literal `andchamber.versionName` / `andchamber.versionCode` (currently 0.0.20 / 20); the release workflow checks them against the tag |
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
| `…/app/runtime/process/` | Runs, probes and stops the OpenChamber server inside PRoot: command lines, guest env, readiness and identity checks, process ownership | [View Map](app/src/main/java/io/andchamber/app/runtime/process/codemap.md) |
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
| `…/app/ui/` | Compose root: navigation (home, web, browser), launch decision and cover, notification deep links, system bars, permission requests | [View Map](app/src/main/java/io/andchamber/app/ui/codemap.md) |
| `…/app/ui/home/` | Control-room home: local runtime card, shared-storage card, remote servers | [View Map](app/src/main/java/io/andchamber/app/ui/home/codemap.md) |
| `…/app/ui/web/` | Profile-bound WebView pool, session/page loading, navigation and JS bridge, HTTP download handoff, visibility reporting and isolation/error overlays | [View Map](app/src/main/java/io/andchamber/app/ui/web/codemap.md) |
| `…/app/ui/browser/` | Floating in-app browser window, its single WebView driver and the presenter that binds grants to the chat view. Ready foreground opens default to agent control; takeover and hiding revoke it. | [View Map](app/src/main/java/io/andchamber/app/ui/browser/codemap.md) |
| `…/app/ui/components/` | Stateless shared Compose widgets | [View Map](app/src/main/java/io/andchamber/app/ui/components/codemap.md) |
| `…/app/ui/theme/` | Material 3 color schemes, typography, shapes, extended `ChamberColors` | [View Map](app/src/main/java/io/andchamber/app/ui/theme/codemap.md) |
| `app/src/main/assets/` | Runtime manifest pins, the Node preload shim, and the App-injected OpenCode browser-tool plugin | [View Map](app/src/main/assets/codemap.md) |
| `scripts/` | Build-time tools: network source preparation for the native runtime, offline NDK compilation, the legacy Termux pipeline, and mirror health checks | [View Map](scripts/codemap.md) |
| `runtime_tools/` | Python library and SHA-256 lock files for the PRoot build (Termux lock and runtime source lock) | [View Map](runtime_tools/codemap.md) |
| `third_party/native-runtime/` | License texts (PRoot COPYING, talloc LGPL-3.0 and GPL-3.0, libandroid-shmem BSD-3-Clause) and the provenance record for native components; packaged into the APK by `syncNativeLicenseAssets` | [View README](third_party/native-runtime/README.md) |
| `fdroid/` | Candidate F-Droid metadata and submission guide; local fixture build/scans passed, official build, review and signing remain pending | [View README](fdroid/README.md) |

## Not Mapped

- `app/src/test/` — JVM unit tests (runtime, storage, notifications, i18n string parity).
- `scripts/tests/` — offline Python tests for the native source build and the license assets, plus Node tests for the browser-tool plugin and the local-provider gate adapters (see [scripts/codemap.md](scripts/codemap.md)).
- `app/src/main/java/io/andchamber/app/ui/preview/` — Compose previews and fake contract implementations.
- `app/src/main/res/` — resources (`values/` English, `values-zh/` Simplified Chinese, launcher/notification icons).
- `docs/` — icon, screenshots, release notes, and `docs/embedded-browser-control.md`. The plugin transport passed the emulator acceptance run on the current tree; the arm64 phone runtime is not verified.

Change detection excludes tests, documentation and translations. The current codemap utility skips dot-directories, so review `.github/workflows/` directly when checking mapping changes.
