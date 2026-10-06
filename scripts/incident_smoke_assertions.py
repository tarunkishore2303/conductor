"""Independent checks for real, sufficiently similar historical evidence."""
import math


def assert_incident_evidence(response, current_job_id, expected_previous_job_id, top_k=3):
    if type(top_k) is not int or not 1 <= top_k <= 5 or not isinstance(response, dict):
        raise ValueError('Invalid retrieval response or top-K')
    threshold = response.get('threshold')
    if type(threshold) not in (int, float) or not math.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ValueError('Invalid similarity threshold')
    matches = response.get('matches')
    if not isinstance(matches, list) or len(matches) > top_k:
        raise ValueError('Invalid match count')
    incident_ids = set()
    found_previous = False
    for match in matches:
        if not isinstance(match, dict) or any(not isinstance(match.get(key), str) or not match[key]
                for key in ('incidentId', 'jobId', 'taskId', 'analysisId')):
            raise ValueError('Missing incident reference')
        score = match.get('similarity')
        if type(score) not in (int, float) or not math.isfinite(score) or not threshold <= score <= 1:
            raise ValueError('Invalid or weak similarity score')
        if match['jobId'] == current_job_id or match['incidentId'] in incident_ids:
            raise ValueError('Current or duplicate incident included')
        incident_ids.add(match['incidentId'])
        found_previous |= match['jobId'] == expected_previous_job_id
    if not found_previous:
        raise ValueError('Expected historical run was not retrieved')
    return incident_ids
