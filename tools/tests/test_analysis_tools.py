import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]


def dex_with_string(value: str) -> bytes:
    encoded = value.encode("ascii")
    header = bytearray(112)
    header[:8] = b"dex\n035\0"
    struct.pack_into("<II", header, 56, 1, 112)
    return bytes(header) + struct.pack("<I", 116) + bytes([len(encoded)]) + encoded + b"\0"


class AnalysisToolsTest(unittest.TestCase):
    def test_extract_uses_requested_paths_and_keeps_collision_contents(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "sample.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("a.txt", "first")
                archive.writestr("A.TXT", "second")
                archive.writestr("../outside.txt", "contained")
            result = subprocess.run(
                [sys.executable, str(TOOLS / "extract.py"), str(apk), "--output", str(root / "out")],
                cwd=root, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / "out/a.txt").read_text(), "first")
            self.assertEqual((root / "out/A.TXT_dup1").read_text(), "second")
            self.assertEqual((root / "out/__up__/outside.txt").read_text(), "contained")
            self.assertFalse((root / "outside.txt").exists())

    def test_dex_dump_discovers_members_instead_of_assuming_seven(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "dex"
            source.mkdir()
            (source / "classes.dex").write_bytes(dex_with_string("one"))
            (source / "classes9.dex").write_bytes(dex_with_string("nine"))
            result = subprocess.run(
                [sys.executable, str(TOOLS / "dexstrings.py"), "--input", str(source), "--output", str(root / "meta")],
                cwd=root, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((root / "meta/strings.txt").read_text().splitlines(), ["nine", "one"])

    def test_empty_dex_input_is_an_error(self):
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(
                [sys.executable, str(TOOLS / "dexstrings.py"), "--input", temp, "--output", str(Path(temp) / "meta")],
                capture_output=True,
            )
            self.assertEqual(result.returncode, 2)
            self.assertFalse((Path(temp) / "meta").exists())


if __name__ == "__main__":
    unittest.main()
