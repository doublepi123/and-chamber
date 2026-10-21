#!/usr/bin/env python3
"""Stage a verified OpenChamber browser-provider payload for the APK assets.

The OpenChamber lane owns the source checkout, the maintained patch, the official
Bun build and the manifest that lists every built file. This script is the final
portable step: it reads the maintained source pin, validates the patch file and
every manifest pin, copies the paired ``server``/``dist`` trees as regular files,
re-verifies the staged copies, and publishes the output with a same-parent
backup/rename pair (not a Linux ``RENAME_EXCHANGE`` transaction).

Maintained pin (default ``third_party/openchamber-browser-provider/source.json``):

{
  "package": "@openchamber/web",
  "version": "2.4.0",
  "baseCommit": "6a10ee19a12a1542cad01eee5add14e030ba4ac4",
  "patch": {"file": "openchamber-android-provider.patch",
            "sha256": "<64 lowercase hex>"},
  "buildTools": {"bunUpstream": "1.4.3", "bunObserved": "1.4.2"}
}

Input lane manifest:

{
  "package": "@openchamber/web",
  "version": "2.4.0",
  "baseCommit": "6a10ee19a12a1542cad01eee5add14e030ba4ac4",
  "sourcePatchSha256": "<same value as the maintained pin>",
  "server": [{"path": "packages/web/server/index.js",
              "sha256": "<64 lowercase hex>", "bytes": 123}],
  "dist": [{"path": "packages/web/dist/index.html",
            "sha256": "<64 lowercase hex>", "bytes": 123}]
}

The output contains the guest pair at ``server/`` and ``dist/`` plus
``apk-asset/openchamber-provider/{manifest.json,packages/web/...}``. The runtime
manifest is a whitelist of the fields Kotlin's ``OpenChamberProviderArtifact``
parses with ``ignoreUnknownKeys = false``; build-only details live in the
separate ``build-provenance.json``.

This script never downloads, clones, applies the patch or runs Bun. It does not
claim that the Bun output is reproducible across toolchains.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import uuid
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE_PIN = REPO_ROOT / "third_party" / "openchamber-browser-provider" / "source.json"

PACKAGE = "@openchamber/web"
VERSION = "2.4.0"
BASE_COMMIT = "6a10ee19a12a1542cad01eee5add14e030ba4ac4"
SERVER_PREFIX = "packages/web/server/"
DIST_PREFIX = "packages/web/dist/"

HEX64 = re.compile(r"^[0-9a-f]{64}$")

# Kotlin's LaneManifest uses ignoreUnknownKeys = false. Keep the runtime
# manifest to exactly these fields; build provenance must stay out of it.
RUNTIME_MANIFEST_KEYS = ("package", "version", "baseCommit", "sourcePatchSha256", "server", "dist")
RUNTIME_FILE_KEYS = ("path", "sha256", "bytes")


class PreparationError(RuntimeError):
    """A clear, user-facing preparation failure."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_json(path: Path, label: str) -> dict:
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as error:
        raise PreparationError(f"{label} is not readable: {error}") from error
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise PreparationError(f"{label} is not valid JSON: {error}") from error
    if not isinstance(value, dict):
        raise PreparationError(f"{label} must be a JSON object")
    return value


def _require_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise PreparationError(f"{label} must be a non-empty string")
    return value


def _require_hex64(value: object, label: str) -> str:
    if not isinstance(value, str) or not HEX64.fullmatch(value):
        raise PreparationError(f"{label} must be 64 lowercase hex characters")
    return value


def _strict_relative(value: object, label: str) -> str:
    text = _require_string(value, label)
    if text.startswith("/") or "\\" in text or "\x00" in text:
        raise PreparationError(f"{label} must be a relative POSIX path")
    parts = text.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise PreparationError(f"{label} must not contain empty, '.' or '..' segments")
    return text


