import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class VersionCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tools").mkdir()
        shutil.copyfile(Path(__file__).parents[1] / "check-version.py",
                        self.root / "tools/check-version.py")
        gradle = self.root / "SmartLife798/app/build.gradle.kts"
        gradle.parent.mkdir(parents=True)
        gradle.write_text('versionName = "1.7.0"\nversionCode = 23\n', encoding="utf-8")
        (self.root / "CHANGELOG.md").write_text(
            "## [Unreleased]\n\n## [1.7.0] - 2026-10-23\n", encoding="utf-8")
        self.apk = self.root / "dist/SmartLife798-1.7.0-debug.apk"
        self.apk.parent.mkdir()
        self.apk.write_bytes(b"test artifact, not an installable APK")
        self.readme = (
            "| 当前版本 | `1.7.0`（`versionCode 23`） |\n"
            "| 产物 | [APK](dist/SmartLife798-1.7.0-debug.apk) |\n"
        )
        self.write_readme(self.readme)

    def write_readme(self, text):
        (self.root / "README.md").write_text(text, encoding="utf-8")

    def run_check(self, *args):
        return subprocess.run([sys.executable, str(self.root / "tools/check-version.py"), *args],
                              capture_output=True, encoding="utf-8")

    def test_valid_artifact_in_release_mode(self):
        result = self.run_check("--require-apk")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_artifact_allowed_only_in_source_mode(self):
        self.apk.unlink()
        self.assertEqual(self.run_check().returncode, 0)
        self.assertEqual(self.run_check("--require-apk").returncode, 1)

    def test_version_prefix_does_not_match(self):
        for wrong in ("1.7.01", "1.7.0-beta"):
            with self.subTest(version=wrong):
                self.write_readme(self.readme.replace("`1.7.0`", f"`{wrong}`"))
                self.assertEqual(self.run_check().returncode, 1)

    def test_version_code_prefix_does_not_match(self):
        self.write_readme(self.readme.replace("versionCode 23", "versionCode 230"))
        self.assertEqual(self.run_check().returncode, 1)

    def test_version_code_elsewhere_cannot_hide_wrong_current_row(self):
        self.write_readme(self.readme.replace("versionCode 23", "versionCode 22") +
                          "\n历史示例：versionCode 23\n")
        self.assertEqual(self.run_check().returncode, 1)

    def test_link_elsewhere_cannot_hide_wrong_artifact_row(self):
        self.write_readme(self.readme.replace("1.7.0-debug.apk", "1.6.0-debug.apk") +
                          "\n旧说明 dist/SmartLife798-1.7.0-debug.apk\n")
        self.assertEqual(self.run_check().returncode, 1)

    def test_duplicate_current_rows_are_rejected(self):
        self.write_readme(self.readme + self.readme.splitlines()[0] + "\n")
        self.assertEqual(self.run_check().returncode, 1)

    def test_source_check_ignores_artifacts_and_release_only_requires_file(self):
        self.apk.write_bytes(b"different local build")
        (self.apk.parent / "SmartLife798-1.6.0-debug.apk").touch()
        self.assertEqual(self.run_check().returncode, 0)
        self.assertEqual(self.run_check("--require-apk").returncode, 0)
        self.apk.unlink()
        self.apk.mkdir()
        self.assertEqual(self.run_check().returncode, 0)
        self.assertEqual(self.run_check("--require-apk").returncode, 1)

    def test_quiet_source_check_without_artifact(self):
        self.apk.unlink()
        result = self.run_check("--quiet")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
