#!/usr/bin/env python3
"""Provision the inputs of the x86_64 runtime probe (scripts/x86_probe, docs/testing-x86-runtime.md).

Network step (explicit, like prepare_android_runtime_sources.py). Produces, under --output-dir:

    alpine-x86_64.tar.gz   official Alpine x86_64 minirootfs, SHA-256 checked against runtime-manifest.json
    fork_regs              raw SYS_fork register check, built from the PRoot archive named in the lock
    sysprobe               static glibc probe: getppid, fork(), clone()
    vforkasm               static glibc probe: inline-asm vfork
    manifest.json          SHA-256 of every output and the source it came from

Nothing here is committed: outputs are build artefacts. `fork_regs.c` is read from the verified PRoot
archive at run time, so this repository does not carry a copy of upstream source.

Usage:
    python3 scripts/provision_x86_probe.py --output-dir build/x86-probe \
        [--sources-dir build/runtime-sources]   # prepared PRoot tree (prepare_android_runtime_sources.py)
        [--alpine-archive PATH]                 # optional pre-downloaded tarball (still SHA-checked)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
MANIFEST = REPO_ROOT / "app" / "src" / "main" / "assets" / "runtime-manifest.json"
PROBE_SOURCES = REPO_ROOT / "scripts" / "x86_probe"
CHUNK = 64 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_alpine(output: Path, local: Path | None) -> Path:
    spec = json.loads(MANIFEST.read_text(encoding="utf-8"))["architectures"]["x86_64"]
    target = output / "alpine-x86_64.tar.gz"
    if local is not None:
        shutil.copyfile(local, target)
    else:
        with urllib.request.urlopen(spec["alpineUrl"], timeout=600) as response, target.open("wb") as handle:
            shutil.copyfileobj(response, handle, CHUNK)
    actual = sha256(target)
    if actual != spec["alpineSha256"]:
        target.unlink()
        raise SystemExit(f"Alpine x86_64 SHA-256 mismatch: expected {spec['alpineSha256']}, got {actual}")
    return target


def build_static(source: Path, output: Path) -> Path:
    subprocess.run(
        ["gcc", "-O1", "-static", "-fno-stack-protector", "-o", str(output), str(source)],
        check=True,
    )
    return output


def build_fork_regs(sources_dir: Path, output: Path) -> Path:
    """Build the raw fork register check from the PRoot tree prepared from the lock (tests/fork_regs.c)."""
    source = sources_dir / "proot" / "proot-5.1.107.98" / "tests" / "fork_regs.c"
    if not source.is_file():
        raise SystemExit(f"fork_regs.c not found at {source}; run prepare_android_runtime_sources.py first")
    return build_static(source, output / "fork_regs")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--sources-dir", default=str(REPO_ROOT / "build" / "runtime-sources"))
    parser.add_argument("--alpine-archive")
    args = parser.parse_args(argv)

    output = Path(args.output_dir).expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    local = Path(args.alpine_archive).expanduser().resolve() if args.alpine_archive else None

    alpine = fetch_alpine(output, local)
    fork_regs = build_fork_regs(Path(args.sources_dir).expanduser().resolve(), output)
    sysprobe = build_static(PROBE_SOURCES / "sysprobe.c", output / "sysprobe")
    vforkasm = build_static(PROBE_SOURCES / "vforkasm.c", output / "vforkasm")

    manifest = {
        "alpine": {"file": alpine.name, "sha256": sha256(alpine)},
        "binaries": {
            path.name: {"sha256": sha256(path), "source": f"scripts/x86_probe/{path.name}.c"
                        if path.name in ("sysprobe", "vforkasm") else "proot-5.1.107.98/tests/fork_regs.c"}
            for path in (fork_regs, sysprobe, vforkasm)
        },
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
