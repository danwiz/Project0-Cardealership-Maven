#!/usr/bin/env python3
"""W34.24: demonstrate why W34.23 is NOT independently administered.

Disposable only; ALL keys, paths, ports, and money-like amounts are test data.
Successful run means the trust-boundary release gates remain BLOCKED, NOT passed.
"""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import wave34_23_boundary_lab as prior

CASES = {
    'W3424-01': 'same_ci_uid_reads_witness_private_key',
    'W3424-02': 'anonymous_witness_request_accepted',
    'W3424-03': 'shared_admin_can_forge_and_replace_witness_receipt',
    'W3424-04': 'shared_admin_can_mutate_receiver_effect_store',
    'W3424-05': 'unsigned_receiver_ack_can_be_forged_without_delivery',
    'W3424-06': 'unauthenticated_receiver_delivery_accepted',
    'W3424-07': 'shared_primary_operator_can_revoke_signer',
    'W3424-08': 'signature_valid_after_revocation_without_trust_policy',
}

def check(condition, description):
    if not condition:
        raise AssertionError(description)

def main():
    check(os.getenv('CI') == 'true' and os.getenv('W34_DISPOSABLE_ONLY') == 'true',
          'CI/disposable-only safeguards must be explicit')
    sha = os.getenv('EXPECTED_HEAD', '')
    check(re.fullmatch('[a-f0-9]{40}', sha) is not None, 'exact source SHA required')
    observations = []
    def record(identifier, evidence):
        observations.append({
            'case_id': identifier,
            'control': CASES[identifier],
            'test_observation': evidence,
            'control_disposition': 'BLOCKED_DEMONSTRATED',
            'release_gate_passed': False,
            'infrastructure': 'DISPOSABLE_LOCAL_PROCESS_LAB',
            'real_independent_administration_tested': False,
            'production_system_touched': False,
            'source_head': sha,
        })

    with tempfile.TemporaryDirectory(prefix='w3424_disposable_') as tmp:
        runner = prior.Runner(tmp)
        try:
            runner.start('witness')
            runner.start('receiver')
            wp, rp = runner.ports['witness'], runner.ports['receiver']
            witness_dir = Path(tmp) / 'witness'
            receiver_dir = Path(tmp) / 'receiver'
            key_path = witness_dir / 'key.pem'
            with key_path.open('rb') as stream:
                header = stream.read(128)
            check(b'PRIVATE KEY' in header, 'expected same-UID local key access')
            record('W3424-01', 'test_process_reads_witness_private_key_same_os_uid')

            claim = {'claim_id':'CLAIM-DEMO', 'digest':prior.digest('legitimate'),
                     'checkpoint':'CP-DEMO'}
            status, receipt = prior.request(wp, '/attest', claim)
            check(status == 201, 'anonymous caller did not obtain signed receipt')
            pub = prior.request(wp, '/trust')[1]['public_key_pem']
            check(prior.verify(receipt, pub), 'baseline signature invalid')
            record('W3424-02', 'attest_without_authentication_returns_201')

            forged = {'claim_id':claim['claim_id'],
                      'digest':prior.digest('adversarial-claim'),
                      'checkpoint':claim['checkpoint'],
                      'key_id':receipt['receipt']['key_id']}
            signed = subprocess.run(['openssl','dgst','-sha256','-sign',str(key_path)],
                                    input=prior.canonical(forged), capture_output=True, check=True)
            forged_receipt = {'receipt': forged,
                              'signature':base64.b64encode(signed.stdout).decode()}
            check(prior.verify(forged_receipt, pub), 'test signing key did not forge valid receipt')
            with prior.sqlite(witness_dir / 'witness.db') as c:
                c.execute('UPDATE receipts SET payload=?,signature=? WHERE claim_id=?',
                          (json.dumps(forged, sort_keys=True),
                           forged_receipt['signature'],claim['claim_id']))
            status, observed = prior.request(wp, '/receipt?claim_id=CLAIM-DEMO')
            check(status == 200 and observed['receipt']['digest'] == forged['digest']
                  and prior.verify(observed,pub), 'offline DB receipt overwrite failed')
            record('W3424-03', 'same_admin_replaces_witness_record_with_valid_forged_signature')

            msg = {'message_id':'MSG-STORE','digest':prior.digest('receiver-message'),
                   'effect_minor':15}
            check(prior.request(rp,'/deliver',msg)[0] == 201,'expected effect commit')
            initial = prior.request(rp,'/status?message_id=MSG-STORE')[1]
            check(initial['effect_total_minor'] == 15,'unexpected baseline effect')
            with prior.sqlite(receiver_dir / 'receiver.db') as c:
                c.execute('UPDATE effects SET effect_minor=999 WHERE message_id=?',
                          (msg['message_id'],))
            modified = prior.request(rp,'/status?message_id=MSG-STORE')[1]
            check(modified['effect_total_minor'] == 999,'shared admin could not tamper store')
            record('W3424-04','primary_admin_can_modify_receiver_effect_outside_api')

            out = prior.Outbox(Path(tmp)/'primary_outbox.db')
            never_delivered = {'message_id':'MSG-GHOST',
                               'digest':prior.digest('ghost'), 'effect_minor':44}
            out.add(**{'mid':never_delivered['message_id'], 'd':never_delivered['digest'],
                       'effect':never_delivered['effect_minor']})
            generation = out.claim(never_delivered['message_id'],'worker-attacker',100)
            check(generation == 1,'claim failed')
            forged_ack = prior.digest('RECEIVER|'+never_delivered['message_id']+'|'
                                      +never_delivered['digest']+'|'
                                      +str(never_delivered['effect_minor']))
            check(out.ack(never_delivered['message_id'],'worker-attacker',
                          generation,forged_ack),'forged hash-only ACK not accepted')
            check(out.state(never_delivered['message_id'])[0] == 'ACKED','outbox not ACKED')
            ghost = prior.request(rp,'/status?message_id=MSG-GHOST')[1]
            check(ghost['row'] is None,'ghost message unexpectedly delivered')
            record('W3424-05','hash_only_ack_accepted_for_message_never_received')

            anon = {'message_id':'MSG-ANON','digest':prior.digest('anonymous-receiver'),
                    'effect_minor':3}
            check(prior.request(rp,'/deliver',anon)[0] == 201,
                  'expected receiver to accept unauthenticated delivery')
            record('W3424-06','deliver_without_transport_identity_commits_effect')

            check(prior.request(wp,'/revoke', {'key_id':'W3423_TEST_P256_1'})[0] == 403,
                  'unauthorized revocation unexpectedly permitted')
            check(prior.request(wp,'/revoke', {'key_id':'W3423_TEST_P256_1'},
                                {'X-Test-Admin':runner.auth})[0] == 200,
                  'shared runner admin token did not allow revocation')
            check(prior.request(wp,'/attest',{'claim_id':'AFTER-REVOKE',
                  'digest':prior.digest('after'),'checkpoint':'CP-NEXT'})[0] == 423,
                  'revoked signer still signing')
            record('W3424-07','single_local_test_operator_controls_revocation_token')

            trust = prior.request(wp,'/trust')[1]
            check(trust['keys'][0]['revoked'] is True,'revocation state missing')
            check(prior.verify(receipt,pub) is True,'historical signature invalid')
            record('W3424-08','cryptographic_verify_true_even_after_signer_revocation')
        finally:
            runner.close()

    check(set(CASES) == {x['case_id'] for x in observations}, 'expected eight observations')
    evidence = {
        'schema':'W3424-ADVERSARIAL-NONINDEPENDENCE-v1',
        'source_head': sha,
        'observations': observations,
        'negative_controls_observed': len(observations),
        'genuine_independent_controls_passed': 0,
        'W3422_09_external_witness_status':'NOT_RUN',
        'W3422_10_external_receiver_status':'NOT_RUN',
        'independent_administrative_custody':False,
        'real_financial_provider_tested':False,
        'production_changes':False,
        'release_disposition':'NO-GO',
    }
    output = Path('wave34-24-evidence')
    output.mkdir(parents=True,exist_ok=True)
    (output/'w3424-negative-controls.json').write_text(
        json.dumps(evidence, sort_keys=True, indent=2)+'\n',encoding='utf-8')
    print(json.dumps({
        'source_head':sha, 'negative_controls_observed':len(observations),
        'independent_controls_passed':0,'status':'EXPECTED_BOUNDARY_FAILURES_CONFIRMED',
        'release_disposition':'NO-GO'}, sort_keys=True))

if __name__ == '__main__':
    main()
