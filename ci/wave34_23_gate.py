#!/usr/bin/env python3
"""Wave34.23 exact-source custody and limited process-boundary evidence gate."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PARENT = 'c6f3fea85a1a3c138f49ccccdfd8a81e7b200ecd'
ALLOWED = {
    '.github/workflows/wave34-23-process-boundary-lab.yml',
    'ci/wave34_23_boundary_lab.py',
    'ci/wave34_23_gate.py',
    'docs/audit/wave34/Wave34_23_Process_Boundary_Review.md',
}
FROZEN = [
    'pom.xml',
    'src/test/java/com/revature/repository/jdbc/Wave3422PgFixture.java',
    'src/test/java/com/revature/repository/jdbc/Wave3422DeterministicPgInterleavingTest.java',
    'src/test/java/com/revature/repository/jdbc/Wave34PostgresqlBalancedGlReconciliationTest.java',
    'src/test/java/com/revature/repository/jdbc/Wave34PostgresqlSettlementBatchApprovalTest.java',
    'docs/audit/wave34/wave34_15_DISPOSABLE_balanced_gl.sql',
    'docs/audit/wave34/wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql',
    'docs/audit/wave34/wave34_19_DISPOSABLE_witness_revocation_outbox.sql',
    'docs/audit/wave34/Wave34_19_Witness_Outbox_Release_Gate_Register.json',
]
CASES = ['W3423-%02d' % i for i in range(1, 13)]

def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()

def source():
    head=git('rev-parse','HEAD')
    if not re.fullmatch(r'[0-9a-f]{40}',head) or os.getenv('EXPECTED_HEAD')!=head:
        raise ValueError('exact immutable GitHub source SHA required')
    return head

def static():
    sha=source()
    subprocess.check_call(['git','merge-base','--is-ancestor',PARENT,sha])
    changed=set(filter(None,git('diff','--name-only',PARENT,sha).splitlines()))
    if changed!=ALLOWED:
        raise ValueError('W34.23 source custody mismatch: '+repr(changed^ALLOWED))
    for path in ALLOWED:
        if not Path(path).is_file():raise ValueError('missing '+path)
    for path in FROZEN:
        if git('rev-parse',PARENT+':'+path)!=git('rev-parse',sha+':'+path):
            raise ValueError('frozen history changed: '+path)
    code=Path('ci/wave34_23_boundary_lab.py').read_text()
    if 'independent_administration' not in code or "real_independent_administration':False" not in code:
        raise ValueError('must declare independence not proven')
    doc=Path('docs/audit/wave34/Wave34_23_Process_Boundary_Review.md').read_text()
    for x in ('NO-GO','NOT INDEPENDENT','NOT RUN'):
        if x not in doc:raise ValueError('missing audit caveat '+x)
    subprocess.check_call([sys.executable,'-m','py_compile','ci/wave34_23_boundary_lab.py'])
    print(json.dumps({'scope':'REVIEW_ONLY','status':'PASS','sha':sha,
                      'files':sorted(changed),'production_authorized':False,
                      'genuine_independent_custody':False,
                      'release_disposition':'NO-GO'},sort_keys=True))

def evidence():
    sha=source()
    p=Path('wave34-23-evidence/w3423-results.json')
    obj=json.loads(p.read_text())
    if obj.get('source_sha')!=sha or obj.get('passed')!=12 or obj.get('failed')!=0:
        raise ValueError('incorrect source/count in process-boundary evidence')
    if [x.get('case_id') for x in obj.get('cases',[])]!=CASES:
        raise ValueError('incomplete 12-case acceptance register')
    if any(x.get('status')!='PASS' or x.get('source_sha')!=sha
            or x.get('independent_administration') is not False
            or x.get('external_provider_verified') is not False
            or x.get('production_authorized') is not False
            for x in obj['cases']):
        raise ValueError('case verification or custody claim invalid')
    for flag in ('real_independent_administration',
                 'real_financial_provider_or_effects','production_authorized'):
        if obj.get(flag) is not False:
            raise ValueError('unsupported production claim '+flag)
    if obj.get('release_disposition')!='NO-GO':
        raise ValueError('release gate illegally changed')
    print(json.dumps({'case_count':12,'status':'PASS','sha':sha,
                      'process_isolation_only':True,
                      'real_independent_administration':False,
                      'release_disposition':'NO-GO'},sort_keys=True))

if __name__=='__main__':
    try:
        {'static':static,'evidence':evidence}[sys.argv[1]]()
    except Exception as exc:
        print('W34.23 GATE HOLD: '+str(exc),file=sys.stderr)
        sys.exit(1)
