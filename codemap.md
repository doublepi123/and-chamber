# Repository Atlas: AndChamber

## Project Responsibility

AndChamber is an Android app (`io.andchamber.app`, Kotlin + Jetpack Compose, minSdk 26, target 35) that runs the OpenChamber web server (OpenChamber 2.4.0 + OpenCode 2.0.32) natively on the phone and shows it in a full-screen WebView. It builds an Alpine Linux 3.24 userland (Node.js 24) in app-private storage, runs it under PRoot (no root), keeps the server alive with a foreground service, signs the WebView in automatically, bridges OpenChamber's notifications to Android notifications with quick replies, and can also connect to remote OpenChamber servers.

Key constraints:
- On-device mode is arm64-v8a only (x86_64 Android seccomp blocks `fork`, which PRoot does not translate); x86_64 builds can only use remote servers.
- OpenCode stays on v2 (OpenChamber requires ≥ 2.0.20); no downgrades.
- Optional shared storage `/sdcard/AndChamber`: `projects/` → `~/projects`, `opencode/` → `~/.config/opencode`. Secrets (`service.json`, `auth.json`, session DB, OpenChamber data) and `node_modules` stay private. The private workspace is `~/projects-private`.
- Guest paths: home `~` = `/root` (bind of `<runtime>/home`), server working directory `/root`.

## System Entry Points

| File | Role |
|---|---|
| `app/src/main/java/io/andchamber/app/AndChamberApplication.kt` | Process entry: starts Koin (`di/AppModule.kt`) and the notification observer |
| `app/src/main/java/io/andchamber/app/MainActivity.kt` | Single launcher activity (singleTop): hosts Compose `AndChamberApp`, turns notification taps into `OpenSessionLink` |
| `app/src/main/java/io/andchamber/app/service/RuntimeService.kt` | `specialUse` foreground service that runs runtime operations and keeps the server alive |
| `app/src/main/AndroidManifest.xml` | Components, permissions (FGS, notifications, all-files access, microphone), locale config |
| `app/src/main/assets/runtime-manifest.json` | Pinned runtime: Alpine rootfs per ABI (URL + SHA-256), OpenChamber/OpenCode versions, port 3000 |
| `app/build.gradle.kts` | App build; Exec tasks that prepare PRoot jniLibs from pinned Termux packages; env-driven release signing (`ANDCHAMBER_*`) |
| `build.gradle.kts`, `settings.gradle.kts` | AGP 8.5.2, Kotlin 2.0.21; single `:app` module |
| `.github/workflows/ci.yml` | Push/PR: unit tests + debug APK |
| `.github/workflows/release.yml` | Tag `v*`: signed release APK, `apksigner` verify, sha256, GitHub release (notes from `docs/release-notes/v<ver>.md`) |

## Architecture at a Glance

```text
MainActivity ──► ui/ (Compose: home, web WebView, launch flow)
                   │  runtime contract (LocalRuntimeController, SharedStorageController,
                   │  RemoteServerStore, WebSessionAuthenticator)
                   ▼
service/RuntimeService (FGS) ──► runtime/LocalRuntimeController (state machine, watchdog)
                                   ├─ runtime/install   (download → extract → apk/npm → node-pty → verify → atomic swap)
                                   ├─ runtime/process   (PRoot argv/env, OpenChamber server, readiness, process ownership)
                                   ├─ runtime/storage   (bind decisions, shared folder prep, git config for FUSE)
                                   ├─ runtime/network   (resolv.conf from system DNS)
                                   ├─ runtime/fs        (no-follow delete/size/copy)
                                   └─ runtime/manifest  (pinned versions)
OpenChamber stdout "[OpenChamberDesktopNotify] {json}" ──► notify/ ──► Android notifications
notification actions ──► data/LocalApiClient ──► OpenChamber HTTP (/api/session/:id/prompt, permission, form)
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
| `…/app/runtime/network/` | Guest `/etc/resolv.conf` from the device's active network DNS | [View Map](app/src/main/java/io/andchamber/app/runtime/network/codemap.md) |
| `…/app/runtime/manifest/` | Reads and validates `runtime-manifest.json` (rootfs pins, versions, port) | [View Map](app/src/main/java/io/andchamber/app/runtime/manifest/codemap.md) |
| `…/app/service/` | `RuntimeService` foreground service: status notification, operation dispatch, wake lock, lifetime policy | [View Map](app/src/main/java/io/andchamber/app/service/codemap.md) |
| `…/app/notify/` | OpenChamber notifications and AndChamber runtime errors → Android notifications with reply / permission / form actions; notification bootstrap; open-session link | [View Map](app/src/main/java/io/andchamber/app/notify/codemap.md) |
| `…/app/data/` | Network and persistence adapters: WebView session preparation (login, cookies), loopback API client, encrypted remote server store | [View Map](app/src/main/java/io/andchamber/app/data/codemap.md) |
| `…/app/di/` | Koin composition root (`appModule`) | [View Map](app/src/main/java/io/andchamber/app/di/codemap.md) |
| `…/app/i18n/` | Maps runtime message keys and install steps to string resources (English / Simplified Chinese) | [View Map](app/src/main/java/io/andchamber/app/i18n/codemap.md) |
| `…/app/ui/` | Compose root: navigation (home, web), launch decision and cover, notification deep links, system bars, permission requests | [View Map](app/src/main/java/io/andchamber/app/ui/codemap.md) |
| `…/app/ui/home/` | Control-room home: local runtime card, shared-storage card, remote servers | [View Map](app/src/main/java/io/andchamber/app/ui/home/codemap.md) |
| `…/app/ui/web/` | Full-screen OpenChamber WebView per server: pool, session/page loading, navigation policy, JS bridge, downloads, overlays | [View Map](app/src/main/java/io/andchamber/app/ui/web/codemap.md) |
| `…/app/ui/components/` | Stateless shared Compose widgets | [View Map](app/src/main/java/io/andchamber/app/ui/components/codemap.md) |
| `…/app/ui/theme/` | Material 3 color schemes, typography, shapes, extended `ChamberColors` | [View Map](app/src/main/java/io/andchamber/app/ui/theme/codemap.md) |
| `app/src/main/assets/` | Runtime manifest pins and the Node preload shim copied into the guest | [View Map](app/src/main/assets/codemap.md) |
| `scripts/` | Build-time tools: pinned Termux packages → per-ABI prefix → renamed jniLibs (`libandchamber_android_proot*.so`) | [View Map](scripts/codemap.md) |
| `runtime_tools/` | Python library and SHA-256 lock file for the Termux-based PRoot build | [View Map](runtime_tools/codemap.md) |

## Not Mapped

- `app/src/test/` — JVM unit tests (runtime, storage, notifications, i18n string parity).
- `app/src/main/java/io/andchamber/app/ui/preview/` — Compose previews and fake contract implementations.
- `app/src/main/res/` — resources (`values/` English, `values-zh/` Simplified Chinese, launcher/notification icons).
- `docs/` — icon, screenshots, release notes.
