"""Verify real handlers against the opt-in fixed demo server, with no live AI calls."""
import argparse
import json
import time
from urllib.request import Request, urlopen


def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=data, headers={'Content-Type': 'application/json'}), timeout=30) as response:
        return json.load(response)


def task(identifier, kind='NOOP', config=None, dependencies=None, retries=0):
    return {'taskId': identifier, 'name': identifier, 'type': kind, 'config': config or {},
            'dependsOn': dependencies or [], 'maxRetries': retries}


def run_workflow(base, name, tasks):
    workflow = request(base, '/api/v1/workflows', {'name': name, 'tasks': tasks})
    job = request(base, '/api/v1/workflows/' + workflow['id'] + '/jobs', {'name': name})
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        job = request(base, '/api/v1/jobs/' + job['id'])
        if job['status'] in ('COMPLETE', 'FAILED') and all(t['status'] != 'RUNNING' for t in job['tasks']):
            return workflow, job
        time.sleep(.2)
    raise AssertionError('Workflow did not settle before the smoke deadline')


def run(args):
    base, target = args.base_url.rstrip('/'), args.demo_url.rstrip('/')
    capabilities = request(base, '/api/v1/task-capabilities')
    assert {item['type'] for item in capabilities} == {'NOOP', 'DELAY', 'HTTP_REQUEST', 'TRANSFORM'}
    def http(path):
        return {'method': 'GET', 'url': target + path, 'timeoutMillis': 2000}
    tasks = [task('checkOrders', 'HTTP_REQUEST', http('/orders')),
             task('selectStaticJson', 'TRANSFORM', {'operation': 'JSON_SELECT',
                   'input': '{"id":42,"email":"demo@example.test","internal":"omitted"}',
                   'fields': ['id', 'email']}, ['checkOrders']),
             task('briefWait', 'DELAY', {'durationMillis': 1500}, ['selectStaticJson']),
             task('notify', 'HTTP_REQUEST', {'method': 'POST', 'url': target + '/notify',
                   'timeoutMillis': 2000, 'contentType': 'application/json',
                   'body': '{"status":"READY"}'}, ['selectStaticJson'])]
    workflow, mixed = run_workflow(base, 'Real capability smoke: parallel static inputs', tasks)
    assert mixed['status'] == 'COMPLETE' and len(mixed['tasks']) == 4
    assert all(item['status'] == 'COMPLETE' for item in mixed['tasks'])
    assert {item['type'] for item in mixed['tasks']} == {'HTTP_REQUEST', 'TRANSFORM', 'DELAY'}
    failed_workflow, failed = run_workflow(base, 'Real HTTP 500 retry smoke',
                                         [task('realHttpFailure', 'HTTP_REQUEST', http('/fail'), retries=2)])
    assert failed['status'] == 'FAILED'
    assert failed['tasks'][0]['status'] == 'DEAD_LETTERED'
    assert failed['tasks'][0]['retryCount'] == 2
    _, legacy = run_workflow(base, 'Legacy NOOP smoke', [{'taskId': 'old', 'name': 'old', 'dependsOn': [], 'maxRetries': 0}])
    assert legacy['status'] == 'COMPLETE' and legacy['tasks'][0]['type'] == 'NOOP'
    print(json.dumps({'mixedWorkflowId': workflow['id'], 'mixedJobId': mixed['id'],
                     'mixedTasks': mixed['tasks'], 'failedWorkflowId': failed_workflow['id'],
                     'failedJobId': failed['id'], 'failedTask': failed['tasks'][0],
                     'legacyJobId': legacy['id']}, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--demo-url', default='http://task-demo:8080')
    run(parser.parse_args())
