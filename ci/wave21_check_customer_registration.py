#!/usr/bin/env python3
"""Require actual JDBC+PG16 customer-only SQL boundary test, without skips."""
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

CLASS = "com.revature.repository.jdbc.PostgresqlCustomerOnlyRegistrationBoundaryTest"
METHOD = "limitedRegistrarCreatesOnlyCustomerAndCannotMutateAccountsDirectly"
file = Path("target/surefire-reports") / ("TEST-" + CLASS + ".xml")
if not file.is_file():
    raise SystemExit("NO-GO: missing expected real PostgreSQL16 registration XML")
testcases = list(ET.parse(file).getroot().iter("testcase"))
actual = []
for testcase in testcases:
    status = "PASS" if all(testcase.find(tag) is None
                            for tag in ("error","failure","skipped")) else "NOT_PASS"
    actual.append((testcase.attrib.get("classname"),testcase.attrib.get("name"),status))
if actual != [(CLASS,METHOD,"PASS")]:
    raise SystemExit("NO-GO: missing/skipped/failed customer registration test: " + str(actual))
out = Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave21-evidence/customer-registration.json")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({
    "status":"PASS",
    "tested":1,
    "method":METHOD,
    "scope":"disposable PostgreSQL16 owner + SELECT/EXECUTE registrar, account SQL DML denied",
    "not_verified":[
        "Production database grant provisioning or deployments",
        "Owner-approved customer password-change capability",
        "Rate limiting and self-registration abuse protection",
        "Full administrative SQL writer isolation"
    ]
},indent=2)+"\n",encoding="utf-8")
print("Wave21: real PostgreSQL customer-only trusted function test PASSED")
