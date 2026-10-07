"""Optional local summary smoke using one complete and one analyzed failed run."""
import argparse
import json
from urllib.request import Request, urlopen


def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=data, headers={'Content-Type': 'application/json'}), timeout=250) as response:
        return json.load(response)


def run(args):
    base = args.base_url.rstrip('/')
    for job_id in [args.complete_job_id, args.failed_job_id]:
        job = request(base, '/api/v1/jobs/' + job_id)
        path = '/api/v1/ai/runs/' + job_id + '/summary'
        summary = request(base, path, {})
        cached = request(base, path, {})
        stored = request(base, path)
        if summary != cached or summary != stored:
            raise AssertionError('Summary was regenerated instead of reusing its immutable row')
        facts = summary['facts']
        if facts['jobId'] != job_id or facts['status'] != job['status']:
            raise AssertionError('Summary does not describe the requested terminal run')
        tasks = job['tasks']
        if facts['taskCount'] != len(tasks):
            raise AssertionError('Task count differs from the normal run API')
        for status, field in [('COMPLETE', 'completedTasks'), ('FAILED', 'failedTasks'),
                              ('DEAD_LETTERED', 'deadLetteredTasks'), ('CANCELLED', 'cancelledTasks')]:
            if facts[field] != sum(task['status'] == status for task in tasks):
                raise AssertionError('Task status counts differ from the normal run API')
        if job_id == args.failed_job_id:
            analysis = request(base, '/api/v1/ai/runs/' + job_id + '/failure-analysis')
            if facts['failureAnalysisId'] != analysis['analysisId']:
                raise AssertionError('Stored failure analysis reference is missing')
            if args.expected_retries is not None and facts['recordedRetries'] != args.expected_retries:
                raise AssertionError('Recorded retries differ from the known demo history')
        print(json.dumps(summary, indent=2), flush=True)
    print('Terminal summary persistence/reuse smoke passed', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--complete-job-id', required=True)
    parser.add_argument('--failed-job-id', required=True)
    parser.add_argument('--expected-retries', type=int)
    run(parser.parse_args())
