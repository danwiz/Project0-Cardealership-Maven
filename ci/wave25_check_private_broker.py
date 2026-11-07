#!/usr/bin/env python3
"""Require one successful, non-skipped PostgreSQL16 private credential broker regression."""
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

CLASS="com.revature.repository.jdbc.PostgresqlPrivateCredentialBrokerTest"
METHOD="privateBrokerRechecksCustomerProofWhileReaderCannotWriteAndWrongPlaintextFails"
xml=Path("target/surefire-reports")/("TEST-"+CLASS+".xml")
if not xml.is_file():
    raise SystemExit("NO-GO: missing Wave25 PG16 private credential broker test report")
actual=[]
for test in ET.parse(xml).getroot().iter("testcase"):
    outcome="PASS" if all(test.find(x) is None
                          for x in ("failure","error","skipped")) else "NOT_PASS"
    actual.append((test.attrib.get("classname"),test.attrib.get("name"),outcome))
if actual != [(CLASS,METHOD,"PASS")]:
    raise SystemExit("NO-GO: Wave25 test failed, skipped or absent: "+str(actual))
out=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave25-evidence/private-broker.json")
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps({
    "status":"PASS",
    "test_cases":1,
    "test_identity":CLASS+"."+METHOD,
    "scope":"real disposable PostgreSQL16 private verifier and credential-writer principals; current plaintext and persisted revision rechecked; wrong password/role and stale session denied",
    "not_proved":[
        "production process or RPC authentication and mTLS",
        "production secret injection and network isolation",
        "SQL principal compromise cannot directly update an encoded credential",
        "rate limiting and user account recovery",
        "production migration or deployment"
    ]
},indent=2)+"\n",encoding="utf-8")
print("Wave25: private broker PG16 role and credential test PASS")
