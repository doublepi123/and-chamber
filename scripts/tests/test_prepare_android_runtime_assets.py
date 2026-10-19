"""Offline tests for the mirror health check and CLI argument rules of prepare_android_runtime_assets.py.

Run with: python3 -m unittest discover -s scripts/tests -p 'test_*.py'
No network access is used: download_bytes is replaced with an in-memory fake, and
urllib.request.urlopen / socket.create_connection are patched module-wide to fail loudly
if any test reaches the real network by mistake.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
SCRIPT_PATH = SCRIPTS_DIR / "prepare_android_runtime_assets.py"

_spec = importlib.util.spec_from_file_location("prepare_android_runtime_assets", SCRIPT_PATH)
assets = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(assets)


def _network_forbidden(*_args, **_kwargs):
    raise AssertionError("offline test attempted a real network connection")


# Module-wide guard: every test runs with real network access disabled.
_NETWORK_GUARDS = [
    mock.patch("urllib.request.urlopen", side_effect=_network_forbidden),
    mock.patch("socket.create_connection", side_effect=_network_forbidden),
    mock.patch("socket.getaddrinfo", side_effect=_network_forbidden),
]


def setUpModule() -> None:
    for guard in _NETWORK_GUARDS:
        guard.start()


def tearDownModule() -> None:
    for guard in _NETWORK_GUARDS:
        guard.stop()


ARCH = "x86_64"
INDEX_PATH = f"dists/stable/main/binary-{ARCH}/Packages"

VALID_INDEX = (
    b"Package: proot\n"
    b"Version: 5.1.107-70\n"
    b"Architecture: x86_64\n"
    b"Filename: pool/main/p/proot/proot_5.1.107-70_x86_64.deb\n"
    b"SHA256: " + b"a" * 64 + b"\n"
    b"Depends: libtalloc\n"
    b"\n"
    b"Package: libtalloc\n"
    b"Version: 2.4.2\n"
    b"Filename: pool/main/libt/libtalloc/libtalloc_2.4.2_x86_64.deb\n"
    b"SHA256: " + b"b" * 64 + b"\n"
)
NO_PROOT_INDEX = VALID_INDEX.replace(b"Package: proot", b"Package: other")

MIRROR_OK_1 = "https://ok-one.example/termux-main"
MIRROR_BAD_HTML = "https://html.example/termux-main"
MIRROR_DOWN = "https://down.example/termux-main"
MIRROR_NO_PROOT = "https://noproot.example/termux-main"
MIRROR_OK_2 = "https://ok-two.example/termux-main"
MIRRORS = [MIRROR_OK_1, MIRROR_BAD_HTML, MIRROR_DOWN, MIRROR_NO_PROOT, MIRROR_OK_2]


def make_fake_download(responses: dict[str, bytes | Exception]):
    """Return a download_bytes stand-in that answers only from `responses`, recording calls."""
    calls: list[str] = []

    def fake_download(url: str, attempts: int = 3) -> bytes:
        calls.append(url)
        response = responses.get(url)
        if response is None:
            raise RuntimeError(f"Failed to download {url}: 404 Not Found")
        if isinstance(response, Exception):
            raise response
        return response

    fake_download.calls = calls  # type: ignore[attr-defined]
    return fake_download


def standard_responses() -> dict[str, bytes | Exception]:
    return {
        f"{MIRROR_OK_1}/{INDEX_PATH}": VALID_INDEX,
        f"{MIRROR_BAD_HTML}/{INDEX_PATH}": b"<html>blocked by captive portal</html>",
        # MIRROR_DOWN is absent -> raises like a 404.
        f"{MIRROR_NO_PROOT}/{INDEX_PATH}": NO_PROOT_INDEX,
        f"{MIRROR_OK_2}/{INDEX_PATH}": VALID_INDEX,
    }


class MirrorHealthTests(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.object(assets, "configured_termux_main_base_urls", return_value=list(MIRRORS))
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_health(self, responses: dict[str, bytes | Exception]) -> tuple[dict, object]:
        fake = make_fake_download(responses)
        with mock.patch.object(assets, "download_bytes", side_effect=fake):
            report = assets.check_termux_mirror_health(termux_arch=ARCH)
        return report, fake

    def test_health_check_is_self_contained(self) -> None:
        # The removed helper must not be referenced, and the module must work without it.
        self.assertFalse((SCRIPTS_DIR / "android_visual_harness.py").exists())
        self.assertNotIn("android_visual_harness", SCRIPT_PATH.read_text(encoding="utf-8"))
        self.assertTrue(callable(assets.check_termux_mirror_health))

    def test_per_mirror_results_and_recommended_order(self) -> None:
        report, _ = self.run_health(standard_responses())

        self.assertEqual(report["termux_arch"], ARCH)
        self.assertEqual(report["healthy_count"], 2)
        self.assertEqual(report["recommended_base_urls"], [MIRROR_OK_1, MIRROR_OK_2])

        by_base = {item["base_url"]: item for item in report["mirrors"]}
        self.assertEqual([item["base_url"] for item in report["mirrors"]], MIRRORS)
        self.assertEqual(by_base[MIRROR_OK_1]["status"], "ok")
        self.assertEqual(by_base[MIRROR_OK_1]["package_count"], 2)
        self.assertEqual(by_base[MIRROR_OK_1]["url"], f"{MIRROR_OK_1}/{INDEX_PATH}")
        self.assertEqual(by_base[MIRROR_OK_2]["status"], "ok")

        self.assertEqual(by_base[MIRROR_BAD_HTML]["status"], "error")
        self.assertIn("Packages index", by_base[MIRROR_BAD_HTML]["error"])
        self.assertEqual(by_base[MIRROR_DOWN]["status"], "error")
        self.assertIn("404", by_base[MIRROR_DOWN]["error"])
        self.assertEqual(by_base[MIRROR_NO_PROOT]["status"], "error")
        self.assertIn("proot", by_base[MIRROR_NO_PROOT]["error"])

    def test_failed_mirror_is_not_masked_by_other_mirrors(self) -> None:
        # Only MIRROR_OK_1 serves a valid index. The others must not be reported ok
        # by falling through to it, so each mirror must be requested directly.
        responses = {f"{MIRROR_OK_1}/{INDEX_PATH}": VALID_INDEX}
        report, fake = self.run_health(responses)

        self.assertEqual(report["healthy_count"], 1)
        self.assertEqual(report["recommended_base_urls"], [MIRROR_OK_1])
        requested_bases = [url.split(f"/{INDEX_PATH}")[0] for url in fake.calls]
        self.assertEqual(requested_bases, MIRRORS)
        by_base = {item["base_url"]: item for item in report["mirrors"]}
        self.assertEqual(by_base[MIRROR_DOWN]["status"], "error")
        self.assertEqual(by_base[MIRROR_OK_2]["status"], "error")

    def test_no_healthy_mirror_reports_zero(self) -> None:
        report, _ = self.run_health({})
        self.assertEqual(report["healthy_count"], 0)
        self.assertEqual(report["recommended_base_urls"], [])
        self.assertEqual(len(report["mirrors"]), len(MIRRORS))
        self.assertTrue(all(item["status"] == "error" for item in report["mirrors"]))


class CheckMirrorsCliTests(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.object(assets, "configured_termux_main_base_urls", return_value=list(MIRRORS))
        patcher.start()
        self.addCleanup(patcher.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)

    def run_main(self, argv: list[str], responses: dict[str, bytes | Exception]) -> tuple[int, str]:
        """Run main() with argv; return (exit_code, stdout). exit_code 0 when main returns normally."""
        fake = make_fake_download(responses)
        stdout = io.StringIO()
        code = 0
        with mock.patch.object(assets, "download_bytes", side_effect=fake), mock.patch.object(
            sys, "argv", ["prepare_android_runtime_assets.py", *argv]
        ), contextlib.redirect_stdout(stdout):
            try:
                assets.main()
            except SystemExit as exc:
                code = int(exc.code or 0)
        return code, stdout.getvalue()

    def test_healthy_mirrors_exit_zero_and_write_report(self) -> None:
        report_path = self.tmp / "mirror-report.json"
        before = sorted(os.listdir(self.tmp))
        code, stdout = self.run_main(
            ["--check-mirrors", "--termux-arch", ARCH, "--mirror-report", str(report_path)],
            standard_responses(),
        )

        self.assertEqual(code, 0)
        self.assertEqual(stdout, "")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(report["healthy_count"], 2)
        self.assertEqual(report["recommended_base_urls"], [MIRROR_OK_1, MIRROR_OK_2])
        self.assertEqual(report["termux_arch"], ARCH)
        self.assertEqual(len(report["mirrors"]), len(MIRRORS))
        # Only the requested report file may appear; no asset output is generated.
        self.assertEqual(sorted(os.listdir(self.tmp)), sorted([*before, report_path.name]))

    def test_healthy_mirrors_print_json_to_stdout_without_report_file(self) -> None:
        code, stdout = self.run_main(["--check-mirrors"], standard_responses())
        self.assertEqual(code, 0)
        report = json.loads(stdout)
        self.assertEqual(report["recommended_base_urls"], [MIRROR_OK_1, MIRROR_OK_2])

    def test_no_healthy_mirror_exits_one(self) -> None:
        report_path = self.tmp / "mirror-report.json"
        code, _ = self.run_main(
            ["--check-mirrors", "--mirror-report", str(report_path)],
            {},
        )
        self.assertEqual(code, 1)
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(report["healthy_count"], 0)
        self.assertEqual(report["recommended_base_urls"], [])

    def test_check_mirrors_does_not_require_output_dir_or_create_assets(self) -> None:
        with mock.patch.object(assets, "prepare_assets") as prepare, mock.patch.object(
            assets, "build_lock_payload"
        ) as build_lock, mock.patch.object(assets, "write_lock_file") as write_lock:
            code, _ = self.run_main(["--check-mirrors"], standard_responses())
        self.assertEqual(code, 0)
        prepare.assert_not_called()
        build_lock.assert_not_called()
        write_lock.assert_not_called()


class ArgumentRuleTests(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)

    def run_main(self, argv: list[str]) -> int:
        stderr = io.StringIO()
        code = 0
        with mock.patch.object(sys, "argv", ["prepare_android_runtime_assets.py", *argv]), contextlib.redirect_stderr(
            stderr
        ), contextlib.redirect_stdout(io.StringIO()):
            try:
                assets.main()
            except SystemExit as exc:
                code = int(exc.code or 0)
        self.last_stderr = stderr.getvalue()
        return code

    def test_normal_build_requires_output_dir(self) -> None:
        with mock.patch.object(assets, "prepare_assets") as prepare:
            code = self.run_main(["--lock-file", str(self.tmp / "lock.json")])
        self.assertEqual(code, 2)
        self.assertIn("--output-dir is required", self.last_stderr)
        prepare.assert_not_called()

    def test_lock_only_does_not_require_output_dir(self) -> None:
        lock_path = self.tmp / "lock.json"
        with mock.patch.object(assets, "build_lock_payload", return_value={"version": 1}) as build_lock:
            code = self.run_main(["--lock-only", "--lock-file", str(lock_path)])
        self.assertEqual(code, 0)
        build_lock.assert_called_once_with()
        self.assertTrue(lock_path.is_file())
        self.assertEqual(sorted(p.name for p in self.tmp.iterdir()), ["lock.json"])

    def test_build_package_archive_does_not_require_output_dir(self) -> None:
        archive_path = self.tmp / "bundle.zip"

        def fake_build(_payload: dict, output: Path) -> str:
            output.write_bytes(b"PK-placeholder")
            return "0" * 64

        with mock.patch.object(assets, "load_lock_file", return_value={"version": 1}), mock.patch.object(
            assets, "build_package_archive", side_effect=fake_build
        ) as build_archive:
            code = self.run_main(["--build-package-archive", str(archive_path), "--lock-file", str(self.tmp / "l.json")])
        self.assertEqual(code, 0)
        build_archive.assert_called_once()

    def test_normal_build_with_output_dir_calls_prepare_assets(self) -> None:
        output_dir = self.tmp / "gen"
        with mock.patch.object(assets, "prepare_assets") as prepare:
            code = self.run_main(["--output-dir", str(output_dir), "--lock-file", str(self.tmp / "lock.json")])
        self.assertEqual(code, 0)
        prepare.assert_called_once()
        self.assertEqual(prepare.call_args.args[0], output_dir.resolve())
        self.assertTrue((output_dir / assets.ANDROID_LINUX_ASSET_ROOT).is_dir())

    def test_check_mirrors_with_stale_output_dir_still_does_not_generate_assets(self) -> None:
        output_dir = self.tmp / "gen"
        down_fake = make_fake_download({})  # every request raises a controlled RuntimeError
        with mock.patch.object(assets, "configured_termux_main_base_urls", return_value=[MIRROR_DOWN]), mock.patch.object(
            assets, "download_bytes", side_effect=down_fake
        ), mock.patch.object(assets, "prepare_assets") as prepare:
            code = self.run_main(["--check-mirrors", "--output-dir", str(output_dir)])
        self.assertEqual(down_fake.calls, [f"{MIRROR_DOWN}/{INDEX_PATH}"])
        self.assertEqual(code, 1)  # the only mirror is down
        prepare.assert_not_called()
        self.assertFalse(output_dir.exists())


class SyntaxTests(unittest.TestCase):
    def test_script_compiles(self) -> None:
        source = SCRIPT_PATH.read_text(encoding="utf-8")
        compile(source, str(SCRIPT_PATH), "exec")


if __name__ == "__main__":
    unittest.main()
