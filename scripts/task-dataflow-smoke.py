"""Verify bounded direct-dependency dataflow against a freshly restarted fixed fixture; no AI calls."""
import argparse
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('capability_smoke', Path(__file__).with_name('task-capabilities-smoke.py'))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
request, task = helpers.request, helpers.task


def execute(base, name, tasks):
    workflow = request(base, '/api/v1/workflows', {'name': name, 'tasks': tasks})
    job = request(base, '/api/v1/workflows/' + workflow['id'] + '/jobs', {'name': name})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        job = request(base, '/api/v1/jobs/' + job['id'])
        if job['status'] in ('COMPLETE', 'FAILED') and all(t['status'] != 'RUNNING' for t in job['tasks']):
            return {'workflowId': workflow['id'], 'jobId': job['id'], 'status': job['status'],
                    'tasks': [{'taskId': t['id'], 'name': t['name'], 'type': t['type'],
                               'status': t['status'], 'retryCount': t['retryCount']} for t in job['tasks']]}
        time.sleep(.2)
    raise AssertionError('Dataflow workflow did not settle within 90 seconds: ' + job['id'])


def pipeline(target, path='/orders', capture=True, retries=0):
    return [task('fetch', 'HTTP_REQUEST', {'method': 'GET', 'url': target + path,
                 'timeoutMillis': 2000, 'captureResponseBody': capture}, retries=retries),
            task('select', 'TRANSFORM', {'operation': 'JSON_SELECT', 'inputTaskId': 'fetch',
                 'fields': ['id', 'email']}, ['fetch']),
            task('notify', 'HTTP_REQUEST', {'method': 'POST', 'url': target + '/notify',
                 'timeoutMillis': 2000, 'contentType': 'application/json', 'bodyTaskId': 'select'}, ['select'])]


def run(args):
    base, target, fixture = args.base_url.rstrip('/'), args.demo_url.rstrip('/'), args.fixture_url.rstrip('/')
    results = {}
    results['legacyNoop'] = execute(base, 'Dataflow smoke legacy NOOP',
            [{'taskId': 'legacy', 'name': 'legacy', 'dependsOn': [], 'maxRetries': 0}])
    results['staticTransform'] = execute(base, 'Dataflow smoke static transform',
            [task('static', 'TRANSFORM', {'operation': 'JSON_SELECT', 'input': '{"id":42}', 'fields': ['id']})])
    results['captureDisabled'] = execute(base, 'Dataflow smoke default discard',
            [task('discard', 'HTTP_REQUEST', {'method': 'GET', 'url': target + '/orders', 'timeoutMillis': 2000})])
    for name in ('legacyNoop', 'staticTransform', 'captureDisabled'):
        assert results[name]['status'] == 'COMPLETE', results[name]

    expected = {'id': 42, 'email': 'demo@example.test'}
    results['pipeline'] = execute(base, 'Dataflow smoke captured JSON pipeline', pipeline(target))
    assert results['pipeline']['status'] == 'COMPLETE', results['pipeline']
    assert request(fixture, '/last-notification') == expected
    results['pipeline']['notification'] = expected

    results['retrySuccess'] = execute(base, 'Dataflow smoke successful retry output',
            pipeline(target, '/retry-orders', retries=2))
    assert results['retrySuccess']['status'] == 'COMPLETE', results['retrySuccess']
    fetch = next(t for t in results['retrySuccess']['tasks'] if t['name'] == 'fetch')
    assert fetch['retryCount'] == 2, 'Restart task-demo before running: /retry-orders must begin at attempt zero'
    assert request(fixture, '/last-notification') == expected

    results['failedUpstream'] = execute(base, 'Dataflow smoke failed upstream blocks consumers',
            pipeline(target, '/fail', retries=2))
    failed = {t['name']: t for t in results['failedUpstream']['tasks']}
    assert results['failedUpstream']['status'] == 'FAILED', results['failedUpstream']
    assert failed['fetch']['status'] == 'DEAD_LETTERED' and failed['fetch']['retryCount'] == 2
    assert all(failed[name]['status'] in ('PENDING', 'CANCELLED') and failed[name]['retryCount'] == 0
               for name in ('select', 'notify')), failed

    results['missingOutput'] = execute(base, 'Dataflow smoke disabled capture cannot feed consumers',
            pipeline(target, capture=False))
    missing = {t['name']: t for t in results['missingOutput']['tasks']}
    assert results['missingOutput']['status'] == 'FAILED', results['missingOutput']
    assert missing['fetch']['status'] == 'COMPLETE' and missing['select']['status'] == 'DEAD_LETTERED'
    assert missing['notify']['status'] in ('PENDING', 'CANCELLED')
    print(json.dumps(results, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--demo-url', default='http://task-demo:8080')
    parser.add_argument('--fixture-url', default='http://localhost:18080')
    run(parser.parse_args())
