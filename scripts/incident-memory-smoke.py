"""Verify local incident indexing, real historical references, and optional synthesis."""
import argparse
import json
import time
from urllib.request import Request, urlopen

from failure_smoke_assertions import assert_cached_analysis
from incident_smoke_assertions import assert_incident_evidence


def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=data, headers={'Content-Type': 'application/json'}), timeout=190) as response:
        return json.load(response)


def failed_run(base, name):
    job = request(base, '/api/v1/jobs', {'name': 'Incident smoke ' + name,
        'failurePolicy': 'FAIL_FAST', 'tasks': [{'taskId': 'demo', 'name': name,
        'maxRetries': 2, 'dependsOn': []}]})
    print('Created ' + name + ' run ' + job['id'], flush=True)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if request(base, '/api/v1/jobs/' + job['id'])['status'] == 'FAILED':
            return job['id']
        time.sleep(1)
    raise RuntimeError('Run did not fail; enable DEMO_FAILURES_ENABLED for both workers')


def analyze_and_index(base, job_id):
    path = '/api/v1/ai/runs/' + job_id
    analysis = request(base, path + '/analyze-failure', {})
    assert_cached_analysis(analysis, request(base, path + '/analyze-failure', {}))
    index_path = '/api/v1/ai/failure-analyses/' + analysis['analysisId'] + '/index'
    request(base, index_path, {})
    if request(base, index_path, {})['insertedCount'] != 0:
        raise AssertionError('Repeat indexing inserted duplicate incidents')
    print('Indexed analysis ' + analysis['analysisId'], flush=True)
    return analysis


def main(args):
    base = args.base_url.rstrip('/')
    previous = args.previous_run or failed_run(base, 'demoConnectionTimeout')
    analyze_and_index(base, previous)
    current = args.current_run or failed_run(base, 'demoDownstreamTimeout')
    analyze_and_index(base, current)
    unrelated = args.unrelated_run or failed_run(base, 'demoInvalidJson')
    analyze_and_index(base, unrelated)
    path = '/api/v1/ai/runs/' + current + '/similar-incidents'
    evidence = request(base, path)
    incident_ids = assert_incident_evidence(evidence, current, previous)
    if any(match['jobId'] == unrelated for match in evidence['matches']):
        raise AssertionError('Unrelated JSON incident passed the configured threshold')
    for match in evidence['matches']:
        stored = request(base, match['analysisUrl'])
        if stored['jobId'] != match['jobId'] or stored['analysisId'] != match['analysisId']:
            raise AssertionError('Historical reference does not resolve to its stored run')
    if args.synthesize:
        synthesis = request(base, path + '/synthesize', {})
        citations = set(synthesis['synthesis']['citedIncidentIds'])
        if not citations or not citations <= incident_ids:
            raise AssertionError('Synthesis cites evidence outside the retrieved matches')
        print(json.dumps(synthesis, indent=2), flush=True)
    else:
        print(json.dumps(evidence, indent=2), flush=True)
    print(json.dumps({'previousRun': previous, 'currentRun': current, 'unrelatedRun': unrelated}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--previous-run')
    parser.add_argument('--current-run')
    parser.add_argument('--unrelated-run')
    parser.add_argument('--synthesize', action='store_true')
    main(parser.parse_args())
