# scripts/

## Responsibility

Build-time tools that turn pinned Termux packages into Android-ready runtime inputs. They run from Gradle (`app/build.gradle.kts`) and from CI, never on the device:

- `prepare_android_runtime_assets.py`: resolves, downloads, verifies and extracts the Termux `proot` dependency closure into a per-ABI prefix tree with a manifest.
- `prepare_android_runtime_native_libs.py`: takes that prefix tree and renames the executables and shared libraries into `jniLibs` names so Android installs and loads them.

## Design

- **Two-stage pipeline.** Stage 1 produces a Linux-style prefix at `<out>/andchamber-runtime/<abi>/prefix` plus `manifest.json`. Stage 2 reads only that prefix. Each stage can be rerun alone.
- **Lock-driven and reproducible.** By default the script loads `runtime_tools/termux_assets.lock.json` and uses its pinned filenames and SHA-256 values. It does not rebuild the dependency graph unless `--refresh-lock-file` is passed.
- **Mirror fallback with integrity.** `TERMUX_MAIN_FALLBACK_BASE_URLS` lists several mirrors. `ANDCHAMBER_ANDROID_TERMUX_MAIN_BASE_URLS` / `_BASE_URL` can prepend more. Each payload is checked against its pinned SHA-256 (`verify_sha256`), so a bad mirror is skipped and the next one is tried.
- **Optional archive.** A lock `package_archive` (URL plus SHA-256) can point to one ZIP of all pinned `.deb` files. If it is absent or unavailable, the script falls back to per-package mirror fetches.
- **Deterministic output.** Files are written with `sort_keys`, sorted iteration, normalized shebangs (`/data/data/com.termux/files/usr/bin/X` becomes `/usr/bin/env X`), and no timestamps. Symlinks and hardlinks are recorded in the manifest's `links` list instead of being recreated.
- **Name-based native mapping.** `NATIVE_EXECUTABLES` maps `bin/proot`, `libexec/proot/loader` and `loader32` to `libandchamber_android_proot*.so`. `RUNTIME_LIBRARIES` matches `libtalloc.so.*` by glob because the SONAME changes with the version. `patch_needed` rewrites the proot binary's `libtalloc.so.2` NEEDED string to `libtalloc.so`, padding with NUL bytes.
- **Fail closed.** Missing required executables or libraries raise `FileNotFoundError`. Unknown lock versions raise `ValueError`. A mirror health check that finds no healthy mirror exits 1.

## Flow

Stage 1: `prepare_android_runtime_assets.py --output-dir <gen> --lock-file <lock>`
1. Clear `<gen>/andchamber-runtime`. Load lock (or refresh it when `--refresh-lock-file` is passed).
2. For each ABI (`arm64-v8a` to `aarch64`, `x86_64` to `x86_64`): fetch each locked `.deb` through `download_locked_package`, or from `--local-debs-dir`.
3. `load_data_tar_bytes_from_deb` reads the ar member `data.tar.*`. `open_data_tar` decompresses it. `mirror_data_tar` strips the Termux prefix and writes files into the staging prefix. Links are recorded.
4. `prune_staging_prefix` drops `include`, `pkgconfig`, docs, man and cache dirs. Writes `manifest.json` through `serializable_manifest`.

Stage 2: `prepare_android_runtime_native_libs.py --linux-assets-dir <gen>/runtime-assets --output-dir <gen>/runtime-jni`
1. Recreate `<output>` and create `arm64-v8a/` and `x86_64/` subdirectories.
2. Copy and rename `bin/proot`, `libexec/proot/loader`, `loader32` to `libandchamber_android_proot.so`, `libandchamber_android_proot_loader.so`, `libandchamber_android_proot_loader32.so` (mode 0755).
3. Copy `libandroid-shmem.so` (required), `libc++_shared.so` (optional) and `libtalloc.so.*` to `libtalloc.so` (required). Rewrite the proot NEEDED name.

Lock refresh (developer only): `prepare_android_runtime_assets.py --refresh-lock-file --lock-only`. This queries the Packages index for each arch, resolves the closure from `ROOT_PACKAGES=["proot"]`, and writes the lock. It does not extract anything.

## Integration

- **Gradle** (`app/build.gradle.kts`): `prepareAndChamberRuntimeAssets` (Exec, `python3` plus stage 1) and `prepareAndChamberRuntimeNativeLibs` (Exec, depends on the first, runs stage 2). `preBuild` depends on the second. Output dirs are `build/generated/runtime-assets` and `build/generated/runtime-jni`. The jniLibs srcDir is wired to `runtime-jni`, so the renamed `.so` files are packaged. `useLegacyPackaging = true` keeps them extractable as files.
- **Shared module**: both scripts import `runtime_tools/termux_assets.py` through `REPO_ROOT`. The Python path is patched at import time.
- **CI** (`.github/workflows/ci.yml`): caches `build/generated/runtime-assets` and `build/generated/runtime-jni`, keyed by a hash of the lock, `termux_assets.py` and `prepare_android_runtime_*.py`. A key change means a fresh fetch.
- **Release** (`.github/workflows/release.yml`): runs `./gradlew :app:assembleRelease`, which triggers the same Exec chain. The pinned lock gives reproducible native inputs.
- **Runtime**: the Kotlin side loads the renamed libraries by name (`EmbeddedCommandSuite.kt`: `PROOT_LIBRARY_NAME`, `LOADER_LIBRARY_NAME`, `LOADER32_LIBRARY_NAME`). Nothing in this folder runs on device.
- **Known gaps**: `prepare_android_runtime_assets.py` imports `scripts/android_visual_harness.py` (used by `--check-mirrors`) and references `scripts/build_android_runtime_native_debs.sh` (in the `--local-debs-dir` docs). Neither file exists in this checkout, so those paths fail if used.
