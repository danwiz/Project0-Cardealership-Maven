#!/usr/bin/env python3
"""Require Wave22's real PostgreSQL16 customer password-rotation regression."""
from pathlib import Path
import json
import sys
from xml.etree import ElementTree as ET

name="com.revature.repository.jdbc.PostgresqlCustomerCredentialRotationTest"
method="currentPasswordRotatesCredentialAndSessionWhilePriorSnapshotExpires"
path=Path("target/surefire-reports")/("TEST-"+name+".xml")
if not path.is_file():
    raise SystemExit("NO-GO: missing PostgreSQL customer password-rotation XML")
cases=[]
for item in ET.parse(path).getroot().iter("testcase"):
    status="PASS" if all(item.find(x) is None
                         for x in ("failure","error","skipped")) else "NOT_PASS"
    cases.append((item.attrib.get("classname"),item.attrib.get("name"),status))
if cases != [(name,method,"PASS")]:
    raise SystemExit("NO-GO: missing/failed/skipped PostgreSQL password rotation: "+str(cases))
target=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave22-evidence/credential-rotation.json")
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(json.dumps({
    "status":"PASS",
    "test_cases":1,
    "class":name,
    "method":method,
    "scope":"real disposable PG16 customer current-password transaction, revision rotation",
    "not_proved":[
        "production DB principal isolation",
        "public API rate limiting and CSRF",
        "complete plugin/supply chain review",
        "all credential race schedules"
    ]
},indent=2)+"\n",encoding="utf-8")
print("Wave22: PostgreSQL16 customer credential rotation test PASS")
