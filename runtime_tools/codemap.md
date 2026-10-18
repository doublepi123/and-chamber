# runtime_tools/

## Responsibility

Shared Python library and data for the Termux-based Android runtime build. It contains:

- `termux_assets.py`: the pure logic. Termux repo and Packages index parsing, dependency closure, `.deb` / ar / tar reading, shebang normalization, and manifest serialization.
- `termux_assets.lock.json`: the reproducibility pin. It lists, per Android ABI, every `.deb` filename and SHA-256 needed for the `proot` closure.
- `__init__.py`: empty, which makes the folder importable as the `runtime_tools` package.

It has no I/O of its own (no HTTP). Downloads and writes are in `scripts/prepare_android_runtime_assets.py`.

## Design

- **Lock file as truth.** `termux_assets.lock.json` (`version: 1`) maps `arm64-v8a` to `aarch64` and `x86_64` to `x86_64`. Each entry has `name`, `version`, `filename`, `sha256` and `depends`. `root_packages` (`proot`) and `termux_main_base_url` are recorded for documentation. Current pins: proot 5.1.107.97, libandroid-shmem 0.7, libtalloc 2.5.0.
- **Dependency closure.** `resolve_dependency_closure` runs a BFS from `ROOT_PACKAGES` through `depends`. `parse_depends` takes the first non-ignored alternative of an `a | b` clause and strips version constraints. `IGNORED_DEPENDENCIES` (`termux-am`, `termux-tools`, `termux-exec`, and similar) are dropped because they need the Termux app.
- **Path model.** Termux files live under `/data/data/com.termux/files/usr`. `strip_termux_prefix` maps archive members into prefix-relative paths. `asset_prefix_dir` and `asset_manifest_path` place output at `andchamber-runtime/<abi>/prefix` and `.../manifest.json`.
- **Integrity helpers.** `verify_sha256` raises `ValueError` on mismatch. The `.deb` parser reads the ar header by hand (`load_data_tar_bytes_from_deb`), so no `dpkg` or `ar` binary is required. It accepts `data.tar` with `.xz`, `.gz`, `.bz2` or no compression.
- **Shebang rewriting.** `normalize_text_shebang` changes only the first line, `#!<termux prefix>/bin/<name>`, to `#!/usr/bin/env <name>`. Binary content is returned as-is.
- **Stable manifest.** `serializable_manifest` normalizes `files` and `links` (backslashes to slashes, leading slashes stripped), sorts them, and emits `asset_root`, `android_abi`, `termux_arch`, `root_packages`, `ignored_dependencies`, `files`, `links` and `packages`. `write_manifest` uses `indent=2, sort_keys=True`.

## Flow

Lock creation (`prepare_android_runtime_assets.py --lock-only`):
1. `read_packages_index` gets `dists/stable/main/binary-<arch>/Packages` and calls `parse_packages_index` (stanza-based, handles blank-line separators and the last stanza).
2. `resolve_dependency_closure` builds the closure. `build_lock_payload` writes JSON. This is the only path that changes the pins.

Asset extraction (stage 1 in `scripts/`):
1. `load_lock_file` or `locked_packages` returns `TermuxPackageRecord` objects per ABI.
2. Each `.deb` is passed to `load_data_tar_bytes_from_deb`, then `open_data_tar`.
3. `serializable_manifest` and `write_manifest` produce the per-ABI `manifest.json` next to `prefix/`.

## Integration

- **Consumers**: `scripts/prepare_android_runtime_assets.py` imports everything it needs from this package (`parse_packages_index`, `resolve_dependency_closure`, `load_data_tar_bytes_from_deb`, `open_data_tar`, `normalize_text_shebang`, `serializable_manifest`, `write_manifest`, `asset_prefix_dir`, `ROOT_PACKAGES`, `TERMUX_MAIN_BASE_URL`, and others). `scripts/prepare_android_runtime_native_libs.py` does not import it. It only reads the directory layout that this package defines.
- **Build pipeline**: Gradle runs the scripts that use this folder (`app/build.gradle.kts`, Exec tasks `prepareAndChamberRuntimeAssets` and `prepareAndChamberRuntimeNativeLibs`). `inputs.file` tracks both files in `runtime_tools/`, so Gradle reruns the task when they change.
- **CI cache**: `.github/workflows/ci.yml` hashes `runtime_tools/termux_assets.lock.json`, `runtime_tools/termux_assets.py` and `scripts/prepare_android_runtime_*.py` into the cache key for the generated output.
- **Lock refresh**: `python3 scripts/prepare_android_runtime_assets.py --refresh-lock-file --lock-only` rewrites `termux_assets.lock.json` from the live Termux index. Review the diff and commit it deliberately. Without `--lock-only` the refresh also extracts assets.
- **Lock format gap**: the current lock has no `package_archive` key, so `locked_package_archive` yields `None` and each package is fetched individually from mirrors, with per-package SHA-256 checks.
- **Known gap**: `termux_assets.py` is pure, but `check_termux_mirror_health` in the script depends on `scripts/android_visual_harness.py`, which is missing from this checkout.