def _lane_path(path: object) -> tuple[str, str]:
    """Return (kind, guest-relative path) for a strict packages/web path."""
    text = _strict_relative(path, "artifact path")
    if text.startswith(SERVER_PREFIX):
        kind, relative = "server", text.removeprefix(SERVER_PREFIX)
    elif text.startswith(DIST_PREFIX):
        kind, relative = "dist", text.removeprefix(DIST_PREFIX)
    else:
        raise PreparationError("artifact path is outside packages/web/server or packages/web/dist")
    if not relative:
        raise PreparationError("artifact path names a directory, not a file")
    return kind, relative


def _guest_path(path: str) -> str:
    kind, relative = _lane_path(path)
    return f"{kind}/{relative}"


def _reject_ancestor_symlinks(path: Path, label: str) -> None:
    """Reject visible symlinks below an actual cwd/home/tmpfs boundary.

    The boundary itself may be an environment-level symlink (for example a home
    directory reached through one); the path components created for this input
    must be real. This deliberately does not treat a normal /tmp directory as a
    symlink.
    """
    target = Path(os.path.abspath(os.fspath(path)))
    boundary_path: Path | None = None
    for candidate in (Path.cwd(), Path.home(), Path("/tmp"), Path("/var/tmp")):
        boundary = Path(os.path.abspath(os.fspath(candidate)))
        if target == boundary or target.is_relative_to(boundary):
            if boundary_path is None or len(boundary.parts) > len(boundary_path.parts):
                boundary_path = boundary
    boundary = boundary_path or Path(target.anchor)
    if target == boundary:
        return
    current = boundary
    for part in target.relative_to(boundary).parts:
        current = current / part
        try:
            if current.is_symlink():
                raise PreparationError(f"{label} path uses a symlink: {part}")
        except OSError as error:
            raise PreparationError(f"{label} path is not readable: {error}") from error


def _check_input_root(source: Path) -> Path:
    if source.is_symlink():
        raise PreparationError("source root must not be a symlink")
    _reject_ancestor_symlinks(source, "source root")
    try:
        root = source.resolve(strict=True)
    except OSError as error:
        raise PreparationError(f"source root is not readable: {error}") from error
    if not root.is_dir():
        raise PreparationError("source root must be a directory")
    return root


def _source_file(source: Path, lane_path: str) -> Path:
    """Resolve one manifest path without following symlinks on any component."""
    _lane_path(lane_path)
    current = source
    try:
        root = source.resolve(strict=True)
    except OSError as error:
        raise PreparationError(f"source root is not readable: {error}") from error
    for part in lane_path.split("/"):
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError as error:
            raise PreparationError(f"artifact file missing: {lane_path}") from error
        except OSError as error:
            raise PreparationError(f"artifact path is not readable: {lane_path}: {error}") from error
        if stat.S_ISLNK(info.st_mode):
            raise PreparationError(f"artifact path uses a symlink: {lane_path}")
    try:
        resolved = current.resolve(strict=True)
    except OSError as error:
        raise PreparationError(f"artifact path is not readable: {lane_path}: {error}") from error
    if not resolved.is_relative_to(root):
        raise PreparationError(f"artifact path escapes the source root: {lane_path}")
    if not stat.S_ISREG(info.st_mode):
        raise PreparationError(f"artifact path is not a regular file: {lane_path}")
    return current


