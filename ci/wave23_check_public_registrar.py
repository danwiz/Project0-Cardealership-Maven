#!/usr/bin/env python3
"""Reject missing, skipped or failed Wave23 real PostgreSQL16 hash-blind registrar test."""
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET
cls="com.revature.repository.jdbc.PostgresqlPublicRegistrationIsolationTest"
case="registrarCanCreateCustomersWithoutAnyAccountTableReadOrWriteGrants"
xmlfile=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not xmlfile.is_file():
    raise SystemExit("NO-GO: Wave23 registrar security test report missing")
actual=[]
for item in ET.parse(xmlfile).getroot().iter("testcase"):
    status="PASS" if all(item.find(n) is None for n in ("failure","error","skipped")) else "NOT_PASS"
    actual.append((item.attrib.get("classname"),item.attrib.get("name"),status))
if actual != [(cls,case,"PASS")]:
    raise SystemExit("NO-GO: Wave23 security test not PASS: "+str(actual))
dest=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave23-evidence/public-registrar.json")
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps({
    "status":"PASS","methods":1,"test":case,
    "scope":"disposable PostgreSQL16; registrar USAGE + function EXECUTE + schema_history SELECT only; account SELECT/DML explicitly denied",
    "not_verified":["production SQL role migration and secrets isolation",
                    "complete authentication service redesign","abuse throttling"]
},indent=2)+"\n",encoding="utf-8")
print("Wave23: real PG16 hash-blind registrar test PASSED")
