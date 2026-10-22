"""Read/replay existing v1 data against the running refactored service."""
import json
import pathlib
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
config = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if '=' in line)
baseline = root / '.runtime/v1.1-evidence-backup/e2e'
if not baseline.exists():
    baseline = root / 'reports/evidence/e2e'
original = json.loads((baseline / '0003.json').read_text())
headers = {'X-API-Key': config['API_KEY'], 'Content-Type': 'application/json',
           'Idempotency-Key': original['idempotency_key']}
request = urllib.request.Request('http://127.0.0.1:8080/api/jobs',
                                 data=json.dumps(original['request']).encode(), headers=headers)
with urllib.request.urlopen(request, timeout=15) as response:
    replay = json.load(response)
assert replay == {'id': original['response']['id'], 'replayed': True}
comparisons = []
for artifact in sorted(baseline.glob('[0-9]*.json')):
    old = json.loads(artifact.read_text())
    if old['method'] != 'GET' or not old['path'].startswith('/api/tasks/'):
        continue
    if old['response']['state'] not in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
        continue
    request = urllib.request.Request('http://127.0.0.1:8080' + old['path'], headers=headers)
    with urllib.request.urlopen(request, timeout=15) as response:
        current = json.load(response)
    assert current == old['response'], old['path']
    comparisons.append(old['path'])
result = {'v1_idempotency_replay': replay, 'identical_v1_task_responses': comparisons, 'status': 'PASS'}
(root / 'reports/evidence/v1.1/v1-data-compatibility.json').write_text(json.dumps(result, indent=2))
print('v1 replay and persisted task response comparison PASS:', len(comparisons))