def _load_source_pin(pin_path: Path) -> dict:
    if pin_path.is_symlink():
        raise PreparationError("source pin must not be a symlink")
    _reject_ancestor_symlinks(pin_path, "source pin")
    try:
        pin_path = pin_path.resolve(strict=True)
    except OSError as error:
        raise PreparationError(f"source pin is not readable: {error}") from error
    pin = _load_json(pin_path, "source pin")

    package = _require_string(pin.get("package"), "source pin package")
    version = _require_string(pin.get("version"), "source pin version")
    commit = _require_string(pin.get("baseCommit"), "source pin baseCommit")
    if package != PACKAGE:
        raise PreparationError(f"source pin package must be {PACKAGE}")
    if version != VERSION:
        raise PreparationError(f"source pin version must be {VERSION}")
    if commit != BASE_COMMIT:
        raise PreparationError(f"source pin baseCommit must be {BASE_COMMIT}")

    patch = pin.get("patch")
    if not isinstance(patch, dict):
        raise PreparationError("source pin patch must be an object")
    patch_name = _strict_relative(patch.get("file"), "source pin patch.file")
    patch_sha = _require_hex64(patch.get("sha256"), "source pin patch.sha256")

    pin_dir = pin_path.parent
    patch_path = pin_dir / patch_name
    if patch_path.is_symlink():
        raise PreparationError("maintained patch must not be a symlink")
    _reject_ancestor_symlinks(patch_path, "maintained patch")
    try:
        patch_resolved = patch_path.resolve(strict=True)
    except OSError as error:
        raise PreparationError("maintained patch is not readable") from error
    if not patch_resolved.is_relative_to(pin_dir.resolve(strict=True)):
        raise PreparationError("source pin patch.file escapes the pin directory")
    if not patch_resolved.is_file():
        raise PreparationError("maintained patch must be a regular file")
    actual = _sha256(patch_path)
    if actual != patch_sha:
        raise PreparationError(
            f"maintained patch hash mismatch: pin {patch_sha}, file {actual}"
        )

    tools = pin.get("buildTools")
    if not isinstance(tools, dict):
        raise PreparationError("source pin buildTools must be an object")
    bun_upstream = _require_string(tools.get("bunUpstream"), "source pin buildTools.bunUpstream")
    bun_observed = _require_string(tools.get("bunObserved"), "source pin buildTools.bunObserved")
    base_tag = pin.get("baseTag")
    upstream = pin.get("upstream")
    if base_tag is not None:
        _require_string(base_tag, "source pin baseTag")
    if upstream is not None:
        _require_string(upstream, "source pin upstream")

    return {
        "path": pin_path,
        "package": package,
        "version": version,
        "baseCommit": commit,
        "baseTag": base_tag,
        "upstream": upstream,
        "patchFile": patch_name,
        "patchSha256": patch_sha,
        "patchPath": patch_path,
        "bunUpstream": bun_upstream,
        "bunObserved": bun_observed,
    }


