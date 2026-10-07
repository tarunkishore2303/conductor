"""Exercise local read-only copilot using an already analyzed and indexed failed run."""
import argparse
import json
from urllib.request import Request, urlopen
from copilot_smoke_assertions import assert_copilot_evidence

def request(base, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=data, headers={'Content-Type': 'application/json'}), timeout=190) as response:
        return json.load(response)

def run(args):
    base = args.base_url.rstrip('/')
    analysis_path = '/api/v1/ai/runs/' + args.job_id + '/failure-analysis'
    analysis = request(base, analysis_path)
    before = request(base, '/api/v1/jobs/' + args.job_id)
    for question, expected in [('Why did this run fail?', 'getFailureAnalysis'),
                               ('Have we seen this before?', 'getSimilarIncidents'),
                               ('How many retries happened?', 'getRun'),
                               ('Retry this task.', None)]:
        result = request(base, '/api/v1/ai/copilot/query', {'question': question, 'jobId': args.job_id})
        assert_copilot_evidence(result)
        if expected and expected not in result['toolsUsed']:
            raise AssertionError('Expected read tool ' + expected)
        if expected is None and (result['toolsUsed'] or 'read-only' not in result['answer'].lower()):
            raise AssertionError('Mutation request was not refused')
        print(json.dumps({'question': question, 'response': result}, indent=2), flush=True)
    if request(base, analysis_path)['analysisId'] != analysis['analysisId']:
        raise AssertionError('Copilot unexpectedly regenerated failure analysis')
    if request(base, '/api/v1/jobs/' + args.job_id) != before:
        raise AssertionError('Copilot changed execution state')
    print('Read-only copilot smoke passed', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--job-id', required=True)
    run(parser.parse_args())
