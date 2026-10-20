import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "prepare_openchamber_browser_provider.py"


def pin(path: Path, relative: str, content: bytes) -> dict:
    file = path / relative
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(content)
    return {
        "path": relative,
        "sha256": hashlib.sha256(content).hexdigest(),
        "bytes": len(content),
    }


class PrepareOpenChamberBrowserProviderTest(unittest.TestCase):
    def test_verified_pair_is_copied(self):
        with tempfile.TemporaryDirectory() as raw:
            source = Path(raw) / "source"
            output = Path(raw) / "output"
            manifest = {
                "package": "@openchamber/web",
                "version": "2.4.0",
                "baseCommit": "6a10ee19a12a1542cad01eee5add14e030ba4ac4",
                "sourcePatchSha256": "57c45074052f52ca941d68765ca5f0b6c4c6624c70809f3492c6f0af97e251b8",
                "server": [pin(source, "packages/web/server/index.js", b"server")],
                "dist": [pin(source, "packages/web/dist/index.html", b"dist")],
            }
            (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

            subprocess.run(["python3", str(SCRIPT), "--source", str(source), "--output", str(output)], check=True)

            self.assertEqual(b"server", (output / "server/index.js").read_bytes())
            self.assertTrue((output / "manifest.json").is_file())

    def test_hash_mismatch_writes_nothing(self):
        with tempfile.TemporaryDirectory() as raw:
            source = Path(raw) / "source"
            output = Path(raw) / "output"
            item = pin(source, "packages/web/server/index.js", b"server")
            item["sha256"] = "0" * 64
            manifest = {
                "package": "@openchamber/web",
                "version": "2.4.0",
                "baseCommit": "6a10ee19a12a1542cad01eee5add14e030ba4ac4",
                "sourcePatchSha256": "57c45074052f52ca941d68765ca5f0b6c4c6624c70809f3492c6f0af97e251b8",
                "server": [item],
                "dist": [pin(source, "packages/web/dist/index.html", b"dist")],
            }
            (source / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

            completed = subprocess.run(
                ["python3", str(SCRIPT), "--source", str(source), "--output", str(output)],
                check=False,
                text=True,
                capture_output=True,
            )

            self.assertNotEqual(0, completed.returncode)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
