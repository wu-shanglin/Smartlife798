import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("collect_baseline", Path(__file__).parents[1] / "collect-baseline.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BaselineTests(unittest.TestCase):
    def test_only_actual_production_classes_survive(self):
        with tempfile.TemporaryDirectory() as directory:
            classes = Path(directory)
            file = classes / "com/smartlife798/app/MainActivity.class"
            file.parent.mkdir(parents=True)
            file.touch()
            profile = "\n".join([
                "HSPLcom/smartlife798/app/MainActivity;->onCreate(Landroid/os/Bundle;)V",
                "Lcom/smartlife798/app/MainActivity;",
                "Lcom/smartlife798/app/OfflineTransport;",
                "HLandroidx/compose/runtime/ComposerImpl;->method()V",
                "HLcom/smartlife798/app/Unknown;->method()V",
                "Lcom/smartlife798/app/MainActivity;",  # deduplicated
            ])
            rules = module.production_rules(profile, classes)
            self.assertEqual(len(rules), 2)
            self.assertTrue(all("MainActivity;" in rule for rule in rules))

    def test_missing_classes_or_empty_capture_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            classes = Path(directory)
            with self.assertRaises(ValueError):
                module.production_rules("", classes)
            (classes / "Unrelated.class").touch()
            with self.assertRaises(ValueError):
                module.production_rules("Lcom/smartlife798/app/Unknown;", classes)
