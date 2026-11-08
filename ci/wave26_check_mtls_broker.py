#!/usr/bin/env python3
"""Wave26: reject absent/failed/skipped real PostgreSQL16 mTLS broker JUnit."""
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET
cls="com.revature.repository.jdbc.PostgresqlMutualTlsCredentialBrokerTest"
method="isolatedPrivateBrokerDeniesUntrustedClientCertificatesAndRotatesCustomer"
report=Path("target/surefire-reports")/("TEST-"+cls+".xml")
if not report.exists():
    raise SystemExit("NO-GO: missing Wave26 mTLS PostgreSQL test XML")
found=[]
for test in ET.parse(report).getroot().iter("testcase"):
    status="PASS" if all(test.find(t) is None for t in ("failure","error","skipped")) else "NOT_PASS"
    found.append((test.attrib.get("classname"),test.attrib.get("name"),status))
if found != [(cls,method,"PASS")]:
    raise SystemExit("NO-GO: Wave26 mTLS test not PASS: "+str(found))
dest=Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave26-evidence/tls.json")
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps({
    "status":"PASS","named_methods":1,"identity":cls+"."+method,
    "scope":"disposable PG16; one private credential-writer JVM, loopback TLS1.2, separate verifier/writer SQL roles, TLS trust chain plus exact pinned verifier client certificate",
    "negative_proofs":[
        "rogue client certificate trusted by same CA rejected by application pin",
        "missing client certificate cannot rotate credential",
        "wrong current plaintext cannot rotate credential",
        "replay of old current password denied after rotation",
        "private SQL reader cannot issue direct account UPDATE"
    ],
    "not_accepted":[
        "production mTLS proxy or service mesh certification",
        "real deployment secret injection/network controls",
        "client certificate private key compromise or revocation",
        "production SQL-writer compromise resistance",
        "in-flight COMMIT during PostgreSQL failover"
    ]
},indent=2)+"\n",encoding="utf-8")
print("Wave26: real PostgreSQL16 private mTLS broker PASS")
