import importlib.util
import unittest
from pathlib import Path

class SmokeAssertionTests(unittest.TestCase):
    def setUp(self):
        path = Path('scripts/failure_smoke_assertions.py')
        spec = importlib.util.spec_from_file_location('smoke_assertions', path)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_same_snapshot_returns_id(self):
        value = {'analysisId': 'a', 'facts': {'tasks': [{'status': 'DEAD_LETTERED'}]}, 'interpretation': {'likelyCause': 'timeout'}}
        self.assertEqual(self.module.assert_cached_analysis(value, dict(value)), 'a')

    def test_changed_id_rejected(self):
        with self.assertRaises(ValueError):
            self.module.assert_cached_analysis({'analysisId':'a','facts':{}}, {'analysisId':'b','facts':{}})

    def test_changed_facts_rejected(self):
        with self.assertRaises(ValueError):
            self.module.assert_cached_analysis({'analysisId':'a','facts':{'attempts':3}}, {'analysisId':'a','facts':{'attempts':4}})

    def test_missing_or_invalid_fields_rejected(self):
        for bad in (None, [], {}, {'analysisId':'','facts':{}}, {'analysisId':'a','facts':None}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.module.assert_cached_analysis(bad, bad)

if __name__ == '__main__':
    unittest.main()
