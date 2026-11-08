#!/usr/bin/env python3
"""Fail closed if Wave27 stolen SQL-writer negative regression is absent or skipped."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json,sys

cls="com.revature.repository.jdbc.PostgresqlSignedCredentialIntentIsolationTest"
method="stolenSqlWriterLoginCannotBypassSignedOneTimeCustomerCredentialIntent"
report=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not report.is_file():
    raise SystemExit("NO-GO: Wave27 signed-intent PostgreSQL report absent")
observed=[]
for test in ET.parse(report).getroot().iter("testcase"):
    status="PASS" if all(test.find(t) is None for t in ("failure","error","skipped")) else "NOT_PASS"
    observed.append((test.attrib.get("classname"),test.attrib.get("name"),status))
if observed != [(cls,method,"PASS")]:
    raise SystemExit("NO-GO: Wave27 signed-intent case failed/skipped/absent: "+str(observed))
out=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave27-evidence/signed-intent.json")
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps({
    "status":"PASS","case_identity":cls+"."+method,
    "scope":"actual disposable PostgreSQL16 with owner-only HMAC key; compromised SQL-login simulation has EXECUTE but no SELECT/UPDATE, cannot sign arbitrary intent or replay nonce",
    "not_verified":[
        "production signer key management and rotation",
        "mTLS service boundary integration with new signed writer",
        "compromise of both signing key and DB login",
        "production SECURITY DEFINER migration and ownership",
        "full code and secrets audit"
    ]
},indent=2)+"\n",encoding="utf-8")
print("Wave27: stolen credential SQL principal denied; valid HMAC and replay controls PASS")
