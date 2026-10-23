import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('make_update', Path(__file__).parents[1] / 'make-update.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class UpdateMetadataTest(unittest.TestCase):
    def test_history_contains_all_skipped_versions_in_numeric_order(self):
        changelog = '\n'.join([
            '## [Unreleased]', '尚未发布', '## [1.7.11] - 2026-10-24', '未来',
            '## [1.7.10] - 2026-10-24', '当前', '## [1.7.9] - 2026-10-24', '九',
            '## [1.7.8] - 2026-10-24', '八', '## [1.7.7] - 2026-10-24', '七',
        ])
        result = module.release_history(changelog, '1.7.10', '十')
        self.assertEqual([r['versionName'] for r in result], ['1.7.10', '1.7.9', '1.7.8', '1.7.7'])
        self.assertEqual(result[0]['notes'], '十')
        self.assertNotIn('尚未发布', str(result))

    def test_invalid_history_is_not_silently_published(self):
        for changelog in ['## [1.6.0]\n', '## [1.6.0]\na\n## [1.6.0]\nb',
                          '## [1.6.0]\n' + 'a' * 4001, '## [01.6.0]\na']:
            with self.subTest(changelog=changelog[:70]), self.assertRaises(ValueError):
                module.release_history(changelog, '1.7.0', '改进')

    def test_update_policy_remains_within_legacy_byte_limit(self):
        import json
        result = module.metadata('versionCode = 24\nversionName = "1.7.0"', 1, '改' * 4000, 'a' * 64)
        self.assertNotIn('releases', result)
        self.assertTrue(result['historyAvailable'])
        self.assertLessEqual(len(json.dumps(result, ensure_ascii=False, indent=2).encode('utf-8')), 16384)
        with self.assertRaises(ValueError):
            module.metadata('versionCode = 24\nversionName = "1.7.0"', 1, '\0' * 4000, 'a' * 64)

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
