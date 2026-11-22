#!/usr/bin/env python3
"""Wave34.24 exact-head custody and adversarial evidence gate; RELEASE NO-GO."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PARENT = 'cfd7eb0ce528ef505407c148c67eb23bdc37d7d4'
ALLOWED = {
    '.github/workflows/wave34-24-adversarial-trust-review.yml',
    'ci/wave34_24_adversarial_boundary_probe.py',
    'ci/wave34_24_gate.py',
    'docs/audit/wave34/Wave34_24_Independent_Trust_Gate_Register.md',
    'docs/audit/wave34/Wave34_24_QA_AppSec_Adjudication_Dossier.md',
}
FROZEN = [
    'pom.xml',
    'ci/wave34_23_boundary_lab.py',
    'ci/wave34_23_gate.py',
    'src/test/java/com/revature/repository/jdbc/Wave3422PgFixture.java',
    'src/test/java/com/revature/repository/jdbc/Wave3422DeterministicPgInterleavingTest.java',
    'src/test/java/com/revature/repository/jdbc/Wave34PostgresqlSettlementBatchApprovalTest.java',
    'src/test/java/com/revature/repository/jdbc/Wave34PostgresqlBalancedGlReconciliationTest.java',
    'docs/audit/wave34/wave34_15_DISPOSABLE_balanced_gl.sql',
    'docs/audit/wave34/wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql',
    'docs/audit/wave34/wave34_19_DISPOSABLE_witness_revocation_outbox.sql',
]
CASES = ['W3424-%02d' % n for n in range(1, 9)]

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def head():
    h = git('rev-parse', 'HEAD')
    if not re.fullmatch(r'[a-f0-9]{40}', h) or os.environ.get('EXPECTED_HEAD') != h:
        raise ValueError('exact immutable PR head required')
    return h

def static():
    h = head()
    subprocess.check_call(['git','merge-base','--is-ancestor',PARENT,h])
    changed = set(filter(None, git('diff','--name-only',PARENT,h).splitlines()))
    if changed != ALLOWED:
        raise ValueError('only five review-only files permitted: ' + repr(changed ^ ALLOWED))
    for f in ALLOWED:
        if not Path(f).is_file():
            raise ValueError('missing review file: ' + f)
    for f in FROZEN:
        if git('rev-parse',PARENT+':'+f) != git('rev-parse',h+':'+f):
            raise ValueError('FROZEN historical or production file changed: ' + f)
    doc = Path('docs/audit/wave34/Wave34_24_Independent_Trust_Gate_Register.md').read_text()
    for term in ['NO-GO','NOT RUN','W3422-09','W3422-10','INDEPENDENT','BLOCKED']:
        if term not in doc:
            raise ValueError('required release language missing: '+term)
    for f in ['ci/wave34_24_adversarial_boundary_probe.py','ci/wave34_24_gate.py']:
        subprocess.check_call([sys.executable,'-m','py_compile',f])
    print(json.dumps({'source_sha':h,'source_custody':'PASS','added_files':sorted(changed),
          'historical_fixtures_modified':False,'operational_independence_proven':False,
          'production_authorized':False,'release_disposition':'NO-GO'},sort_keys=True))

def evidence():
    h = head()
    obj = json.loads(Path('wave34-24-evidence/w3424-negative-controls.json').read_text())
    if (obj.get('source_head') != h or obj.get('negative_controls_observed') != 8
            or obj.get('genuine_independent_controls_passed') != 0
            or obj.get('independent_administrative_custody') is not False
            or obj.get('real_financial_provider_tested') is not False
            or obj.get('production_changes') is not False
            or obj.get('release_disposition') != 'NO-GO'
            or obj.get('W3422_09_external_witness_status') != 'NOT_RUN'
            or obj.get('W3422_10_external_receiver_status') != 'NOT_RUN'):
        raise ValueError('invalid independent trust boundary / NO-GO evidence')
    rows = obj.get('observations', [])
    if len(rows) != 8 or [row.get('case_id') for row in rows] != CASES:
        raise ValueError('missing, unordered or duplicate adversarial case')
    for row in rows:
        if (row.get('control_disposition') != 'BLOCKED_DEMONSTRATED'
            or row.get('release_gate_passed') is not False
            or row.get('real_independent_administration_tested') is not False
            or row.get('production_system_touched') is not False
            or row.get('source_head') != h):
            raise ValueError('control failure mislabeled as accepted release proof')
    print(json.dumps({'source_sha':h,'adversarial_observations':len(rows),
        'independent_controls_verified':0,'operational_gates':'BLOCKED',
        'production_authorized':False,'release_disposition':'NO-GO'},sort_keys=True))

if __name__=='__main__':
    try:
        {'static':static,'evidence':evidence}[sys.argv[1]]()
    except Exception as exc:
        print('W34.24 GATE HOLD: '+str(exc),file=sys.stderr)
        sys.exit(1)
