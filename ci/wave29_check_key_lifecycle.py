#!/usr/bin/env python3
"""Require actual disposable PG16 key rotation / expiry / revocation test."""
import sys,json
from pathlib import Path
from xml.etree import ElementTree as ET

cls="com.revature.repository.jdbc.PostgresqlVersionedCredentialKeyLifecycleTest"
method="keyVersionExpiryVerifyOnlyRevocationAndReplayAreEnforcedByPostgres"
p=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not p.is_file():
    raise SystemExit("NO-GO: Wave29 key lifecycle PostgreSQL16 test report missing")
actual=[]
for test in ET.parse(p).getroot().iter("testcase"):
    state="PASS" if all(test.find(t) is None for t in ("failure","error","skipped")) else "NOT_PASS"
    actual.append((test.get("classname"),test.get("name"),state))
if actual != [(cls,method,"PASS")]:
    raise SystemExit("NO-GO: key lifecycle test failed, skipped, absent or duplicated: "+str(actual))
output=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave29-evidence/key-lifecycle.json")
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps({
 "status":"PASS",
 "method":cls+"."+method,
 "scope":"real disposable PG16 key version, issue/expiry TTL, VERIFY_ONLY cutoff, emergency REVOKED, one-time nonce and independent SQL identity",
 "boundaries":["no SQL executor SELECT/UPDATE on accounts or HMAC keys",
              "valid pre-cutover intent permitted under VERIFY_ONLY",
              "post-cutover intent denied",
              "expired/future/unknown-key/revoked/wrong-purpose intent denied",
              "new active key authorizes customer rotation",
              "revoked key immediately fails",
              "old password and stale session invalidated"],
 "not_certified":["production key vault","issuer TLS secret custody",
                  "multi-tenant enforcement","signer-plus-executor compromise",
                  "production migration and DBA/AppSec signoff"]
},indent=2)+"\n",encoding="utf-8")
print("Wave29: real PostgreSQL16 versioned signed-key lifecycle PASS")
