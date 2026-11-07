#!/usr/bin/env python3
"""Validate exactly two real PostgreSQL16 contention regression identities; no skips."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json
import sys

REPORT=Path("target/surefire-reports")/(
    "TEST-com.revature.repository.jdbc.PostgresqlAdminContentionTest.xml")
REQUIRED={
    "actorLockFirstOrdersAuthorizedWriteBeforeWaitingRevocation",
    "competingAdministratorsCannotBothDemoteTheFinalAdministrators",
}
if not REPORT.is_file():
    raise SystemExit("NO-GO: missing PostgreSQL16 contention suite")
seen={}
for tc in ET.parse(REPORT).getroot().iter("testcase"):
    key=tc.attrib.get("name","")
    if not key or key in seen:
        raise SystemExit("NO-GO: missing/duplicate test identity")
    seen[key]=("FAIL" if any(tc.find(x) is not None
                              for x in ("failure","error","skipped")) else "PASS")
if set(seen)!=REQUIRED or any(v!="PASS" for v in seen.values()):
    raise SystemExit("NO-GO: actual PG16 tests missing or not PASS: "+str(seen))
out=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave19-evidence/pg16-contention.json")
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps({
    "status":"PASS",
    "real_postgresql16_cases":2,
    "methods":sorted(seen),
    "limitations":[
        "No replica failover",
        "No production database grants validation",
        "No guarantee of deadlock freedom under all schedules"
    ]
},indent=2)+"\n",encoding="utf-8")
print("Wave19: both PostgreSQL16 contention methods passed without skips")
