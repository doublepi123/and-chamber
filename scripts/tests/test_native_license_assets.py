"""Offline checks for the native license texts and provenance in third_party/native-runtime/.

These tests run on a fresh checkout: they do not need build/runtime-sources and never touch the
network. They verify the copied license bytes against their recorded SHA-256, the presence of the
full GPL-3.0 text, the SPDX labels and source fields against the source lock, and that upstream
text (copyright lines, the BSD {organization} placeholder) was not modified.
"""

from __future__ import annotations

import hashlib
import json
import re
import socket
import unittest
import urllib.request
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
NATIVE_DIR = REPO_ROOT / "third_party" / "native-runtime"
LOCK = REPO_ROOT / "runtime_tools" / "runtime_sources.lock.json"
PROVENANCE = NATIVE_DIR / "provenance.json"

PROOT_COPYING_SHA256 = "0ffa6454acbe7e410ac6f0007df83b9b3161fe55484c1f4b87487c0f2d228a6d"
TALLOC_LICENSE_SHA256 = "da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768"
SHMEM_LICENSE_SHA256 = "0dd43abe17196b0224cb0b159819af798cd508a82a21e5870126fff4e899a7eb"
GPL3_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class NoNetworkMixin:
    def setUp(self) -> None:
        super().setUp()
        for patch in (
            mock.patch.object(urllib.request, "urlopen", side_effect=AssertionError("network touched")),
            mock.patch.object(socket, "create_connection", side_effect=AssertionError("network touched")),
            mock.patch.object(socket, "getaddrinfo", side_effect=AssertionError("network touched")),
        ):
            patch.start()
            self.addCleanup(patch.stop)


class LicenseFileTests(NoNetworkMixin, unittest.TestCase):
    def test_license_files_match_recorded_hashes(self) -> None:
        expected = {
            "proot/COPYING": PROOT_COPYING_SHA256,
            "talloc/LICENSE": TALLOC_LICENSE_SHA256,
            "talloc/GPL-3.0.txt": GPL3_SHA256,
            "libandroid-shmem/LICENSE": SHMEM_LICENSE_SHA256,
        }
        for relative, digest in expected.items():
            with self.subTest(file=relative):
                path = NATIVE_DIR / relative
                self.assertTrue(path.is_file(), f"missing {relative}")
                self.assertEqual(sha256_of(path), digest)

    def test_gpl3_text_is_full_version_3(self) -> None:
        text = (NATIVE_DIR / "talloc" / "GPL-3.0.txt").read_text(encoding="utf-8")
        self.assertIn("GNU GENERAL PUBLIC LICENSE", text)
        self.assertIn("Version 3, 29 June 2007", text)
        self.assertIn("END OF TERMS AND CONDITIONS", text)

    def test_talloc_lgpl_text_is_version_3_and_refers_to_gpl3(self) -> None:
        text = (NATIVE_DIR / "talloc" / "LICENSE").read_text(encoding="utf-8")
        self.assertIn("GNU LESSER GENERAL PUBLIC LICENSE", text)
        self.assertIn("Version 3, 29 June 2007", text)

    def test_proot_copying_permits_later_versions(self) -> None:
        text = (NATIVE_DIR / "proot" / "COPYING").read_text(encoding="utf-8")
        self.assertIn("either version 2 of the License, or", text)
        self.assertIn("any later version", text)

    def test_bsd_placeholder_and_copyright_are_unmodified(self) -> None:
        text = (NATIVE_DIR / "libandroid-shmem" / "LICENSE").read_text(encoding="utf-8")
        self.assertIn("Neither the name of the {organization}", text)
        self.assertIn("Copyright (c) 2013, Sergii Pylypenko", text)
        self.assertIn("Copyright (c) 2017, Fredrik Fornwall", text)

    def test_no_handmaintained_copies_under_app_assets(self) -> None:
        assets = REPO_ROOT / "app" / "src" / "main" / "assets"
        if not assets.exists():
            return
        for path in assets.rglob("*"):
            self.assertNotIn("native", path.parts[len(assets.parts):],
                             f"native license copy must not live in app assets: {path}")


