"""Scan deliverable text for actual .env secret values without printing them."""
import hashlib
import json
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
secrets = {}
for line in (root / '.env').read_text().splitlines():
    if '=' in line:
        key, value = line.split('=', 1)
        if key in ('API_KEY', 'DB_PASSWORD'):
            secrets[key] = value.strip().strip('\"\'').encode()
paths = subprocess.check_output(['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
findings = []
checked = 0
for name in sorted(set(filter(None, paths))):
    path = root / name
    if not path.is_file() or path.suffix in ('.png', '.jar'):
        continue
    checked += 1
    data = path.read_bytes()
    for kind, secret in secrets.items():
        if secret and secret in data:
            findings.append({'file': name, 'secret_kind': kind})
protected = json.loads((root / '.runtime/v1.1-protected.json').read_text())
unchanged = hashlib.sha256((root / '.env').read_bytes()).hexdigest() == protected['env_sha256']
private_unchanged = (root / '数据库环境.rtf').exists() == protected['private_file_present']
result = {'checked_files': checked, 'secret_matches': findings, 'env_unchanged': unchanged,
          'private_file_presence_unchanged': private_unchanged,
          'result': 'PASS' if not findings and unchanged and private_unchanged else 'FAIL'}
(root / 'reports/evidence/v1.1/secret-scan.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result))
assert result['result'] == 'PASS'
