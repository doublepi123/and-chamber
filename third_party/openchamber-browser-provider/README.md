# OpenChamber browser-provider source pin

This directory keeps the maintained inputs for the Android browser-provider lane:

- `source.json` pins `@openchamber/web` `2.4.0` at base commit
  `6a10ee19a12a1542cad01eee5add14e030ba4ac4`, the maintained patch filename,
  the patch SHA-256 and the observed build toolchain.
- `openchamber-android-provider.patch` is the complete, byte-for-byte patch.
  Its SHA-256 is `2499ecb38f4f59784928aa21a3ca21bb9478a9e82436c32fe6b02f1991d0d952`.
- `LICENSE` is the upstream MIT license text for OpenChamber.

`scripts/prepare_openchamber_browser_provider.py` reads this pin by default and
requires the input manifest's `sourcePatchSha256` to match it. It never fetches,
clones, patches or builds; it only stages a verified pair.

## Toolchain note

The lane observed Bun `1.4.2` locally. Upstream `package.json` pins Bun `1.4.3`
through `packageManager`/CI. The two are deliberately recorded separately in
`source.json`; the output is **not** claimed to be reproducible across Bun or
other toolchain versions. The staged file hashes are the evidence for this
particular build.

## Build and stage recipe

Run this from the AndChamber repository root. `WORK` is a generic temporary
build root; choose a fresh one instead of reusing an older run. The maintained
patch and pin are always addressed through `ANDCHAMBER_REPO`.

```sh
ANDCHAMBER_REPO=$(pwd)   # AndChamber repository root
PATCH="$ANDCHAMBER_REPO/third_party/openchamber-browser-provider/openchamber-android-provider.patch"
PIN="$ANDCHAMBER_REPO/third_party/openchamber-browser-provider/source.json"
WORK="${TMPDIR:-/tmp}/openchamber-provider-build"
test ! -e "$WORK" || { echo "WORK exists; choose a fresh temporary path"; exit 1; }
mkdir -p "$WORK"
SOURCE="$WORK/openchamber-v2.4.0"

# 1. Base checkout
git clone https://github.com/openchamber/openchamber.git "$SOURCE"
git -C "$SOURCE" checkout 6a10ee19a12a1542cad01eee5add14e030ba4ac4

# 2. Apply the maintained patch. --check first; the patch is not modified by
#    AndChamber and must apply cleanly to this base commit.
git -C "$SOURCE" apply --check "$PATCH"
git -C "$SOURCE" apply "$PATCH"

# 3. Official Bun build (run in the source checkout)
(
  cd "$SOURCE"
  bun --version            # 1.4.2 observed; upstream pin is 1.4.3
  bun install --frozen-lockfile
  bun run build:web
)
```

Generate the lane manifest from the built `packages/web/server` and
`packages/web/dist` trees. This is the whole generator; there is no separate
dependency system:

```sh
(
  cd "$SOURCE"
  python3 - <<'PY'
import hashlib, json
from pathlib import Path

root = Path(".")
files = {"server": [], "dist": []}
for kind, directory in (("server", "packages/web/server"), ("dist", "packages/web/dist")):
    for path in sorted(p for p in (root / directory).rglob("*") if p.is_file()):
        data = path.read_bytes()
        files[kind].append({
            "path": path.relative_to(root).as_posix(),
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        })

manifest = {
    "package": "@openchamber/web",
    "version": "2.4.0",
    "baseCommit": "6a10ee19a12a1542cad01eee5add14e030ba4ac4",
    "sourcePatch": "openchamber-android-provider.patch",
    "sourcePatchSha256": "2499ecb38f4f59784928aa21a3ca21bb9478a9e82436c32fe6b02f1991d0d952",
    "serverFileCount": len(files["server"]),
    "distFileCount": len(files["dist"]),
    "server": files["server"],
    "dist": files["dist"],
}
Path("manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
PY
)
```

`packages/web/dist` may be a symlink in a scratch checkout. `prepare` rejects
symlinked ancestors by default, so materialize a plain-file input first. Do not
reuse an older materialized input:

```sh
INPUT="$WORK/materialized-input"
test ! -e "$INPUT" || { echo "INPUT exists; choose a fresh WORK"; exit 1; }
mkdir -p "$INPUT/packages/web"
cp -a "$SOURCE/packages/web/server" "$INPUT/packages/web/server"
cp -aL "$SOURCE/packages/web/dist" "$INPUT/packages/web/dist"   # -L dereferences the scratch symlink
cp "$SOURCE/manifest.json" "$INPUT/manifest.json"
```

Then stage the APK payload with the maintained pin. The output is an absolute
path inside the AndChamber repository, not inside the upstream checkout:

```sh
python3 "$ANDCHAMBER_REPO/scripts/prepare_openchamber_browser_provider.py" \
  --source "$INPUT" \
  --source-pin "$PIN" \
  --output "$ANDCHAMBER_REPO/build/openchamber-browser-provider"
```

Output layout:

```text
"$ANDCHAMBER_REPO/build/openchamber-browser-provider"/
  manifest.json                         # Kotlin LaneManifest fields only
  build-provenance.json                 # counts, bytes, patch hash, toolchain note
  server/...                            # guest-mapped server tree
  dist/...                              # guest-mapped dist tree
  apk-asset/openchamber-provider/
    manifest.json
    packages/web/server/...
    packages/web/dist/...
```

Publication is a pair of renames: the existing output is renamed to a same-parent
backup, then the fully verified temporary tree is renamed to the output. It is
not a Linux `RENAME_EXCHANGE` transaction, so the output is briefly absent
between the two renames; this build-staging step assumes no concurrent reader of
that directory. If the second rename fails, the backup is restored. Validation
or copy failures happen before either rename, so an existing good output is left
untouched.

The debug APK consumes `<output>/apk-asset` only with the explicit opt-in:

```sh
(
  cd "$ANDCHAMBER_REPO"
  ./gradlew :app:assembleDebug \
    -Pandchamber.providerArtifactDir="$ANDCHAMBER_REPO/build/openchamber-browser-provider"
)
```

Release builds do not embed this payload. The runtime manifest deliberately
contains only `package`, `version`, `baseCommit`, `sourcePatchSha256`, `server`
and `dist`; build counts and toolchain notes stay in `build-provenance.json`
because the Kotlin parser uses `ignoreUnknownKeys = false`.

## License and provenance

OpenChamber is MIT-licensed; the upstream text is in `LICENSE`. The patch is a
modification of that source. Redistributors must keep the upstream copyright and
permission notice and verify their own obligations. This repository records the
exact patch bytes and staged file hashes, not a legal certification.