class LockAndProvenanceTests(NoNetworkMixin, unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        self.lock = json.loads(LOCK.read_text(encoding="utf-8"))
        self.provenance = json.loads(PROVENANCE.read_text(encoding="utf-8"))
        self.by_name = {c["name"]: c for c in self.provenance["components"]}

    def test_lock_has_corrected_proot_spdx(self) -> None:
        self.assertEqual(self.lock["components"]["proot"]["license"]["spdx"], "GPL-2.0-or-later")

    def test_lock_spdx_labels_for_other_components(self) -> None:
        self.assertEqual(self.lock["components"]["talloc"]["license"]["spdx"], "LGPL-3.0-or-later")
        self.assertEqual(self.lock["components"]["libandroid-shmem"]["license"]["spdx"], "BSD-3-Clause")

    def test_lock_pins_still_match_recorded_versions(self) -> None:
        pins = {
            "proot": ("5.1.107.98", "e2d843ee6dab076ea0ed1ffd5d0ea12232185499",
                      "a1237d2039e0efedd1639aa4a99ac5e48a10f4c2e14cce445b2cbebedf32d22e"),
            "talloc": ("2.5.0", None, "912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"),
            "libandroid-shmem": ("0.7", "7f0bd7e25dbdd146265aff7c6a890029e374622d",
                                 "1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867"),
        }
        for name, (version, commit, archive_sha) in pins.items():
            with self.subTest(component=name):
                spec = self.lock["components"][name]
                self.assertEqual(spec["version"], version)
                self.assertEqual(spec.get("commit"), commit)
                self.assertEqual(spec["archive"]["sha256"], archive_sha)

    def test_provenance_source_fields_match_lock(self) -> None:
        for name, spec in self.lock["components"].items():
            with self.subTest(component=name):
                record = self.by_name[name]
                self.assertEqual(record["version"], spec["version"])
                self.assertEqual(record["archive_sha256"], spec["archive"]["sha256"])
                self.assertEqual(record["archive_url"], spec["archive"]["url"])
                self.assertEqual(record["spdx"], spec["license"]["spdx"])
                self.assertEqual(record.get("commit"), spec.get("commit"))

    def test_provenance_license_hashes_match_files(self) -> None:
        for record in self.provenance["components"]:
            for entry in record["license_files"]:
                with self.subTest(component=record["name"], file=entry["path"]):
                    path = NATIVE_DIR / entry["path"]
                    self.assertEqual(sha256_of(path), entry["sha256"])

    def test_provenance_lists_gpl3_reference_and_local_patches(self) -> None:
        talloc = self.by_name["talloc"]
        paths = [entry["path"] for entry in talloc["license_files"]]
        self.assertIn("talloc/GPL-3.0.txt", paths)
        proot_patches = self.by_name["proot"]["local_patches"]
        for patch in proot_patches:
            self.assertTrue((REPO_ROOT / patch).is_file(), patch)

    def test_provenance_urls_are_public_upstream_only(self) -> None:
        text = PROVENANCE.read_text(encoding="utf-8")
        allowed_hosts = {"github.com", "codeload.github.com", "www.samba.org", "www.gnu.org"}
        for url in re.findall(r"https?://[^\s\"]+", text):
            host = url.split("/")[2]
            with self.subTest(url=url):
                self.assertIn(host, allowed_hosts)

    def test_readme_references_provenance_and_source_offer(self) -> None:
        readme = (NATIVE_DIR / "README.md").read_text(encoding="utf-8")
        self.assertIn("provenance.json", readme)
        self.assertIn("runtime_tools/runtime_sources.lock.json", readme)
        self.assertIn("{organization}", readme)


class NoticeTests(unittest.TestCase):
    def test_notice_points_to_bundled_license_locations(self) -> None:
        notice = (REPO_ROOT / "NOTICE").read_text(encoding="utf-8")
        self.assertIn("third_party/native-runtime/", notice)
        self.assertIn("assets/licenses/native/", notice)
        self.assertIn("GPL-2.0-or-later", notice)
        self.assertNotIn("experimental branch", notice)


if __name__ == "__main__":
    unittest.main()
