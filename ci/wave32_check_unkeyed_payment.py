#!/usr/bin/env python3
"""Fail closed if Wave32 unkeyed payment replay characterization is missing."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json,sys

cls="com.revature.repository.jdbc.JdbcPaymentUnkeyedRetryCharacterizationTest"
method="sameAmountRetryCreatesSecondLocalPaymentWithoutExternalIdempotencyKey"
file=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not file.is_file():
    raise SystemExit("NO-GO: Wave32 payment gap characterization JUnit report absent")
cases=[]
for item in ET.parse(file).getroot().iter("testcase"):
    status="PASS" if all(item.find(k) is None for k in ("failure","error","skipped")) else "NOT_PASS"
    cases.append((item.attrib.get("classname"),item.attrib.get("name"),status))
if cases!=[(cls,method,"PASS")]:
    raise SystemExit("NO-GO: unkeyed payment behavior not verified: "+str(cases))
dest=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave32-evidence/unkeyed-payment.json")
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps({
 "status":"PASS_CHARACTERIZATION_ONLY",
 "junit_identity":cls+"."+method,
 "observed":"Two same-amount unkeyed API calls create two distinct local transactions",
 "risk":"Existing API cannot distinguish an intended second payment from a retry of one processor charge",
 "production_fix_implemented":False,
 "reconciliation_candidate":"Wave32 independent PG16 provider receipt + unique local posting register test-only"
},indent=2)+"\n",encoding="utf-8")
print("Wave32: unkeyed JDBC payment retry gap characterized; not remediated in product runtime")
