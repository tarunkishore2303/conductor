"""Run the opt-in failure-analysis demo against an already started local stack."""
import argparse
import json
import time
from urllib.request import Request, urlopen

from failure_smoke_assertions import assert_cached_analysis


def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, headers={'Content-Type': 'application/json'})
    with urlopen(req, timeout=150) as response:
        return json.load(response)


def run(base, name):
    job = request(base, '/api/v1/jobs', {
        'name': 'Failure smoke ' + name, 'failurePolicy': 'FAIL_FAST',
        'tasks': [{'taskId': 'demo', 'name': name, 'maxRetries': 2, 'dependsOn': []}]})
    job_id = job['id']
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        job = request(base, '/api/v1/jobs/' + job_id)
        if job['status'] == 'FAILED':
            break
        time.sleep(1)
    else:
        raise RuntimeError('Demo did not fail; enable DEMO_FAILURES_ENABLED for workers')
    path = '/api/v1/ai/runs/' + job_id
    analysis = request(base, path + '/analyze-failure', {})
    cached = request(base, path + '/analyze-failure', {})
    assert_cached_analysis(analysis, cached)
    facts = analysis['facts']['tasks']
    if len(facts) != 1 or facts[0]['status'] != 'DEAD_LETTERED':
        raise AssertionError('Expected one dead-lettered task')
    attempts = facts[0]['attempts']
    if len(attempts) != 3 or [a['attemptNumber'] for a in attempts] != [0, 1, 2]:
        raise AssertionError('Expected initial attempt plus two retries')
    if not facts[0]['deadLetteredAt'] or not all(a['errorMessage'] for a in attempts):
        raise AssertionError('Missing persisted failure evidence')
    print(json.dumps(analysis, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--task', choices=['demoConnectionTimeout', 'demoDownstreamTimeout', 'demoInvalidJson'],
                        default='demoConnectionTimeout')
    arguments = parser.parse_args()
    run(arguments.base_url.rstrip('/'), arguments.task)
