# scripts/

## Responsibility

Build-time tools for the native PRoot runtime. They run from Gradle (`app/build.gradle.kts`) and from CI, never on the device. The default path compiles PRoot, talloc and libandroid-shmem from pinned source archives:

- `prepare_android_runtime_sources.py` (default, network): the only network step. Downloads the archives pinned in `runtime_tools/runtime_sources.lock.json`, checks their SHA-256 and the license file hash, extracts them safely into `build/runtime-sources/<component>/` and writes `sources.json`. Run explicitly before Gradle; Gradle does not run it.
- `build_android_runtime_native.py` (default, offline): compiles both ABIs (arm64-v8a, x86_64) from the prepared sources with NDK 29.0.14206865 at API 26, and writes the jniLibs names to `build/generated/runtime-jni`. It copies every source into the work directory first and only reads the prepared tree. It verifies `sources.json` against the current lock and refuses overlapping source, output and work directories before any write.
- Patches in `runtime_tools/patches/`, applied to build copies only: `proot-5.1.107.97-andchamber-string-include.patch`, `libandroid-shmem-0.7-andchamber-tmpdir.patch` (`PROOT_TMP_DIR` for key symlinks) and `loader-info-posix-awk.awk` (POSIX awk replacement for the gawk-only upstream script, used by the loader build).

Legacy Termux utilities (manual only, not run by Gradle or CI):

- `prepare_android_runtime_assets.py`: resolves, downloads, verifies and extracts the Termux `proot` dependency closure into a per-ABI prefix tree with a manifest.
- `prepare_android_runtime_native_libs.py`: takes that prefix tree and renames the executables and shared libraries into `jniLibs` names.

## Design (legacy Termux utilities)

- **Two-stage pipeline.** Stage 1 produces a Linux-style prefix at `<out>/andchamber-runtime/<abi>/prefix` plus `manifest.json`. Stage 2 reads only that prefix. Each stage can be rerun alone.
- **Lock-driven and reproducible.** By default the script loads `runtime_tools/termux_assets.lock.json` and uses its pinned filenames and SHA-256 values. It does not rebuild the dependency graph unless `--refresh-lock-file` is passed.
- **Legacy: mirror fallback with integrity.** `TERMUX_MAIN_FALLBACK_BASE_URLS` lists several mirrors. `ANDCHAMBER_ANDROID_TERMUX_MAIN_BASE_URLS` / `_BASE_URL` can prepend more. Each payload is checked against its pinned SHA-256 (`verify_sha256`), so a bad mirror is skipped and the next one is tried.
- **Optional archive.** A lock `package_archive` (URL plus SHA-256) can point to one ZIP of all pinned `.deb` files. If it is absent or unavailable, the script falls back to per-package mirror fetches.
- **Deterministic output.** Files are written with `sort_keys`, sorted iteration, normalized shebangs (`/data/data/com.termux/files/usr/bin/X` becomes `/usr/bin/env X`), and no timestamps. Symlinks and hardlinks are recorded in the manifest's `links` list instead of being recreated.
- **Name-based native mapping.** `NATIVE_EXECUTABLES` maps `bin/proot`, `libexec/proot/loader` and `loader32` to `libandchamber_android_proot*.so`. `RUNTIME_LIBRARIES` matches `libtalloc.so.*` by glob because the SONAME changes with the version. `patch_needed` rewrites the proot binary's `libtalloc.so.2` NEEDED string to `libtalloc.so`, padding with NUL bytes.
- **Fail closed.** Missing required executables or libraries raise `FileNotFoundError`. Unknown lock versions raise `ValueError`. A mirror health check that finds no healthy mirror exits 1.
- **Mirror health check is self-contained.** `--check-mirrors` probes each `configured_termux_main_base_urls()` entry directly with `probe_termux_mirror`. It calls `download_bytes` without the fallback chain, so one mirror's result is never borrowed from another. A mirror is `ok` only when its Packages index validates (`_validate_termux_payload`, `parse_packages_index`) and contains the `ROOT_PACKAGES`. The report keeps `termux_arch`, `healthy_count`, `recommended_base_urls` and `mirrors` (per-mirror `base_url`, `url`, `status`, plus `package_count` or `error`). Healthy mirrors exit 0 and none exits 1.
- **Legacy argument rules.** For the legacy Termux script, `--output-dir` is required only for asset generation. `--check-mirrors`, `--lock-only` and `--build-package-archive` do not need it and write no asset tree.

## Flow

Default source path (`build/runtime-sources` then `build/generated/runtime-jni`):

1. `prepare_android_runtime_sources.py --output-dir build/runtime-sources` (network): for each lock component, download or copy the archive, verify its SHA-256, extract with `is_safe_member` checks into `<component>/`, verify the license file hash and write `sources.json`.
2. `build_android_runtime_native.py --sources-dir build/runtime-sources --ndk-dir <ndk> --output-dir build/generated/runtime-jni --work-dir build/runtime-native` (offline): check the NDK revision, verify `sources.json` against the current lock, then per ABI build talloc (configure/make, SONAME rewritten to `libtalloc.so`), libandroid-shmem (patched), the primary and 32-bit loaders and PRoot, and copy them to the jniLibs names. Each step runs on a fresh copy in the work directory.