def _validate_manifest(manifest: dict, pin: dict, source: Path) -> dict:
    package = _require_string(manifest.get("package"), "manifest package")
    version = _require_string(manifest.get("version"), "manifest version")
    commit = _require_string(manifest.get("baseCommit"), "manifest baseCommit")
    if package != pin["package"]:
        raise PreparationError("manifest package does not match the maintained pin")
    if version != pin["version"]:
        raise PreparationError("manifest version does not match the maintained pin")
    if commit != pin["baseCommit"]:
        raise PreparationError("manifest baseCommit does not match the maintained pin")

    patch_sha = _require_hex64(manifest.get("sourcePatchSha256"), "manifest sourcePatchSha256")
    if patch_sha != pin["patchSha256"]:
        raise PreparationError(
            "manifest sourcePatchSha256 does not match the maintained patch pin"
        )
    if "sourcePatch" in manifest:
        source_patch = _require_string(manifest.get("sourcePatch"), "manifest sourcePatch")
        if source_patch != Path(pin["patchFile"]).name:
            raise PreparationError("manifest sourcePatch does not name the maintained patch")

    server = manifest.get("server")
    dist = manifest.get("dist")
    if not isinstance(server, list) or not server:
        raise PreparationError("manifest server must be a non-empty list")
    if not isinstance(dist, list) or not dist:
        raise PreparationError("manifest dist must be a non-empty list")

    for label, expected in (("serverFileCount", len(server)), ("distFileCount", len(dist))):
        if label in manifest:
            value = manifest[label]
            if isinstance(value, bool) or not isinstance(value, int) or value != expected:
                raise PreparationError(f"manifest {label} must be {expected}")

    for label in ("bunObserved", "bunPinned"):
        if label in manifest:
            value = _require_string(manifest[label], f"manifest {label}")
            expected = pin["bunObserved"] if label == "bunObserved" else pin["bunUpstream"]
            if value != expected:
                raise PreparationError(f"manifest {label} does not match the maintained pin")

    seen: set[str] = set()
    pending: list[tuple[str, str, str, int]] = []
    for kind, entries in (("server", server), ("dist", dist)):
        for index, item in enumerate(entries):
            if not isinstance(item, dict):
                raise PreparationError(f"manifest {kind}[{index}] must be an object")
            path = _require_string(item.get("path"), f"manifest {kind}[{index}].path")
            actual_kind, _ = _lane_path(path)
            if actual_kind != kind:
                raise PreparationError(
                    f"manifest {kind}[{index}] uses a {actual_kind} path: {path}"
                )
            if path in seen:
                raise PreparationError(f"duplicate artifact path: {path}")
            seen.add(path)
            digest = _require_hex64(item.get("sha256"), f"manifest {kind}[{index}].sha256")
            size = item.get("bytes")
            if isinstance(size, bool) or not isinstance(size, int) or size < 0:
                raise PreparationError(f"manifest {kind}[{index}].bytes must be a non-negative integer")
            pending.append((kind, path, digest, size))

    # A path cannot be both a file and a directory. The filesystem cannot
    # represent the pair at once, so reject the manifest explicitly instead of
    # relying on an incidental "missing file" error.
    for _, path, _, _ in pending:
        parts = path.split("/")
        for index in range(1, len(parts)):
            ancestor = "/".join(parts[:index])
            if ancestor in seen:
                raise PreparationError(f"artifact path is both a file and a directory: {path}")

    validated: dict[str, list[dict]] = {"server": [], "dist": []}
    for kind, path, digest, size in pending:
        checked = _source_file(source, path)
        actual_size = checked.stat(follow_symlinks=False).st_size
        if actual_size != size:
            raise PreparationError(f"artifact byte size mismatch: {path}")
        if _sha256(checked) != digest:
            raise PreparationError(f"artifact hash mismatch: {path}")
        validated[kind].append({"path": path, "sha256": digest, "bytes": size})

    return {
        "server": sorted(validated["server"], key=lambda item: item["path"]),
        "dist": sorted(validated["dist"], key=lambda item: item["path"]),
    }


def _check_output(source: Path, output: Path, pin: dict) -> Path:
    if output.is_symlink():
        raise PreparationError("output must not be a symlink")
    _reject_ancestor_symlinks(output, "output")
    try:
        resolved = output.resolve(strict=False)
    except OSError as error:
        raise PreparationError(f"output path is not resolvable: {error}") from error
    repo = REPO_ROOT.resolve(strict=True)
    if resolved == repo or repo.is_relative_to(resolved):
        raise PreparationError("output must not be the repository or an ancestor of it")
    if resolved == Path("/") or resolved == Path.home().resolve():
        raise PreparationError("output must not be a home or filesystem root")
    managed = [
        pin["path"].parent.resolve(strict=True),
        pin["path"].resolve(strict=True),
        pin["patchPath"].resolve(strict=True),
    ]
    for managed_path in managed:
        if (
            resolved == managed_path
            or resolved.is_relative_to(managed_path)
            or managed_path.is_relative_to(resolved)
        ):
            raise PreparationError("output must not overlap the maintained source pin or patch")
    if resolved == source or resolved.is_relative_to(source) or source.is_relative_to(resolved):
        raise PreparationError("output and source must not overlap")
    if output.exists() and not output.is_dir():
        raise PreparationError("output exists and is not a directory")
    return resolved


