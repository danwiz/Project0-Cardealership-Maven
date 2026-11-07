#!/usr/bin/env python3
"""Require the real PostgreSQL16 three-principal isolation case to run and pass."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json,sys

cls="com.revature.repository.jdbc.PostgresqlThreePrincipalAuthenticationIsolationTest"
method="registrarCannotReadHashesPrivateAuthenticatorCannotWriteAndCredentialWriterCannotEscalate"
file=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not file.is_file():
    raise SystemExit("NO-GO: missing Wave24 three-principal PG16 JUnit XML")
cases=[]
for case in ET.parse(file).getroot().iter("testcase"):
    outcome="PASS" if all(case.find(x) is None
                          for x in ("failure","error","skipped")) else "NOT_PASS"
    cases.append((case.attrib.get("classname"),case.attrib.get("name"),outcome))
if cases != [(cls,method,"PASS")]:
    raise SystemExit("NO-GO: unverified Wave24 PostgreSQL principal isolation "+str(cases))
out=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave24-evidence/roles.json")
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps({
    "status":"PASS","tested":1,"class":cls,"method":method,
    "scope":"three distinct disposable PG16 SQL identities: registrar EXECUTE only, auth SELECT only, credential SELECT plus UPDATE(encoded_credential) only",
    "remaining":["production process/secret separation unverified",
                 "credential SQL principal may still directly change a hash if compromised",
                 "production GRANT/REVOKE and service composition not deployed",
                 "public registration enumeration/rate limit policy not implemented"]
},indent=2)+"\n",encoding="utf-8")
print("Wave24 three-principal PostgreSQL16 isolation verified")
