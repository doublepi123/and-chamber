"""Offline tests for source preparation, safe extraction and build input diagnostics.

No test touches the network: urllib and socket are patched to fail if called.
"""

from __future__ import annotations

import hashlib
import io
import json
import socket
import sys
import tarfile
import tempfile
import unittest
import urllib.request
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts"))

import build_android_runtime_native as build  # noqa: E402
import prepare_android_runtime_sources as prep  # noqa: E402


def _tar_bytes(entries: list[tuple[str, bytes | None, str]]) -> bytes:
    """entries: (name, data or None for dir, type 'file'|'dir'|'sym'|'hard'|'dev')."""
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for name, data, kind in entries:
            info = tarfile.TarInfo(name)
            if kind == "dir":
                info.type = tarfile.DIRTYPE
                tar.addfile(info)
            elif kind == "sym":
                info.type = tarfile.SYMTYPE
                info.linkname = data.decode()
                tar.addfile(info)
            elif kind == "hard":
                info.type = tarfile.LNKTYPE
                info.linkname = data.decode()
                tar.addfile(info)
            elif kind == "dev":
                info.type = tarfile.CHRTYPE
                tar.addfile(info)
            else:
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


def _lock_for(archive: bytes, root: str, license_bytes: bytes) -> dict:
    return {
        "version": 1,
        "android": {"min_api": 26, "ndk_version": "29.0.14206865", "abis": {}},
        "components": {
            "demo": {
                "version": "1.0",
                "archive": {
                    "url": "https://example.invalid/demo.tar.gz",
                    "filename": "demo-1.0.tar.gz",
                    "sha256": hashlib.sha256(archive).hexdigest(),
                    "root_dir": root,
                },
                "license": {
                    "spdx": "MIT",
                    "file": "LICENSE",
                    "sha256": hashlib.sha256(license_bytes).hexdigest(),
                },
            }
        },
    }


class NoNetworkMixin:
    def setUp(self) -> None:
        super().setUp()
        patches = [
            mock.patch.object(urllib.request, "urlopen", side_effect=AssertionError("network touched")),
            mock.patch.object(socket, "create_connection", side_effect=AssertionError("network touched")),
            mock.patch.object(socket, "getaddrinfo", side_effect=AssertionError("network touched")),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)


