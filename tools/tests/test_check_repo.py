import importlib.util
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("check_repo", TOOLS / "check-repo.py")
repo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repo)


class RepositoryChecksTest(unittest.TestCase):
    def test_layers_block_actual_access_but_allow_ui_copy(self):
        ui = repo.SOURCE + "ui/Page.kt"
        for code in ("Api.master()", "import com.smartlife798.app.core.Api as Backend\nBackend.master()",
                     "com.smartlife798.app.core.Api.master()", "okhttp3.OkHttpClient()",
                     'context.getSharedPreferences("x", 0)', "Store.token"):
            with self.subTest(code=code):
                self.assertTrue(repo.architecture_problems(ui, code))
        self.assertFalse(repo.architecture_problems(ui, 'Text("Api.master()") // Api.master()'))
        self.assertFalse(repo.architecture_problems(ui, 'import com.smartlife798.app.core.Api\nval url = Api.BASE_URL'))
        self.assertTrue(repo.architecture_problems(ui, 'val load = Api::master'))
        self.assertTrue(repo.architecture_problems(repo.SOURCE + "core/X.kt", "import androidx.compose.runtime.State"))
        self.assertTrue(repo.architecture_problems(repo.SOURCE + "core/X.kt", "import com.smartlife798.app.vm.MainViewModel"))
        self.assertFalse(repo.architecture_problems(repo.SOURCE + "core/Payment.kt", "import android.app.Activity"))

    def test_me_screen_has_no_network_exception(self):
        for page in ("MeScreen.kt", "Other.kt"):
            with self.subTest(page=page):
                self.assertTrue(repo.architecture_problems(repo.SOURCE + "ui/" + page, "Api.master()"))
                self.assertFalse(repo.architecture_problems(repo.SOURCE + "ui/" + page, "val url = Api.BASE_URL"))
        self.assertFalse(repo.architecture_problems(repo.SOURCE + "vm/MainViewModel.kt", "Api.master()"))

    def test_generated_material_and_machine_config_are_blocked(self):
        for name in ("SmartLife798/local.properties", "SmartLife798/app/build/output.txt",
                     "evidence/live.json", "key.jks", "tools/patches/X.kt.monitor", "tools/m3/a.jar",
                     "screenshots/new_home.png", "screenshots/home_v2.png"):
            with self.subTest(name=name):
                self.assertTrue(repo.file_problems(name))
        self.assertFalse(repo.file_problems("SmartLife798/gradle/wrapper/gradle-wrapper.jar"))
        self.assertFalse(repo.file_problems("screenshots/appearance_dark.png"))

    def test_links_and_unused_images_in_fresh_checkout(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "docs").mkdir()
            (root / "screenshots").mkdir()
            (root / "screenshots/home.png").touch()
            (root / "screenshots/unused.png").touch()
            (root / "docs/说明.md").write_text("# 内容", encoding="utf-8")
            (root / "README.md").write_text(
                "[doc](docs/%E8%AF%B4%E6%98%8E.md#anchor)\n![home](screenshots/home.png)\n"
                "[apk](dist/SmartLife798-1.5.1-debug.apk)\n[web](https://example.test)\n"
                "[bad](docs/missing.md)\n[ref]: docs/also-missing.md\n"
                "```md\n[example](example.md)\n```\n`[inline](example.md)`\n", encoding="utf-8",
            )
            names = ["README.md", "docs/说明.md", "screenshots/home.png", "screenshots/unused.png", "deleted.md"]
            failures = repo.check(root, names)
            self.assertEqual(len(failures), 3, failures)
            self.assertTrue(any("missing.md" in f for f in failures))
            self.assertTrue(any("also-missing.md" in f for f in failures))
            self.assertTrue(any("unused.png" in f for f in failures))


if __name__ == "__main__":
    unittest.main()
