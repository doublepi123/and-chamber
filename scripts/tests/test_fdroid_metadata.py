"""Offline, dependency-free checks for the candidate F-Droid metadata in fdroid/.

These tests use only the standard library (no YAML parser, no network, no fdroidserver). They
check facts the official schema and the corrected candidate depend on: the full commit selector,
plain AutoUpdateMode, the sudo block, the folded lines the formatter expects, and the localized
AntiFeatures sidecar. Checks depend only on files committed to this repository.
"""

from __future__ import annotations

import re
import socket
import unittest
import urllib.request
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
METADATA = REPO_ROOT / "fdroid" / "metadata" / "io.andchamber.app.yml"
ANTIFEATURE = REPO_ROOT / "fdroid" / "metadata" / "io.andchamber.app" / "en-US" / "antifeatures" / "NonFreeNet.txt"
FULL_SHA = "4d57d233fc8c8379c28c359f82bae71b39dc6be0"


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


class CandidateMetadataTests(NoNetworkMixin, unittest.TestCase):
    def setUp(self) -> None:
        super().setUp()
        self.text = METADATA.read_text(encoding="utf-8")

    def test_file_is_utf8_lf_and_ends_with_newline(self) -> None:
        raw = METADATA.read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"\n"))

    def test_autoupdate_mode_is_plain_version(self) -> None:
        self.assertRegex(self.text, r"(?m)^AutoUpdateMode: Version$")
        self.assertNotIn("v%v", self.text)

    def test_commit_selector_is_full_sha_of_published_v0_0_8(self) -> None:
        self.assertRegex(self.text, rf"(?m)^    commit: {FULL_SHA}$")
        self.assertNotRegex(self.text, r"(?m)^    commit: v0\.0\.8$")

    def test_sudo_block_installs_host_tools_for_managed_vm(self) -> None:
        self.assertRegex(self.text, r"(?m)^    sudo:\n      - apt-get update\n      - apt-get install -y make patch gawk$")

    def test_version_fields_match_current_release(self) -> None:
        self.assertRegex(self.text, r"(?m)^CurrentVersion: 0\.0\.8$")
        self.assertRegex(self.text, r"(?m)^CurrentVersionCode: 8$")
        self.assertRegex(self.text, r"(?m)^  - versionName: 0\.0\.8$")
        self.assertRegex(self.text, r"(?m)^    versionCode: 8$")

    def test_update_check_regexes_target_gradle_properties(self) -> None:
        self.assertRegex(self.text, r"(?m)^UpdateCheckMode: Tags \^v\[0-9\]\+\\\.\[0-9\]\+\\\.\[0-9\]\+\$$")
        self.assertIn("gradle.properties|", self.text)
        self.assertIn(r"andchamber\.versionCode=(\d+)", self.text)
        self.assertIn(r"andchamber\.versionName=([0-9]+\.[0-9]+\.[0-9]+)", self.text)

    def test_folded_lines_match_the_formatter_layout(self) -> None:
        # The official formatter wraps long init and UpdateCheckData values; the candidate keeps that form.
        self.assertRegex(self.text, r"(?m)^    init: python3 \.\./scripts/prepare_android_runtime_sources\.py --output-dir $")
        self.assertRegex(self.text, r"(?m)^      \.\./build/runtime-sources$")
        self.assertRegex(self.text, r"(?m)^UpdateCheckData: $")

    def test_scandelete_lists_exactly_three_source_archives(self) -> None:
        # Slice the scandelete section: it ends at the next four-space key (ndk, gradleprops, ...).
        start = self.text.index("    scandelete:\n")
        after = self.text[start + len("    scandelete:\n"):]
        section = re.split(r"(?m)^    [A-Za-z]", after, maxsplit=1)[0]
        entries = re.findall(r"^      - (.*)$", section, re.MULTILINE)
        self.assertEqual(entries, [
            "build/runtime-sources/downloads/talloc-2.5.0.tar.gz",
            "build/runtime-sources/downloads/proot-5.1.107.97.tar.gz",
            "build/runtime-sources/downloads/libandroid-shmem-0.7.tar.gz",
        ])

    def test_no_forbidden_build_options(self) -> None:
        for forbidden in ("skip-scan", "scanignore", "--force", "on-server", "Binaries:", "AllowedAPKSigningKeys"):
            with self.subTest(option=forbidden):
                self.assertNotIn(forbidden, self.text)

    def test_antifeature_sidecar_discloses_optional_proprietary_services(self) -> None:
        self.assertTrue(ANTIFEATURE.is_file())
        text = ANTIFEATURE.read_text(encoding="utf-8")
        self.assertIn("proprietary AI model providers", text)
        self.assertIn("self-hosted providers", text)
        self.assertIn("does not include or require a specific provider", text)

    def test_public_urls_only(self) -> None:
        for url in re.findall(r"https?://[^\s\"']+", self.text):
            with self.subTest(url=url):
                self.assertRegex(url, r"^https://(github\.com|gitlab\.com)/")


if __name__ == "__main__":
    unittest.main()