def _copy_verified(source: Path, target: Path, digest: str, size: int) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    hasher = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(source, flags)
    try:
        with os.fdopen(descriptor, "rb") as reader, target.open("xb") as writer:
            for chunk in iter(lambda: reader.read(1024 * 1024), b""):
                total += len(chunk)
                hasher.update(chunk)
                writer.write(chunk)
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    if total != size:
        target.unlink(missing_ok=True)
        raise PreparationError(f"staged byte size changed: {target.name}")
    if hasher.hexdigest() != digest:
        target.unlink(missing_ok=True)
        raise PreparationError(f"staged hash changed: {target.name}")


def _write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def _runtime_manifest(pin: dict, entries: dict) -> dict:
    return {
        "package": pin["package"],
        "version": pin["version"],
        "baseCommit": pin["baseCommit"],
        "sourcePatchSha256": pin["patchSha256"],
        "server": entries["server"],
        "dist": entries["dist"],
    }


def _provenance(pin: dict, entries: dict) -> dict:
    server_bytes = sum(item["bytes"] for item in entries["server"])
    dist_bytes = sum(item["bytes"] for item in entries["dist"])
    try:
        pin_display = pin["path"].relative_to(REPO_ROOT).as_posix()
    except ValueError:
        pin_display = pin["path"].name
    return {
        "schemaVersion": 1,
        "package": pin["package"],
        "version": pin["version"],
        "baseCommit": pin["baseCommit"],
        "baseTag": pin["baseTag"],
        "sourcePin": pin_display,
        "sourcePatch": {"file": pin["patchFile"], "sha256": pin["patchSha256"]},
        "buildTools": {
            "bunUpstream": pin["bunUpstream"],
            "bunObserved": pin["bunObserved"],
        },
        "counts": {
            "server": len(entries["server"]),
            "dist": len(entries["dist"]),
            "files": len(entries["server"]) + len(entries["dist"]),
        },
        "bytes": {
            "server": server_bytes,
            "dist": dist_bytes,
            "total": server_bytes + dist_bytes,
        },
        "verification": {
            "manifestEntries": len(entries["server"]) + len(entries["dist"]),
            "stagedFilesVerified": "sha256 and byte size matched for server, dist and APK asset copies",
            "pathChecks": "every file stayed under its declared server/dist root; no symlink was followed",
        },
        "reproducibility": (
            "not claimed: Bun output can differ across toolchains; this file records what was "
            "staged, not a reproducible-build guarantee"
        ),
        "materializedInput": (
            "the verified source tree was copied to plain regular files before preparation; "
            "symlinked dist ancestors are rejected by default"
        ),
    }


def _expect_within(root: Path, path: Path) -> None:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise PreparationError(f"staged path is not readable: {path.name}: {error}") from error
    if not resolved.is_relative_to(root):
        raise PreparationError("staged path escaped the temporary output")


def _verify_staged_output(root: Path, entries: dict, pin: dict) -> None:
    expected = {"manifest.json", "build-provenance.json", "apk-asset/openchamber-provider/manifest.json"}
    for kind in ("server", "dist"):
        for item in entries[kind]:
            _lane_path(item["path"])
            guest = _guest_path(item["path"])
            expected.add(guest)
            expected.add(f"apk-asset/openchamber-provider/{item['path']}")
            for target in (root / guest, root / "apk-asset" / "openchamber-provider" / item["path"]):
                _expect_within(root, target)
                info = target.lstat()
                if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
                    raise PreparationError("staged output must contain only regular files")
                if info.st_size != item["bytes"] or _sha256(target) != item["sha256"]:
                    raise PreparationError(f"staged output hash mismatch: {item['path']}")

    found: set[str] = set()
    for directory, directories, files in os.walk(root, followlinks=False):
        base = Path(directory)
        for name in directories:
            candidate = base / name
            if candidate.is_symlink():
                raise PreparationError("staged output must not contain symlinked directories")
        for name in files:
            candidate = base / name
            if candidate.is_symlink():
                raise PreparationError("staged output must not contain symlink files")
            relative = candidate.relative_to(root).as_posix()
            if relative not in expected:
                raise PreparationError(f"unexpected staged file: {relative}")
            found.add(relative)
    if found != expected:
        missing = sorted(expected - found)
        raise PreparationError(f"staged output is missing files: {missing[0]}")

    runtime = _runtime_manifest(pin, entries)
    for manifest_path in (root / "manifest.json", root / "apk-asset/openchamber-provider/manifest.json"):
        loaded = _load_json(manifest_path, "runtime manifest")
        if loaded != runtime:
            raise PreparationError("runtime manifest does not match the validated pins")
        if tuple(loaded) != RUNTIME_MANIFEST_KEYS:
            raise PreparationError("runtime manifest contains fields the Kotlin schema does not accept")
        for kind in ("server", "dist"):
            for item in loaded[kind]:
                if tuple(item) != RUNTIME_FILE_KEYS:
                    raise PreparationError("runtime manifest file entries are not whitelisted")


