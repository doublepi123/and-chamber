#!/usr/bin/env python3
"""Prepare pinned free-software source trees for the Android runtime native build.

This is the only network step of the source-build lane. It downloads the archives
listed in runtime_tools/runtime_sources.lock.json, verifies each SHA-256, extracts them
safely into stable component directories and checks the license file hash. The offline
build (build_android_runtime_native.py) consumes the result and never touches the network.

Layout produced under --output-dir:
    <output>/<component>/           extracted source tree (read-only by convention)
    <output>/downloads/<file>       verified archive
    <output>/sources.json           provenance: url, sha256, commit, license, extracted root
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import sys
import tarfile
import urllib.request
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = REPO_ROOT / "runtime_tools" / "runtime_sources.lock.json"
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
CHUNK_SIZE = 64 * 1024


class SourceIntegrityError(RuntimeError):
    """Raised when a source archive or license file fails verification."""


def load_lock(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("version") != 1:
        raise ValueError(f"unsupported runtime_sources lock version: {data.get('version')!r}")
    return data


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_sha256(path: Path, expected: str, label: str) -> None:
    actual = sha256_file(path)
    if actual != expected:
        raise SourceIntegrityError(f"{label}: SHA-256 mismatch (expected {expected}, got {actual})")


def is_safe_member(member: tarfile.TarInfo, root_dir: str) -> bool:
    """Accept only regular files, directories and symlinks that stay inside root_dir.

    Hardlinks and device/FIFO entries are rejected. Absolute paths and '..' components
    are rejected. Symlink targets must resolve inside the extracted root.
    """
    name = member.name
    if name.startswith("/") or ".." in Path(name).parts:
        return False
    if Path(name).parts[:1] != (root_dir,):
        return False
    if member.isdev() or member.islnk():
        return False
    if member.issym():
        target = Path(name).parent / member.linkname
        if member.linkname.startswith("/"):
            return False
        normalized: list[str] = []
        for part in target.parts:
            if part == "..":
                if not normalized:
                    return False
                normalized.pop()
            elif part not in ("", "."):
                normalized.append(part)
        return bool(normalized) and normalized[0] == root_dir
    return member.isfile() or member.isdir()


def safe_extract(archive: Path, destination: Path, root_dir: str) -> None:
    """Extract archive into destination after validating every member first."""
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for member in members:
            if not is_safe_member(member, root_dir):
                raise SourceIntegrityError(f"{archive.name}: unsafe archive member {member.name!r}")
        destination.mkdir(parents=True, exist_ok=True)
        for member in members:
            target = destination / member.name
            if not target.resolve().is_relative_to(destination.resolve()):
                raise SourceIntegrityError(f"{archive.name}: escapes destination: {member.name!r}")
        tar.extractall(destination, members=members, filter="data")
    for path in destination.rglob("*"):
        mode = path.lstat().st_mode
        if stat.S_ISREG(mode):
            path.chmod(0o644 | (mode & 0o100))


def download(url: str, destination: Path, expected_sha256: str, label: str) -> None:
    if destination.is_file():
        try:
            verify_sha256(destination, expected_sha256, label)
            return
        except SourceIntegrityError:
            destination.unlink()
    partial = destination.with_suffix(destination.suffix + ".part")
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as handle:
        while True:
            chunk = response.read(CHUNK_SIZE)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_ARCHIVE_BYTES:
                partial.unlink(missing_ok=True)
                raise SourceIntegrityError(f"{label}: archive exceeds {MAX_ARCHIVE_BYTES} bytes")
            digest.update(chunk)
            handle.write(chunk)
    if digest.hexdigest() != expected_sha256:
        partial.unlink(missing_ok=True)
        raise SourceIntegrityError(
            f"{label}: SHA-256 mismatch (expected {expected_sha256}, got {digest.hexdigest()})"
        )
    os.replace(partial, destination)


def prepare_component(name: str, spec: dict, output_dir: Path, local_archive_dir: Path | None) -> dict:
    archive_spec = spec["archive"]
    downloads = output_dir / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    archive_path = downloads / archive_spec["filename"]
    label = f"{name} {spec['version']}"
    if local_archive_dir is not None:
        local = local_archive_dir / archive_spec["filename"]
        if not local.is_file():
            raise FileNotFoundError(f"{label}: local archive not found: {local}")
        verify_sha256(local, archive_spec["sha256"], label)
        shutil.copyfile(local, archive_path)
    else:
        download(archive_spec["url"], archive_path, archive_spec["sha256"], label)
    verify_sha256(archive_path, archive_spec["sha256"], label)

    component_dir = output_dir / name
    if component_dir.exists():
        shutil.rmtree(component_dir)
    staging = output_dir / f".{name}.staging"
    if staging.exists():
        shutil.rmtree(staging)
    safe_extract(archive_path, staging, archive_spec["root_dir"])
    extracted = staging / archive_spec["root_dir"]
    if not extracted.is_dir():
        raise SourceIntegrityError(f"{label}: archive root {archive_spec['root_dir']!r} missing")
    license_spec = spec["license"]
    license_file = extracted / license_spec["file"]
    if not license_file.is_file():
        raise SourceIntegrityError(f"{label}: license file {license_spec['file']} missing")
    verify_sha256(license_file, license_spec["sha256"], f"{label} license")
    staging.rename(component_dir)
    return {
        "component": name,
        "version": spec["version"],
        "archive": archive_spec["filename"],
        "archive_url": archive_spec["url"],
        "archive_sha256": archive_spec["sha256"],
        "commit": spec.get("commit"),
        "source_root": str((component_dir / archive_spec["root_dir"]).relative_to(output_dir)),
        "license_spdx": license_spec["spdx"],
        "license_file": license_spec["file"],
        "license_sha256": license_spec["sha256"],
        "termux_recipe_commit": spec.get("termux_recipe_commit"),
        "termux_patches": spec.get("termux_patches", []),
    }


def prepare_sources(lock: dict, output_dir: Path, local_archive_dir: Path | None = None) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    records = []
    for name in sorted(lock["components"]):
        records.append(prepare_component(name, lock["components"][name], output_dir, local_archive_dir))
    provenance = {"lock_version": lock["version"], "ndk_version": lock["android"]["ndk_version"],
                  "min_api": lock["android"]["min_api"], "components": records}
    (output_dir / "sources.json").write_text(
        json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return provenance


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Download (network) and verify pinned runtime source archives. "
                    "Run explicitly before the offline native build.")
    parser.add_argument("--output-dir", required=True, help="e.g. <repo>/build/runtime-sources")
    parser.add_argument("--lock-file", default=str(DEFAULT_LOCK))
    parser.add_argument("--local-archives-dir",
                        help="Use already-downloaded archives from this directory instead of the network")
    args = parser.parse_args(argv)
    try:
        lock = load_lock(Path(args.lock_file))
        local = Path(args.local_archives_dir).expanduser().resolve() if args.local_archives_dir else None
        provenance = prepare_sources(lock, Path(args.output_dir).expanduser().resolve(), local)
    except (SourceIntegrityError, FileNotFoundError, ValueError, OSError) as error:
        print(f"prepare_android_runtime_sources: {error}", file=sys.stderr)
        return 1
    for record in provenance["components"]:
        print(f"{record['component']}: ok ({record['source_root']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