Legacy Termux flow (manual). Stage 1: `prepare_android_runtime_assets.py --output-dir <gen> --lock-file <lock>`
1. Clear `<gen>/andchamber-runtime`. Load lock (or refresh it when `--refresh-lock-file` is passed).
2. For each ABI (`arm64-v8a` to `aarch64`, `x86_64` to `x86_64`): fetch each locked `.deb` through `download_locked_package`, or from `--local-debs-dir`.
3. `load_data_tar_bytes_from_deb` reads the ar member `data.tar.*`. `open_data_tar` decompresses it. `mirror_data_tar` strips the Termux prefix and writes files into the staging prefix. Links are recorded.
4. `prune_staging_prefix` drops `include`, `pkgconfig`, docs, man and cache dirs. Writes `manifest.json` through `serializable_manifest`.

Stage 2: `prepare_android_runtime_native_libs.py --linux-assets-dir <gen>/runtime-assets --output-dir <gen>/runtime-jni`
1. Recreate `<output>` and create `arm64-v8a/` and `x86_64/` subdirectories.
2. Copy and rename `bin/proot`, `libexec/proot/loader`, `loader32` to `libandchamber_android_proot.so`, `libandchamber_android_proot_loader.so`, `libandchamber_android_proot_loader32.so` (mode 0755).
3. Copy `libandroid-shmem.so` (required), `libc++_shared.so` (optional) and `libtalloc.so.*` to `libtalloc.so` (required). Rewrite the proot NEEDED name.

Mirror health (developer only): `prepare_android_runtime_assets.py --check-mirrors [--termux-arch x86_64] [--mirror-report <path>]`. It writes a JSON report and needs no `--output-dir`.

Lock refresh (developer only): `prepare_android_runtime_assets.py --refresh-lock-file --lock-only`. This queries the Packages index for each arch, resolves the closure from `ROOT_PACKAGES=["proot"]`, and writes the lock. It does not extract anything.

## Integration

- **Gradle** (`app/build.gradle.kts`): `buildAndChamberRuntimeNative` (Exec, offline, runs `scripts/build_android_runtime_native.py`), which `preBuild` depends on. Its declared inputs are `runtime_tools/runtime_sources.lock.json`, `scripts/build_android_runtime_native.py`, `scripts/prepare_android_runtime_sources.py`, the three files in `runtime_tools/patches/`, the NDK `source.properties`, the NDK version constant and the prepared source directory. The `runtime_tools/` Python modules and `termux_assets.lock.json` are not inputs of this task. The jniLibs srcDir is wired to `runtime-jni`. Source preparation is a separate network step that Gradle does not run.
- **Legacy Termux tasks are deleted**: `prepareAndChamberRuntimeAssets` and `prepareAndChamberRuntimeNativeLibs` no longer exist in `app/build.gradle.kts`. Only the manual scripts listed above remain, and they rely on `runtime_tools/termux_assets.py` and its lock.
- **Shared module**: the legacy scripts import `runtime_tools/termux_assets.py` through `REPO_ROOT`. The source-build scripts import nothing from `runtime_tools/` beyond their own files.
- **CI** (`.github/workflows/ci.yml`, `release.yml`): installs the NDK, checks host tools with `command -v`, and caches `build/runtime-sources` (key: `runtime_sources.lock.json` plus `prepare_android_runtime_sources.py`); source preparation runs only on a cache miss. `ci.yml` also caches `build/generated/runtime-jni` (key: lock, both scripts, `runtime_tools/patches/**` and the NDK version). `release.yml` does not cache JNI output and compiles it from source on every run.
- **Release** (`.github/workflows/release.yml`): runs `./gradlew :app:assembleRelease`, which triggers the same Exec chain. Native inputs are pinned by the source lock; this does not make the whole APK reproducible.
- **Runtime**: the Kotlin side loads the renamed libraries by name (`EmbeddedCommandSuite.kt`: `PROOT_LIBRARY_NAME`, `LOADER_LIBRARY_NAME`, `LOADER32_LIBRARY_NAME`). Nothing in this folder runs on device.
- **Tests**: stdlib `unittest`, offline. Run with `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` (46 tests in total). `test_prepare_android_runtime_assets.py` covers the legacy Termux scripts: per-mirror results, recommended order, exit codes, no asset output from `--check-mirrors`, and the `--output-dir` rule. `test_runtime_native_sources.py` covers source preparation (hash checks, safe extraction, license hash), lock-verified provenance (stale fields, duplicates, escaping `source_root`), disjoint source/output/work directories, the NDK revision check and the PRoot version contract. Tests patch `urllib.request.urlopen`, `socket.create_connection` and `socket.getaddrinfo` to fail if they touch the network.
- **`--local-debs-dir` contract**: the caller provides a directory with one subdirectory per Termux arch (`aarch64/`, `x86_64/`) holding `.deb` files. The exact locked filename is preferred. Otherwise the first sorted `<name>_*_<arch>.deb` match is used. No build script is bundled.
- **Known gaps**: the source-build lane does not claim reproducible or F-Droid-ready output for the whole APK. Only the native stage is covered by the offline tests and direct compiles.