class PrepareSourcesTests(NoNetworkMixin, unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.license = b"license text\n"
        self.good = _tar_bytes([
            ("demo-1.0", None, "dir"),
            ("demo-1.0/LICENSE", self.license, "file"),
            ("demo-1.0/src.c", b"int main(void){return 0;}\n", "file"),
        ])

    def _archives(self, archive: bytes) -> Path:
        directory = self.root / "archives"
        directory.mkdir(exist_ok=True)
        (directory / "demo-1.0.tar.gz").write_bytes(archive)
        return directory

    def test_prepares_verified_archive_into_stable_directory(self) -> None:
        lock = _lock_for(self.good, "demo-1.0", self.license)
        out = self.root / "out"
        provenance = prep.prepare_sources(lock, out, self._archives(self.good))
        self.assertEqual(provenance["components"][0]["source_root"], "demo/demo-1.0")
        self.assertTrue((out / "demo" / "demo-1.0" / "src.c").is_file())
        self.assertTrue((out / "sources.json").is_file())
        recorded = json.loads((out / "sources.json").read_text())
        self.assertEqual(recorded["components"][0]["license_spdx"], "MIT")

    def test_rejects_archive_hash_mismatch(self) -> None:
        lock = _lock_for(self.good, "demo-1.0", self.license)
        tampered = self.good[:-4] + b"XXXX"
        with self.assertRaises(prep.SourceIntegrityError):
            prep.prepare_sources(lock, self.root / "out", self._archives(tampered))

    def test_rejects_license_hash_mismatch(self) -> None:
        lock = _lock_for(self.good, "demo-1.0", b"different license\n")
        with self.assertRaises(prep.SourceIntegrityError):
            prep.prepare_sources(lock, self.root / "out", self._archives(self.good))

    def test_rejects_unsafe_member_names(self) -> None:
        for name in ("../escape.txt", "/abs.txt", "demo-1.0/../../x"):
            with self.subTest(name=name):
                archive = _tar_bytes([("demo-1.0/LICENSE", self.license, "file"), (name, b"x", "file")])
                lock = _lock_for(archive, "demo-1.0", self.license)
                with self.assertRaises(prep.SourceIntegrityError):
                    prep.prepare_sources(lock, self.root / f"out-{abs(hash(name))}", self._archives(archive))

    def test_rejects_hardlinks_devices_and_escaping_symlinks(self) -> None:
        cases = {
            "hardlink": ("demo-1.0/h", b"demo-1.0/LICENSE", "hard"),
            "device": ("demo-1.0/dev", None, "dev"),
            "escaping-symlink": ("demo-1.0/s", b"../../etc/passwd", "sym"),
        }
        for label, entry in cases.items():
            with self.subTest(case=label):
                archive = _tar_bytes([("demo-1.0/LICENSE", self.license, "file"), entry])
                lock = _lock_for(archive, "demo-1.0", self.license)
                with self.assertRaises(prep.SourceIntegrityError):
                    prep.prepare_sources(lock, self.root / f"out-{label}", self._archives(archive))

    def test_accepts_internal_symlink(self) -> None:
        archive = _tar_bytes([
            ("demo-1.0/LICENSE", self.license, "file"),
            ("demo-1.0/link", b"LICENSE", "sym"),
        ])
        lock = _lock_for(archive, "demo-1.0", self.license)
        prep.prepare_sources(lock, self.root / "out-link", self._archives(archive))

    def test_missing_local_archive_is_reported(self) -> None:
        lock = _lock_for(self.good, "demo-1.0", self.license)
        empty = self.root / "empty"
        empty.mkdir()
        with self.assertRaises(FileNotFoundError):
            prep.prepare_sources(lock, self.root / "out", empty)

    def test_unsupported_lock_version_is_rejected(self) -> None:
        lock_path = self.root / "lock.json"
        lock_path.write_text(json.dumps({"version": 2, "components": {}}))
        with self.assertRaises(ValueError):
            prep.load_lock(lock_path)

    def test_repository_lock_pins_are_well_formed(self) -> None:
        lock = prep.load_lock(prep.DEFAULT_LOCK)
        self.assertEqual(set(lock["components"]), {"proot", "talloc", "libandroid-shmem"})
        self.assertEqual(lock["android"]["min_api"], 26)
        for name, spec in lock["components"].items():
            with self.subTest(component=name):
                self.assertEqual(len(spec["archive"]["sha256"]), 64)
                self.assertEqual(len(spec["license"]["sha256"]), 64)
                self.assertTrue(spec["archive"]["url"].startswith("https://"))


class BuildInputDiagnosticTests(unittest.TestCase):
    def test_missing_sources_message_names_prepare_command(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(build.BuildInputError) as ctx:
                build.load_provenance(Path(tmp) / "nope")
            self.assertIn("prepare_android_runtime_sources.py", str(ctx.exception))

    def test_missing_component_tree_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            provenance = {"components": [{"component": "talloc", "source_root": "talloc/talloc-2.5.0"}]}
            with self.assertRaises(build.BuildInputError) as ctx:
                build.component_source(root, provenance, "talloc")
            self.assertIn("prepare_android_runtime_sources.py", str(ctx.exception))
            with self.assertRaises(build.BuildInputError):
                build.component_source(root, provenance, "proot")

    def test_wrong_ndk_revision_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ndk = Path(tmp)
            (ndk / "source.properties").write_text("Pkg.Revision = 28.0.0\n")
            with self.assertRaises(build.BuildInputError):
                build.check_ndk(ndk)

    def test_ndk_without_properties_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(build.BuildInputError):
                build.check_ndk(Path(tmp))

    def test_build_driver_never_resolves_network(self) -> None:
        with mock.patch.object(urllib.request, "urlopen", side_effect=AssertionError("network")):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                self.assertEqual(build.main(["--sources-dir", str(root / "s"), "--ndk-dir", str(root / "n"),
                                             "--output-dir", str(root / "o"), "--work-dir", str(root / "w")]), 1)


class DisjointLayoutTests(unittest.TestCase):
    """assert_disjoint must reject equal, nested (both directions) and symlink-aliased layouts,
    and must do so before any deletion. A sentinel file in every directory proves nothing is removed."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def _sentinel(self, path: Path) -> Path:
        path.mkdir(parents=True, exist_ok=True)
        sentinel = path / "KEEP.txt"
        sentinel.write_text("do not delete", encoding="utf-8")
        return sentinel

    def test_disjoint_layout_is_accepted(self) -> None:
        build.assert_disjoint(sources_dir=self.root / "a", output_dir=self.root / "b", work_dir=self.root / "c")

    def test_equal_paths_are_rejected(self) -> None:
        same = self.root / "same"
        with self.assertRaises(build.BuildInputError):
            build.assert_disjoint(sources_dir=same, output_dir=same, work_dir=self.root / "w")

    def test_nested_both_directions_are_rejected(self) -> None:
        outer = self.root / "outer"
        inner = outer / "inner"
        with self.assertRaises(build.BuildInputError):
            build.assert_disjoint(sources_dir=outer, output_dir=inner, work_dir=self.root / "w")
        with self.assertRaises(build.BuildInputError):
            build.assert_disjoint(sources_dir=inner, output_dir=outer, work_dir=self.root / "w")

    def test_symlink_alias_is_rejected(self) -> None:
        real = self.root / "real"
        real.mkdir()
        alias = self.root / "alias"
        alias.symlink_to(real, target_is_directory=True)
        with self.assertRaises(build.BuildInputError):
            build.assert_disjoint(sources_dir=real, output_dir=alias, work_dir=self.root / "w")

    def test_symlink_pointing_inside_is_rejected(self) -> None:
        sources = self.root / "sources"
        sources.mkdir()
        link_to_sub = self.root / "out-link"
        link_to_sub.symlink_to(sources / "sub", target_is_directory=True)
        with self.assertRaises(build.BuildInputError):
            build.assert_disjoint(sources_dir=sources, output_dir=link_to_sub, work_dir=self.root / "w")

    def test_rejection_deletes_nothing(self) -> None:
        sentinel = self._sentinel(self.root / "outer")
        with self.assertRaises(build.BuildInputError):
            build.assert_disjoint(sources_dir=self.root / "outer", output_dir=self.root / "outer" / "x",
                                  work_dir=self.root / "w")
        self.assertTrue(sentinel.is_file())

    def test_main_refuses_overlap_before_writing(self) -> None:
        sentinel = self._sentinel(self.root / "sources")
        code = build.main(["--sources-dir", str(self.root / "sources"), "--ndk-dir", str(self.root / "ndk"),
                           "--output-dir", str(self.root / "sources"), "--work-dir", str(self.root / "w")])
        self.assertEqual(code, 1)
        self.assertTrue(sentinel.is_file())
        self.assertFalse((self.root / "w").exists())


def _lock_fixture() -> dict:
    return build.load_lock()


def _valid_manifest(lock: dict) -> dict:
    """Synthetic manifest that exactly matches the current lock (no real archives needed)."""
    records = []
    for name in sorted(lock["components"]):
        spec = lock["components"][name]
        records.append({
            "component": name,
            "version": spec["version"],
            "archive": spec["archive"]["filename"],
            "archive_url": spec["archive"]["url"],
            "archive_sha256": spec["archive"]["sha256"],
            "commit": spec.get("commit"),
            "source_root": f"{name}/{spec['archive']['root_dir']}",
            "license_spdx": spec["license"]["spdx"],
            "license_file": spec["license"]["file"],
            "license_sha256": spec["license"]["sha256"],
            "termux_recipe_commit": spec.get("termux_recipe_commit"),
            "termux_patches": spec.get("termux_patches", []),
        })
    return {"lock_version": lock["version"], "ndk_version": lock["android"]["ndk_version"],
            "min_api": lock["android"]["min_api"], "components": records}


class ProvenanceVerificationTests(unittest.TestCase):
    """load_provenance is checked against the CURRENT lock. Each stale field, duplicate, and
    escaping source_root must fail with the preparation hint; the valid manifest must pass."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.sources = Path(self._tmp.name) / "sources"
        self.sources.mkdir()
        self.lock = _lock_fixture()

    def _write(self, manifest: dict) -> None:
        (self.sources / "sources.json").write_text(json.dumps(manifest), encoding="utf-8")

    def test_valid_manifest_is_accepted(self) -> None:
        self._write(_valid_manifest(self.lock))
        provenance = build.load_provenance(self.sources)
        self.assertEqual({c["component"] for c in provenance["components"]}, set(self.lock["components"]))

    def test_stale_fields_are_rejected_with_hint(self) -> None:
        cases = {
            "lock_version": ("lock_version", 2),
            "ndk_version": ("ndk_version", "28.0.0"),
            "min_api": ("min_api", 21),
            "version": ("version", "0.0.0"),
            "archive_sha256": ("archive_sha256", "0" * 64),
            "archive_url": ("archive_url", "https://example.invalid/x.tar.gz"),
            "commit": ("commit", "0" * 40),
            "license_spdx": ("license_spdx", "MIT"),
            "license_sha256": ("license_sha256", "f" * 64),
            "termux_recipe_commit": ("termux_recipe_commit", "deadbeef"),
        }
        for label, (field, bad) in cases.items():
            with self.subTest(field=label):
                manifest = _valid_manifest(self.lock)
                target = manifest if field in ("lock_version", "ndk_version", "min_api") else manifest["components"][0]
                target[field] = bad
                self._write(manifest)
                with self.assertRaises(build.BuildInputError) as ctx:
                    build.load_provenance(self.sources)
                self.assertIn("prepare_android_runtime_sources.py", str(ctx.exception))

    def test_duplicate_component_is_rejected(self) -> None:
        manifest = _valid_manifest(self.lock)
        manifest["components"].append(dict(manifest["components"][0]))
        self._write(manifest)
        with self.assertRaises(build.BuildInputError) as ctx:
            build.load_provenance(self.sources)
        self.assertIn("duplicate", str(ctx.exception))

    def test_missing_and_extra_components_are_rejected(self) -> None:
        missing = _valid_manifest(self.lock)
        missing["components"] = missing["components"][1:]
        self._write(missing)
        with self.assertRaises(build.BuildInputError):
            build.load_provenance(self.sources)
        extra = _valid_manifest(self.lock)
        extra["components"].append({**extra["components"][0], "component": "unexpected"})
        self._write(extra)
        with self.assertRaises(build.BuildInputError):
            build.load_provenance(self.sources)

    def test_malformed_and_escaping_source_root_are_rejected(self) -> None:
        bad_roots = ["../escape", "/abs/path", "proot/../../x", "wrong/root", "", 7]
        for bad in bad_roots:
            with self.subTest(source_root=bad):
                manifest = _valid_manifest(self.lock)
                manifest["components"][0]["source_root"] = bad
                self._write(manifest)
                with self.assertRaises(build.BuildInputError):
                    build.load_provenance(self.sources)

    def test_symlinked_source_root_escape_is_rejected(self) -> None:
        outside = Path(self._tmp.name) / "outside"
        outside.mkdir()
        manifest = _valid_manifest(self.lock)
        name = manifest["components"][0]["component"]
        root = self.lock["components"][name]["archive"]["root_dir"]
        (self.sources / name).mkdir()
        (self.sources / name / root).symlink_to(outside, target_is_directory=True)
        self._write(manifest)
        with self.assertRaises(build.BuildInputError):
            build.load_provenance(self.sources)

    def test_missing_manifest_points_to_prepare_command(self) -> None:
        with self.assertRaises(build.BuildInputError) as ctx:
            build.load_provenance(self.sources)
        self.assertIn("prepare_android_runtime_sources.py", str(ctx.exception))

    def test_lock_changes_are_detected_against_current_lock(self) -> None:
        self._write(_valid_manifest(self.lock))
        stricter = json.loads(json.dumps(self.lock))
        stricter["components"]["proot"]["version"] = "9.9.9"
        lock_path = Path(self._tmp.name) / "lock.json"
        lock_path.write_text(json.dumps(stricter), encoding="utf-8")
        with self.assertRaises(build.BuildInputError):
            build.load_provenance(self.sources, lock_path)


class ProotVersionTests(unittest.TestCase):
    """PRoot's version comes only from the verified lock, never from an application git describe."""

    def test_version_comes_from_provenance(self) -> None:
        provenance = _valid_manifest(_lock_fixture())
        self.assertEqual(build.proot_version(provenance), "5.1.107.98")

    def test_makefile_git_override_is_passed_on_command_line(self) -> None:
        source = Path(build.__file__).read_text(encoding="utf-8")
        self.assertIn('"GIT=true"', source)
        self.assertNotIn('env["GIT"]', source)

    def test_shmem_patch_is_tracked_and_scoped(self) -> None:
        patch_text = build.SHMEM_PATCH.read_text(encoding="utf-8")
        self.assertIn("PROOT_TMP_DIR", patch_text)
        self.assertNotIn("/data/data/com.termux", patch_text)
        self.assertIn("errno != EEXIST", patch_text)


if __name__ == "__main__":
    unittest.main()
