# Native runtime license texts and provenance

This directory is the single source of truth for the license texts of the native components
compiled into the AndChamber APK: PRoot, talloc and libandroid-shmem. Gradle copies it, byte for
byte, into the APK assets under `licenses/native/` (task `syncNativeLicenseAssets` in
`app/build.gradle.kts`). Do not add hand-maintained copies under `app/src/main/assets`.

## Files

| file | component | SPDX | source |
|---|---|---|---|
| `proot/COPYING` | PRoot 5.1.107.97 | GPL-2.0-or-later | `COPYING` of the pinned archive |
| `talloc/LICENSE` | talloc 2.5.0 | LGPL-3.0-or-later | `LICENSE` of the pinned archive |
| `talloc/GPL-3.0.txt` | talloc (referenced by LGPL-3.0) | GPL-3.0-only text | https://www.gnu.org/licenses/gpl-3.0.txt |
| `libandroid-shmem/LICENSE` | libandroid-shmem 0.7 | BSD-3-Clause | `LICENSE` of the pinned archive |
| `provenance.json` | all three | — | versions, upstream URLs, pinned commits, archive SHA-256, local patches, build steps |

The three component `COPYING`/`LICENSE` files are byte-identical to their upstream archive copies.
The companion GPL-3.0 text is copied unchanged from the canonical GNU URL listed above. All four
license-file hashes are recorded in `provenance.json` and checked by
`scripts/tests/test_native_license_assets.py`. Upstream copyright notices and author names are
unchanged. The BSD text keeps the literal `{organization}` placeholder from upstream.

## Source offer

Corresponding source for each component is the archive listed in `provenance.json`, pinned by SHA-256
in `runtime_tools/runtime_sources.lock.json`, together with the local patches under
`runtime_tools/patches/` and the build script `scripts/build_android_runtime_native.py`. The sources
are fetched explicitly with `scripts/prepare_android_runtime_sources.py`; the APK build itself does
not use the network for them.

This is a technical provenance record, not a legal certification. Redistributors must confirm their
own license obligations.
