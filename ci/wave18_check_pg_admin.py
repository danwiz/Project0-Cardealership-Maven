#!/usr/bin/env python3
"""Fail closed unless all Wave18 PostgreSQL16 concurrent-revocation tests ran and passed."""
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

REPORT = pathlib.Path("target/surefire-reports") / (
    "TEST-com.revature.repository.jdbc.PostgresqlAdminRevocationInterleavingTest.xml"
)
EXPECTED = {
    "revokedActorCannotProvisionAfterBlockedActorRowLock",
    "revokedActorCannotPromoteAfterBlockedActorRowLock",
    "revokedActorCannotDeleteAfterBlockedActorRowLock",
}

def main() -> int:
    if not REPORT.is_file():
        raise RuntimeError("Wave18 PostgreSQL16 test suite is missing; skipping is not accepted")
    root = ET.parse(REPORT).getroot()
    actual = {}
    for testcase in root.iter("testcase"):
        name = testcase.attrib.get("name")
        if name in actual:
            raise RuntimeError("duplicate Wave18 test identity: " + str(name))
        markers = [tag for tag in ("error", "failure", "skipped")
                   if testcase.find(tag) is not None]
        actual[name] = "PASS" if not markers else "+".join(markers)
    if set(actual) != EXPECTED or any(result != "PASS" for result in actual.values()):
        raise RuntimeError("required real PostgreSQL16 interleaving tests not all PASS: "
                           + str(actual))
    output = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(
        "wave18-evidence/pg16-admin.json"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({
        "type": "POSTGRESQL16_INTERLEAVING",
        "status": "PASS",
        "run_count": len(actual),
        "methods": sorted(actual),
        "scope": "separate PG16 connections, actor-row lock, revocation-before-write",
        "not_verified": ["failover", "writer grants", "all concurrency schedules"],
    }, indent=2) + "\n", encoding="utf-8")
    print("Wave18: 3/3 PostgreSQL16 actor-revocation interleavings PASSED")
    return 0

if __name__ == "__main__":
    sys.exit(main())
