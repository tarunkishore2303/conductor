import importlib.util
import unittest
from pathlib import Path


class IncidentEvidenceTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('incident_evidence', Path('scripts/incident_smoke_assertions.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def response(self):
        return {'threshold': 0.93, 'matches': [{'incidentId': 'i1', 'jobId': 'previous',
                'taskId': 't1', 'analysisId': 'a1', 'similarity': 0.95}]}

    def check(self, response):
        return self.module.assert_incident_evidence(response, 'current', 'previous', 3)

    def test_real_previous_match_returns_ids(self):
        self.assertEqual(self.check(self.response()), {'i1'})

    def test_current_run_cannot_be_evidence(self):
        response = self.response()
        response['matches'][0]['jobId'] = 'current'
        with self.assertRaises(ValueError):
            self.check(response)

    def test_prior_match_required(self):
        with self.assertRaises(ValueError):
            self.check({'threshold': 0.93, 'matches': []})

    def test_low_nonfinite_or_boolean_score_rejected(self):
        for score in (0.9, float('nan'), float('inf'), True, '0.95'):
            response = self.response()
            response['matches'][0]['similarity'] = score
            with self.subTest(score=score), self.assertRaises(ValueError):
                self.check(response)

    def test_reference_fields_required(self):
        for key in ('incidentId', 'jobId', 'taskId', 'analysisId'):
            response = self.response()
            response['matches'][0][key] = ''
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(response)

    def test_duplicate_or_excess_matches_rejected(self):
        response = self.response()
        response['matches'] *= 4
        with self.assertRaises(ValueError):
            self.check(response)

    def test_other_historical_jobs_are_allowed(self):
        response = self.response()
        response['matches'].append({'incidentId': 'i2', 'jobId': 'other',
                'taskId': 't2', 'analysisId': 'a2', 'similarity': 0.94})
        self.assertEqual(self.check(response), {'i1', 'i2'})

    def test_invalid_threshold_topk_or_missing_score_rejected(self):
        for threshold in (True, float('nan'), float('inf'), '0.93'):
            response = self.response()
            response['threshold'] = threshold
            with self.subTest(threshold=threshold), self.assertRaises(ValueError):
                self.check(response)
        for top_k in (True, '3', 0, 6):
            with self.subTest(top_k=top_k), self.assertRaises(ValueError):
                self.module.assert_incident_evidence(self.response(), 'current', 'previous', top_k)
        response = self.response()
        del response['matches'][0]['similarity']
        with self.assertRaises(ValueError):
            self.check(response)
        response['matches'] = response['matches'][:2]
        with self.assertRaises(ValueError):
            self.check(response)


if __name__ == '__main__':
    unittest.main()
