"""Offline tests for the portable OpenChamber browser-provider payload stage.

Every test builds a real temporary source tree and never touches the network.
The Kotlin schema test reads the actual parser source so the staged manifest
cannot silently drift away from ``ignoreUnknownKeys = false``.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "prepare_openchamber_browser_provider.py"
SOURCE_PIN = ROOT / "third_party" / "openchamber-browser-provider" / "source.json"
KOTLIN = ROOT / "app" / "src" / "main" / "java" / "io" / "andchamber" / "app" / "runtime" / "install" / "OpenChamberProviderArtifact.kt"

sys.path.insert(0, str(ROOT / "scripts"))
import prepare_openchamber_browser_provider as prep  # noqa: E402

PACKAGE = "@openchamber/web"
VERSION = "2.4.0"
COMMIT = "6a10ee19a12a1542cad01eee5add14e030ba4ac4"
PATCH_SHA = "2499ecb38f4f59784928aa21a3ca21bb9478a9e82436c32fe6b02f1991d0d952"
SERVER_PATH = "packages/web/server/index.js"
DIST_PATH = "packages/web/dist/index.html"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_file(root: Path, relative: str, data: bytes) -> dict:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"path": relative, "sha256": sha(data), "bytes": len(data)}


def make_pin(tmp: Path, patch: bytes = b"maintained patch\n", patch_sha: str | None = None) -> tuple[Path, dict]:
    pin_dir = tmp / "pin"
    pin_dir.mkdir(parents=True)
    patch_path = pin_dir / "provider.patch"
    patch_path.write_bytes(patch)
    pin = {
        "package": PACKAGE,
        "version": VERSION,
        "baseTag": "v2.4.0",
        "baseCommit": COMMIT,
        "upstream": "https://example.com/openchamber",
        "patch": {"file": "provider.patch", "sha256": patch_sha or sha(patch)},
        "buildTools": {"bunUpstream": "1.4.3", "bunObserved": "1.4.2"},
    }
    pin_path = pin_dir / "source.json"
    pin_path.write_text(json.dumps(pin), encoding="utf-8")
    return pin_path, pin


def make_source(
    tmp: Path,
    pin: dict,
    server: list[dict] | None = None,
    dist: list[dict] | None = None,
    extra: dict | None = None,
) -> tuple[Path, dict]:
    source = tmp / "source"
    source.mkdir()
    server = server if server is not None else [write_file(source, SERVER_PATH, b"server")]
    dist = dist if dist is not None else [write_file(source, DIST_PATH, b"dist")]
    manifest = {
        "package": PACKAGE,
        "version": VERSION,
        "baseCommit": COMMIT,
        "sourcePatch": Path(pin["patch"]["file"]).name,
        "sourcePatchSha256": pin["patch"]["sha256"],
        "serverFileCount": len(server),
        "distFileCount": len(dist),
        "server": server,
        "dist": dist,
    }
    if extra:
        manifest.update(extra)
    (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return source, manifest


def write_manifest(source: Path, manifest: dict) -> None:
    (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def run_cli(source: Path, output: Path, pin: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--source",
            str(source),
            "--output",
            str(output),
            "--source-pin",
            str(pin),
        ],
        check=False,
        text=True,
        capture_output=True,
    )


def kotlin_fields(name: str) -> tuple[str, ...]:
    text = KOTLIN.read_text(encoding="utf-8")
    match = re.search(rf"data class {name}\((.*?)\n    \)", text, re.S)
    if not match:
        raise AssertionError(f"could not find Kotlin data class {name}")
    fields = []
    for field in re.finditer(r'(?:@SerialName\("([^"]+)"\)\s*)?val (\w+)', match.group(1)):
        fields.append(field.group(1) or field.group(2))
    return tuple(fields)


class PrepareOpenChamberBrowserProviderTest(unittest.TestCase):
    def assert_no_temp(self, output: Path) -> None:
        self.assertEqual([], list(output.parent.glob(f".{output.name}.prepare-*")))

    def test_verified_pair_is_copied_as_pair_and_asset(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(b"server", (output / "server/index.js").read_bytes())
            self.assertEqual(b"dist", (output / "dist/index.html").read_bytes())
            asset = output / "apk-asset" / "openchamber-provider"
            self.assertEqual(b"server", (asset / SERVER_PATH).read_bytes())
            self.assertEqual(b"dist", (asset / DIST_PATH).read_bytes())
            self.assertEqual(
                (output / "manifest.json").read_bytes(),
                (asset / "manifest.json").read_bytes(),
            )
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(prep.RUNTIME_MANIFEST_KEYS, tuple(manifest))
            provenance = json.loads((output / "build-provenance.json").read_text(encoding="utf-8"))
            self.assertEqual({"server": 1, "dist": 1, "files": 2}, provenance["counts"])
            self.assertEqual(len(b"server") + len(b"dist"), provenance["bytes"]["total"])
            self.assertEqual(pin["patch"]["sha256"], provenance["sourcePatch"]["sha256"])
            self.assert_no_temp(output)

    def test_runtime_manifest_matches_the_actual_kotlin_schema(self):
        self.assertEqual(prep.RUNTIME_MANIFEST_KEYS, kotlin_fields("LaneManifest"))
        self.assertEqual(prep.RUNTIME_FILE_KEYS, kotlin_fields("LaneFile"))
        self.assertEqual(
            PATCH_SHA,
            re.search(r'REQUIRED_PATCH_SHA256 = "([0-9a-f]{64})"', KOTLIN.read_text(encoding="utf-8")).group(1),
        )
        self.assertEqual(
            COMMIT,
            re.search(r'REQUIRED_COMMIT = "([0-9a-f]{40})"', KOTLIN.read_text(encoding="utf-8")).group(1),
        )

    def test_repository_pin_matches_the_maintained_patch_bytes(self):
        pin = json.loads(SOURCE_PIN.read_text(encoding="utf-8"))
        self.assertEqual(PACKAGE, pin["package"])
        self.assertEqual(VERSION, pin["version"])
        self.assertEqual(COMMIT, pin["baseCommit"])
        self.assertEqual(PATCH_SHA, pin["patch"]["sha256"])
        patch = SOURCE_PIN.parent / pin["patch"]["file"]
        self.assertEqual(PATCH_SHA, sha(patch.read_bytes()))
        self.assertEqual("1.4.2", pin["buildTools"]["bunObserved"])
        self.assertEqual("1.4.3", pin["buildTools"]["bunUpstream"])

    def test_wrong_patch_pin_rejected_before_any_copy(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp, patch_sha="0" * 64)
            source, _ = make_source(tmp, pin)
            output = tmp / "output"
            outside = tmp / "outside.txt"
            outside.write_bytes(b"keep")

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("patch hash mismatch", completed.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(b"keep", outside.read_bytes())
            self.assert_no_temp(output)

    def test_patch_file_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            (pin_path.parent / "provider.patch").write_bytes(b"tampered")
            source, _ = make_source(tmp, pin)
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("maintained patch hash mismatch", completed.stderr)
            self.assertFalse(output.exists())

    def test_manifest_patch_pin_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, manifest = make_source(tmp, pin)
            manifest["sourcePatchSha256"] = "1" * 64
            write_manifest(source, manifest)
            output = tmp / "output"
            output.mkdir()
            (output / "keep.txt").write_bytes(b"old")

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("does not match the maintained patch pin", completed.stderr)
            self.assertEqual(b"old", (output / "keep.txt").read_bytes())

    def test_manifest_path_forms_are_rejected(self):
        bad_paths = [
            "/tmp/absolute.js",
            "packages/web/server/../secret.js",
            "packages/web/server/./index.js",
            "packages/web/server//index.js",
            "packages\\\\web\\\\server\\\\index.js",
            "packages/web/dist/\x00evil.js",
            "packages/web/server/",
            "packages/web/other/index.js",
            "server/index.js",
        ]
        for bad in bad_paths:
            with self.subTest(path=bad), tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                pin_path, pin = make_pin(tmp)
                source, manifest = make_source(tmp, pin)
                manifest["server"][0]["path"] = bad
                write_manifest(source, manifest)
                output = tmp / "output"

                completed = run_cli(source, output, pin_path)

                self.assertNotEqual(0, completed.returncode)
                self.assertFalse(output.exists())

    def test_duplicate_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, manifest = make_source(tmp, pin)
            manifest["server"].append(dict(manifest["server"][0]))
            manifest["serverFileCount"] = 2
            write_manifest(source, manifest)
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("duplicate artifact path", completed.stderr)
            self.assertFalse(output.exists())

    def test_byte_fields_reject_negative_bool_and_non_integers(self):
        for bad in (-1, True, False, "1", 1.5, None):
            with self.subTest(bytes=bad), tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                pin_path, pin = make_pin(tmp)
                source, manifest = make_source(tmp, pin)
                manifest["server"][0]["bytes"] = bad
                write_manifest(source, manifest)
                output = tmp / "output"

                completed = run_cli(source, output, pin_path)

                self.assertNotEqual(0, completed.returncode)
                self.assertIn("non-negative integer", completed.stderr)
                self.assertFalse(output.exists())

    def test_hash_fields_require_lowercase_sha256(self):
        for bad in (PATCH_SHA.upper(), PATCH_SHA[:-1], "g" * 64, 1, None):
            with self.subTest(hash=bad), tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                pin_path, pin = make_pin(tmp)
                source, manifest = make_source(tmp, pin)
                manifest["server"][0]["sha256"] = bad
                write_manifest(source, manifest)
                output = tmp / "output"

                completed = run_cli(source, output, pin_path)

                self.assertNotEqual(0, completed.returncode)
                self.assertFalse(output.exists())

    def test_empty_server_or_dist_is_rejected(self):
        for key in ("server", "dist"):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                pin_path, pin = make_pin(tmp)
                source, manifest = make_source(tmp, pin)
                manifest[key] = []
                manifest[f"{key}FileCount"] = 0
                write_manifest(source, manifest)
                output = tmp / "output"

                completed = run_cli(source, output, pin_path)

                self.assertNotEqual(0, completed.returncode)
                self.assertIn("non-empty list", completed.stderr)
                self.assertFalse(output.exists())

    def test_symlinked_file_and_ancestor_cannot_escape(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            outside = tmp / "outside"
            outside.mkdir()
            secret = write_file(outside, "secret.txt", b"secret")

            source, manifest = make_source(tmp, pin)
            (source / DIST_PATH).unlink()
            (source / DIST_PATH).symlink_to(outside / "secret.txt")
            manifest["dist"][0]["sha256"] = secret["sha256"]
            manifest["dist"][0]["bytes"] = secret["bytes"]
            write_manifest(source, manifest)
            output = tmp / "output"
            output.mkdir()
            (output / "keep.txt").write_bytes(b"old")

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("uses a symlink", completed.stderr)
            self.assertEqual(b"secret", (outside / "secret.txt").read_bytes())
            self.assertEqual(b"old", (output / "keep.txt").read_bytes())

        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            outside = tmp / "outside"
            secret = write_file(outside, "secret.txt", b"secret")
            source = tmp / "source"
            source.mkdir()
            server = write_file(source, SERVER_PATH, b"server")
            (source / "packages/web").mkdir(parents=True, exist_ok=True)
            (source / "packages/web/dist").symlink_to(outside, target_is_directory=True)
            manifest = {
                "package": PACKAGE,
                "version": VERSION,
                "baseCommit": COMMIT,
                "sourcePatchSha256": pin["patch"]["sha256"],
                "server": [server],
                "dist": [
                    {
                        "path": "packages/web/dist/secret.txt",
                        "sha256": secret["sha256"],
                        "bytes": secret["bytes"],
                    }
                ],
            }
            write_manifest(source, manifest)
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("uses a symlink", completed.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(b"secret", (outside / "secret.txt").read_bytes())

    def test_symlinked_source_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            link = tmp / "linked-source"
            link.symlink_to(source, target_is_directory=True)
            output = tmp / "output"

            completed = run_cli(link, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("source root must not be a symlink", completed.stderr)
            self.assertFalse(output.exists())

    def test_cross_group_paths_are_rejected_before_copy(self):
        for direction in ("server-holds-dist", "dist-holds-server"):
            with self.subTest(direction=direction), tempfile.TemporaryDirectory() as raw:
                tmp = Path(raw)
                pin_path, pin = make_pin(tmp)
                source, manifest = make_source(tmp, pin)
                server_item = manifest["server"][0]
                dist_item = manifest["dist"][0]
                if direction == "server-holds-dist":
                    manifest["server"] = [dist_item]
                    manifest["dist"] = [write_file(source, "packages/web/dist/other.html", b"other")]
                else:
                    manifest["server"] = [write_file(source, "packages/web/server/other.js", b"other")]
                    manifest["dist"] = [server_item]
                write_manifest(source, manifest)
                output = tmp / "output"
                output.mkdir()
                (output / "keep.txt").write_bytes(b"old")

                completed = run_cli(source, output, pin_path)

                self.assertNotEqual(0, completed.returncode)
                self.assertIn("uses a", completed.stderr)
                self.assertIn("path", completed.stderr)
                self.assertEqual(b"old", (output / "keep.txt").read_bytes())
                self.assert_no_temp(output)

    def test_file_and_directory_ancestor_conflict_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, manifest = make_source(tmp, pin)
            parent = write_file(source, "packages/web/server/foo", b"foo")
            child = {"path": "packages/web/server/foo/bar", "sha256": sha(b"bar"), "bytes": 3}
            manifest["server"] = [parent, child]
            manifest["serverFileCount"] = 2
            write_manifest(source, manifest)
            output = tmp / "output"
            output.mkdir()
            (output / "keep.txt").write_bytes(b"old")

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("both a file and a directory", completed.stderr)
            self.assertEqual(b"old", (output / "keep.txt").read_bytes())
            self.assert_no_temp(output)

    def test_output_cannot_overlap_the_maintained_pin_or_patch(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            pin_before = pin_path.read_bytes()
            patch_before = (pin_path.parent / "provider.patch").read_bytes()
            for output in (tmp / "pin", tmp / "pin" / "nested", tmp):
                with self.subTest(output=output):
                    completed = run_cli(source, output, pin_path)
                    self.assertNotEqual(0, completed.returncode)
                    self.assertIn("maintained source pin", completed.stderr)
                    self.assertEqual(pin_before, pin_path.read_bytes())
                    self.assertEqual(patch_before, (pin_path.parent / "provider.patch").read_bytes())

    def test_output_ancestor_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            outside = tmp / "outside"
            old = outside / "old-output"
            old.mkdir(parents=True)
            (old / "keep.txt").write_bytes(b"old")
            link = tmp / "link"
            link.symlink_to(outside, target_is_directory=True)
            output = link / "old-output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("output path uses a symlink", completed.stderr)
            self.assertEqual(b"old", (old / "keep.txt").read_bytes())
            self.assertEqual([], list(outside.glob(".old-output.prepare-*")))

    def test_source_pin_ancestor_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            real = tmp / "real"
            real.mkdir()
            (tmp / "pin").rename(real / "pin")
            link = tmp / "pin-link"
            link.symlink_to(real / "pin", target_is_directory=True)
            linked_pin = link / "source.json"
            pin_before = (real / "pin" / "source.json").read_bytes()
            patch_before = (real / "pin" / "provider.patch").read_bytes()
            source, _ = make_source(tmp, pin)
            output = tmp / "output"

            completed = run_cli(source, output, linked_pin)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("source pin path uses a symlink", completed.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(pin_before, (real / "pin" / "source.json").read_bytes())
            self.assertEqual(patch_before, (real / "pin" / "provider.patch").read_bytes())

    def test_symlinked_output_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            target = tmp / "target"
            target.mkdir()
            (target / "keep.txt").write_bytes(b"old")
            output = tmp / "output-link"
            output.symlink_to(target, target_is_directory=True)

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("output must not be a symlink", completed.stderr)
            self.assertEqual(b"old", (target / "keep.txt").read_bytes())
            self.assert_no_temp(output)

    def test_copy_failure_keeps_existing_output_and_cleans_only_temp(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            output = tmp / "output"
            output.mkdir()
            (output / "keep.txt").write_bytes(b"old")
            real = prep._copy_verified
            calls = 0

            def fail_second(src, target, digest, size):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("disk full")
                return real(src, target, digest, size)

            with mock.patch.object(prep, "_copy_verified", side_effect=fail_second):
                with self.assertRaises(prep.PreparationError):
                    prep.prepare(source, output, pin_path)

            self.assertEqual(b"old", (output / "keep.txt").read_bytes())
            self.assert_no_temp(output)

    def test_source_change_after_validation_fails_without_replacing_output(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            output = tmp / "output"
            output.mkdir()
            (output / "keep.txt").write_bytes(b"old")
            real = prep._copy_verified
            changed = False

            def change_then_copy(src, target, digest, size):
                nonlocal changed
                if not changed and src == source / SERVER_PATH:
                    changed = True
                    src.write_bytes(b"changed")
                return real(src, target, digest, size)

            with mock.patch.object(prep, "_copy_verified", side_effect=change_then_copy):
                with self.assertRaises(prep.PreparationError):
                    prep.prepare(source, output, pin_path)

            self.assertEqual(b"old", (output / "keep.txt").read_bytes())
            self.assertEqual(b"changed", (source / SERVER_PATH).read_bytes())
            self.assert_no_temp(output)

    def test_output_overlap_is_rejected(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(tmp, pin)
            for output in (source, source / "nested", tmp):
                with self.subTest(output=output):
                    completed = run_cli(source, output, pin_path)
                    self.assertNotEqual(0, completed.returncode)
                    self.assertIn("must not overlap", completed.stderr)
                    self.assertEqual(b"server", (source / SERVER_PATH).read_bytes())
                    self.assertEqual(b"dist", (source / DIST_PATH).read_bytes())

    def test_extra_manifest_fields_do_not_leak_into_runtime_manifest(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, _ = make_source(
                tmp,
                pin,
                extra={
                    "bunObserved": "1.4.2",
                    "bunPinned": "1.4.3",
                    "sourcePaths": ["/home/person/secret", "C:\\Users\\person"],
                    "counts": {"files": 2},
                    "serverFileCount": 1,
                    "distFileCount": 1,
                },
            )
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertEqual(0, completed.returncode, completed.stderr)
            for manifest_path in (output / "manifest.json", output / "apk-asset/openchamber-provider/manifest.json"):
                raw_text = manifest_path.read_text(encoding="utf-8")
                loaded = json.loads(raw_text)
                self.assertEqual(prep.RUNTIME_MANIFEST_KEYS, tuple(loaded))
                self.assertEqual(prep.RUNTIME_FILE_KEYS, tuple(loaded["server"][0]))
                for forbidden in (
                    "sourcePaths",
                    "counts",
                    "bunObserved",
                    "bunPinned",
                    "serverFileCount",
                    "distFileCount",
                    "/home/person/secret",
                    "C:\\\\Users",
                ):
                    self.assertNotIn(forbidden, raw_text)
            provenance = json.loads((output / "build-provenance.json").read_text(encoding="utf-8"))
            self.assertIn("counts", provenance)
            self.assertIn("buildTools", provenance)
            self.assertNotIn("/home/person/secret", json.dumps(provenance))

    def test_bad_json_and_missing_fields_are_clear_errors(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, manifest = make_source(tmp, pin)
            (source / "manifest.json").write_text("{not json", encoding="utf-8")
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("input manifest is not valid JSON", completed.stderr)
            self.assertNotIn("Traceback", completed.stderr)
            self.assertFalse(output.exists())

        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, manifest = make_source(tmp, pin)
            del manifest["server"]
            write_manifest(source, manifest)
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("manifest server", completed.stderr)
            self.assertFalse(output.exists())

    def test_manifest_counts_must_match_the_lists(self):
        with tempfile.TemporaryDirectory() as raw:
            tmp = Path(raw)
            pin_path, pin = make_pin(tmp)
            source, manifest = make_source(tmp, pin)
            manifest["serverFileCount"] = 9
            write_manifest(source, manifest)
            output = tmp / "output"

            completed = run_cli(source, output, pin_path)

            self.assertNotEqual(0, completed.returncode)
            self.assertIn("serverFileCount", completed.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
