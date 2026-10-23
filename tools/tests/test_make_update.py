import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('make_update', Path(__file__).parents[1] / 'make-update.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class UpdateMetadataTest(unittest.TestCase):
    def test_normal_and_mandatory_minimum(self):
        gradle = 'versionCode = 24\nversionName = "1.7.0"'
        self.assertEqual(module.metadata(gradle, 1, '改进', 'a' * 64)['minSupportedVersionCode'], 1)
        self.assertEqual(module.metadata(gradle, 24, '修复', 'a' * 64)['versionCode'], 24)
        for minimum in (0, -1, 25):
            with self.assertRaises(ValueError):
                module.metadata(gradle, minimum, '', 'a' * 64)

    def test_reject_invalid_version_and_large_notes(self):
        for name, notes in [('1.7.0-beta', ''), ('01.7.0', ''), ('1.7.0', 'a' * 4001)]:
            with self.assertRaises(ValueError):
                module.metadata(f'versionCode = 24\nversionName = "{name}"', 1, notes, 'a' * 64)
