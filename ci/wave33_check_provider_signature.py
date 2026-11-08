#!/usr/bin/env python3
"""Fail-closed exact JUnit identity and no-skips guard for Wave33 HMAC."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json,sys

cls="com.revature.repository.jdbc.Wave33SyntheticProviderSignatureTest"
method="signedProcessorReceiptRejectsTamperingWrongKeyAndDomainConfusion"
path=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not path.is_file():
    raise SystemExit("NO-GO: Wave33 provider signature test JUnit report missing")
actual=[]
for test in ET.parse(path).getroot().iter("testcase"):
    outcome="PASS" if all(test.find(k) is None for k in ("failure","error","skipped")) else "NOT_PASS"
    actual.append((test.get("classname"),test.get("name"),outcome))
if actual!=[(cls,method,"PASS")]:
    raise SystemExit("NO-GO: Wave33 provider signature negative test did not execute PASS: "+str(actual))
target=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave33-evidence/receipt-signature.json")
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(json.dumps({
 "status":"PASS_TEST_ONLY",
 "test":cls+"."+method,
 "assertions":"HMAC verified; altered request, transaction, customer, contract, amount, status, unknown key, fake signature and invalid delimiters rejected",
 "production_provider_signature_verified":False,
 "production_key_custody_implemented":False,
 "physical_pg16_recovery":"separate wave33 physical lab; do not conflate unit test with live provider"
},indent=2)+"\n",encoding="utf-8")
print("Wave33: signed synthetic receipt JUnit negative suite PASS")