def _publish(temp: Path, output: Path) -> None:
    """Rename old output aside, publish temp, then remove the backup.

    This is two renames, not an atomic exchange; the output is briefly absent
    between them. A failed second rename restores the backup. Callers must not
    read the staging output concurrently.
    """
    backup: Path | None = None
    if output.is_symlink() or (output.exists() and not output.is_dir()):
        raise PreparationError("output changed into a symlink or non-directory before publish")
    if output.exists():
        backup = output.parent / f".{output.name}.old-{uuid.uuid4().hex}"
        os.replace(output, backup)
    try:
        os.replace(temp, output)
    except BaseException:
        if backup is not None:
            os.replace(backup, output)
        raise
    if backup is not None:
        shutil.rmtree(backup, ignore_errors=False)


def prepare(source: Path, output: Path, source_pin: Path = DEFAULT_SOURCE_PIN) -> dict:
    pin = _load_source_pin(source_pin)
    source_root = _check_input_root(source)
    manifest_path = source_root / "manifest.json"
    if manifest_path.is_symlink():
        raise PreparationError("input manifest must not be a symlink")
    manifest = _load_json(manifest_path, "input manifest")
    entries = _validate_manifest(manifest, pin, source_root)
    output = _check_output(source_root, output, pin)

    output.parent.mkdir(parents=True, exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix=f".{output.name}.prepare-", dir=output.parent))
    published = False
    try:
        try:
            for kind in ("server", "dist"):
                for item in entries[kind]:
                    guest = temp / _guest_path(item["path"])
                    _copy_verified(
                        _source_file(source_root, item["path"]),
                        guest,
                        item["sha256"],
                        item["bytes"],
                    )
                    _copy_verified(
                        guest,
                        temp / "apk-asset" / "openchamber-provider" / item["path"],
                        item["sha256"],
                        item["bytes"],
                    )
        except PreparationError:
            raise
        except OSError as error:
            raise PreparationError(f"staging failed: {error}") from error

        runtime = _runtime_manifest(pin, entries)
        _write_json(temp / "manifest.json", runtime)
        _write_json(temp / "apk-asset" / "openchamber-provider" / "manifest.json", runtime)
        _write_json(temp / "build-provenance.json", _provenance(pin, entries))
        _verify_staged_output(temp, entries, pin)
        _publish(temp, output)
        published = True
    finally:
        if not published and temp.exists():
            shutil.rmtree(temp, ignore_errors=True)

    return {
        "server": len(entries["server"]),
        "dist": len(entries["dist"]),
        "bytes": sum(item["bytes"] for item in entries["server"] + entries["dist"]),
        "patchSha256": pin["patchSha256"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--source-pin",
        type=Path,
        default=DEFAULT_SOURCE_PIN,
        help="maintained source pin (default: third_party/openchamber-browser-provider/source.json)",
    )
    args = parser.parse_args(argv)
    try:
        result = prepare(args.source, args.output, args.source_pin)
    except PreparationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(
        f"staged {result['server']} server and {result['dist']} dist files "
        f"({result['bytes']} bytes); patch sha256 {result['patchSha256']}; output {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
