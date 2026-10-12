import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from copilot_smoke_assertions import assert_copilot_evidence

RUN = '00000000-0000-0000-0000-000000000001'
OTHER = '00000000-0000-0000-0000-000000000002'

class CopilotEvidenceTests(unittest.TestCase):
    def response(self):
        return {'readOnly': True, 'answer': 'Run ' + RUN + ' recorded two retries.',
                'evidenceIds': ['E1'], 'evidence': [{'evidenceId': 'E1', 'success': True,
                'references': [{'id': RUN}]}]}

    def test_valid_reference_returned(self):
        self.assertEqual(assert_copilot_evidence(self.response()), {RUN})

    def test_unknown_uuid_rejected(self):
        response = self.response(); response['answer'] = 'Run ' + OTHER
        with self.assertRaises(ValueError): assert_copilot_evidence(response)

    def test_uncited_and_failed_evidence_cannot_authorize_ids(self):
        for change in ('uncited', 'failed'):
            response = self.response()
            if change == 'uncited': response['evidenceIds'] = []
            else: response['evidence'][0]['success'] = False
            with self.assertRaises(ValueError): assert_copilot_evidence(response)

    def test_unknown_or_duplicate_citations_rejected(self):
        for citations in (['E9'], ['E1', 'E1']):
            response = self.response(); response['evidenceIds'] = citations
            with self.assertRaises(ValueError): assert_copilot_evidence(response)

    def test_mutation_refusal_without_evidence_is_valid(self):
        response = {'readOnly': True, 'answer': 'I cannot retry tasks.', 'evidenceIds': [], 'evidence': []}
        self.assertEqual(assert_copilot_evidence(response), set())

    def test_missing_shape_or_nonboolean_readonly_rejected(self):
        for response in (None, {}, {'readOnly': 1}, dict(self.response(), readOnly=False),
                         dict(self.response(), evidenceIds=None), dict(self.response(), answer='')):
            with self.assertRaises(ValueError): assert_copilot_evidence(response)

    def test_case_insensitive_uuid_matching(self):
        response = self.response(); response['answer'] = ('Run ' + RUN).upper()
        self.assertEqual(assert_copilot_evidence(response), {RUN})

    def test_invalid_reference_and_duplicate_evidence_rejected(self):
        response = self.response(); response['evidence'][0]['references'][0]['id'] = 'invalid'
        with self.assertRaises(ValueError): assert_copilot_evidence(response)
        response = self.response(); response['evidence'].append(copy.deepcopy(response['evidence'][0]))
        with self.assertRaises(ValueError): assert_copilot_evidence(response)

if __name__ == '__main__': unittest.main()
