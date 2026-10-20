#!/usr/bin/env python3
"""Stage a patched OpenChamber browser-provider artifact.

The OpenChamber lane owns the source checkout, patch, and built files. This
script only checks a manifest that lane already produced. It does not invent a
hash and it does not download a package.

Expected lane manifest:

{
  "package": "@openchamber/web",
  "version": "2.4.0",
  "baseCommit": "6a10ee19a12a1542cad01eee5add14e030ba4ac4",
  "sourcePatchSha256": "<64 lowercase hex>",
  "server": [{"path": "packages/web/server/index.js", "sha256": "<64 lowercase hex>", "bytes": 123}],
  "dist": [{"path": "packages/web/dist/index.html", "sha256": "<64 lowercase hex>", "bytes": 123}]
}

Both server/ and dist/ entries are required. Output is a verified copy under
build/openchamber-browser-provider/. The APK should not embed this pair until
the real manifest and size budget are known.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


REQUIRED_COMMIT = "6a10ee19a12a1542cad01eee5add14e030ba4ac4"


def mapped(path: str) -> str | None:
    if path.startswith("packages/web/server/"):
        return "server/" + path.removeprefix("packages/web/server/")
    if path.startswith("packages/web/dist/"):
        return "dist/" + path.removeprefix("packages/web/dist/")
    return None


def reject(manifest: dict, source: Path) -> str | None:
    if manifest.get("package") != "@openchamber/web":
        return "unexpected package"
    if manifest.get("version") != "2.4.0":
        return "unexpected base version"
    if manifest.get("baseCommit") != REQUIRED_COMMIT:
        return "unexpected base commit"
    patch = manifest.get("sourcePatchSha256")
    if not isinstance(patch, str) or len(patch) != 64:
        return "unexpected source patch"
    files = list(manifest.get("server") or []) + list(manifest.get("dist") or [])
    if not manifest.get("server") or not manifest.get("dist"):
        return "artifact must include both server and dist"
    for item in files:
        path = item.get("path")
        digest = item.get("sha256")
        size = item.get("bytes")
        if not isinstance(path, str) or mapped(path) is None or ".." in Path(path).parts:
            return "artifact path is outside server/dist"
        if not isinstance(digest, str) or len(digest) != 64:
            return "artifact hash is not sha256"
        if not isinstance(size, int) or size < 0:
            return "artifact size is invalid"
        file = source / path
        if not file.is_file() or file.is_symlink():
            return "artifact file missing"
        if file.stat().st_size != size or sha256(file) != digest:
            return "artifact hash mismatch"
    return None


def prepare(source: Path, output: Path) -> None:
    manifest_path = source / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    reason = reject(manifest, source)
    if reason:
        raise SystemExit(reason)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    for item in list(manifest["server"]) + list(manifest["dist"]):
        target = output / mapped(item["path"])
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / item["path"], target)
        if sha256(target) != item["sha256"]:
            shutil.rmtree(output)
            raise SystemExit("staged copy hash changed")
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )
    asset = output / "apk-asset" / "openchamber-provider"
    if asset.exists():
        shutil.rmtree(asset)
    for item in list(manifest["server"]) + list(manifest["dist"]):
        target = asset / item["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output / mapped(item["path"]), target)
    (asset / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.source.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()
