# app/src/main/assets/

## Responsibility

Packaged static inputs read at runtime by the app, not by the build:

- `runtime-manifest.json`: pins the guest runtime (Alpine rootfs per ABI, OpenChamber and OpenCode versions, port).
- `android-shim.cjs`: a Node preload script copied into the guest rootfs to work around an Android SELinux failure.
- `browser-tool/package.json`, `browser-tool/index.js`: the App-injected OpenCode plugin tool `andchamber_web` for the in-app browser. Both files are synced into the private guest home on launch.
- `phone-tool/package.json`, `phone-tool/index.js`: the independent opt-in `andchamber_phone` plugin. Its per-launch endpoint and native grant are separate from the browser.

## Design

- **Pinned manifest, not discovery.** `runtime-manifest.json` is the single source of truth for the guest versions. `runtimeVersion` (date-based), `alpineVersion`, `openChamberVersion`, `openCodeVersion` and `port` are top-level. Per-ABI entries (`arm64-v8a`, `x86_64`) carry `alpineUrl` and `alpineSha256`, so the rootfs download is verified before extraction.
- **Schema versioned.** `schemaVersion: 1` lets the Kotlin parser reject unknown layouts.
- **Shim is a plain CJS file.** `android-shim.cjs` wraps `os.networkInterfaces`. If the original throws (`uv_interface_addresses` gives EACCES under SELinux on Android), it returns a synthetic loopback-only `lo` entry. It is `'use strict'`, has no dependencies, and is deliberately small.
- **Browser plugin is a zero-dependency ESM package.** `browser-tool/index.js` registers one tool, `andchamber_web`, with one action per call. It reads the endpoint file named by `ANDCHAMBER_BROWSER_TOOL_FILE` on every call and never prints the token.
- **Phone plugin is also zero-dependency ESM.** It reads `ANDCHAMBER_PHONE_TOOL_FILE` on each call, uses V2 execution-context labels, cannot authorize itself, propagates cancellation and returns capture bytes as a file part rather than duplicating them as text.
- **Assets are not compiled.** Files are read as bytes or text, so changing them needs no Kotlin change, only a rebuild.

## Flow

1. Build: Gradle packages `app/src/main/assets/` into the APK. Generated Termux output is not merged here (see `scripts/codemap.md`).
2. Runtime manifest: `RuntimeManifest.kt` (`ASSET_NAME = "runtime-manifest.json"`) reads the JSON from `context.assets`. The installer then picks the ABI entry matching the device and downloads the Alpine rootfs from `alpineUrl`, checking `alpineSha256`.
3. Shim: `di/AppModule.kt:182` reads `android-shim.cjs` as text from assets. `OpenChamberInstaller.kt` (`SHIM_GUEST_PATH = /opt/andchamber/android-shim.cjs`) writes it into the guest rootfs.
4. Launch: `ProotCommands` sets `NODE_OPTIONS=--require /opt/andchamber/android-shim.cjs` in the guest environment, so every Node process loads the shim before OpenChamber starts.
5. Browser plugin: `BrowserPluginAssets` copies `browser-tool/` into the private guest home on server start and adds it to the launch-only `plugins` overlay. A failed copy skips the plugin for that launch and does not stop the runtime. See `docs/embedded-browser-control.md`.

## Integration

- `app/src/main/java/io/andchamber/app/runtime/manifest/`: parses `runtime-manifest.json`.
- `app/src/main/java/io/andchamber/app/runtime/install/OpenChamberInstaller.kt`: consumes the Alpine and OpenChamber/OpenCode versions and writes the shim.
- `app/src/main/java/io/andchamber/app/runtime/process/ProotCommands.kt`: sets `NODE_OPTIONS`. Tests `ProotCommandsTest.kt` and `OpenChamberInstallerTest.kt` assert both the path and the env value.
- `app/src/main/java/io/andchamber/app/di/AppModule.kt`: loads the shim text via Koin wiring and reads the browser adapter asset.
- `scripts/tests/browser-tool-plugin.test.cjs`: Node tests for the plugin. Run with `node --test scripts/tests/browser-tool-plugin.test.cjs`.
- Versions here must stay consistent with the `runtimeVersion` and release notes. Changing `alpineSha256` without changing `alpineUrl` breaks installs. Verify both together.
- `.slim/codemap.json` tracks content hashes of these two files.
