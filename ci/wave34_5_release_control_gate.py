#!/usr/bin/env python3
"""Wave34.5 exact-SHA test-only ledger migration scope and JUnit custody.
Passing this check is documentation/CI evidence, never release approval.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

BASE = "087031e73b30817b58f423b72eafa28c0c3b8ce2"
REQUIRED = {
    "docs/audit/wave34/wave34_5_disposable_ledger_identity_mapping.sql",
    "docs/audit/wave34/ADR-W34-007-Ledger-Identity-Mapping-Rehearsal.md",
    "docs/audit/wave34/Wave34_5_Migration_Rehearsal_Decision_Register.json",
    "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlLedgerIdentityMigrationRehearsalTest.java",
    "ci/wave34_5_release_control_gate.py",
    ".github/workflows/wave34-5-ledger-migration-rehearsal.yml",
}
FROZEN = (
    "pom.xml",
    "src/main/java/com/revature/repository/jdbc/JdbcDatabase.java",
    "src/main/java/com/revature/repository/jdbc/JdbcPaymentProcessor.java",
    "src/main/java/com/revature/repository/jdbc/JdbcWave34PaymentReconciliationCandidate.java",
    "src/main/java/com/revature/application/ProviderPaymentIdentity.java",
    "docs/audit/wave34/wave34_candidate_additive_receipt.sql",
)
CLASS = "com.revature.repository.jdbc.Wave34PostgresqlLedgerIdentityMigrationRehearsalTest"
METHODS = {
    "historicalSixAndSevenDigitIdsRemainUnchangedUnderAdditiveMapping",
    "concurrentFirstIdentityMappingsRemainUniqueWithoutLatestRowLock",
    "rolledBackMappingLeavesNoOrphanAndNeverReusesIssuedSequenceValue",
}
OUT = Path("wave34-5-evidence")
REGISTER = Path("docs/audit/wave34/Wave34_5_Migration_Rehearsal_Decision_Register.json")

def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.STDOUT).strip()

def head():
    current = git("rev-parse", "HEAD")
    if len(current) != 40 or current != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("exact immutable PR head missing or changed")
    return current

def emit(kind, evidence):
    OUT.mkdir(parents=True, exist_ok=True)
    payload = {
        "kind": kind,
        "status": "PASS_EVIDENCE_ONLY",
        "source_sha": head(),
        "release_disposition": "NO-GO",
        "stage27_release_gates_closed": 0,
        "production_migration_executed": False,
        **evidence
    }
    (OUT / (kind + ".json")).write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(payload, indent=2, sort_keys=True))

def static():
    head()
    git("merge-base", "--is-ancestor", BASE, "HEAD")
    if os.environ.get("EXPECTED_PARENT") != BASE:
        raise RuntimeError("review branch source parent does not match baseline")
    changed = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
    if changed != REQUIRED:
        raise RuntimeError("non-reviewed changed paths: " + str(sorted(changed ^ REQUIRED)))
    blobs = {}
    for file in FROZEN:
        old = git("rev-parse", BASE + ":" + file)
        new = git("rev-parse", "HEAD:" + file)
        if old != new:
            raise RuntimeError("runtime/production schema change is out of scope: " + file)
        blobs[file] = new
    for file in REQUIRED:
        p = Path(file)
        if not p.is_file() or p.stat().st_size < 300:
            raise RuntimeError("missing or empty review-only file: " + file)
    sql = Path("docs/audit/wave34/wave34_5_disposable_ledger_identity_mapping.sql").read_text()
    if "DISPOSABLE" not in sql or "NOT an approved v6 migration" not in sql:
        raise RuntimeError("candidate test DDL lacks explicit production warning")
    adr = Path("docs/audit/wave34/ADR-W34-007-Ledger-Identity-Mapping-Rehearsal.md").read_text()
    if "NO-GO" not in adr or "PROPOSED" not in adr:
        raise RuntimeError("architecture decision incorrectly claims production acceptance")

    record = json.loads(REGISTER.read_text())
    if (record.get("source_baseline_sha") != BASE
            or record.get("release_disposition") != "NO-GO"):
        raise RuntimeError("unverified register baseline or release status")
    controls = record.get("protection", {})
    if (not controls or controls.get("application_schema_version") != 5
            or any(value is not False for key, value in controls.items()
                   if key != "application_schema_version")):
        raise RuntimeError("protection controls are incomplete or unsafe")
    qa = record.get("qa_targets", {})
    if (qa.get("full", {}).get("methods"), qa.get("full", {}).get("classes"),
            qa.get("security", {}).get("methods"), qa.get("security", {}).get("classes")) != (
                390, 111, 151, 64):
        raise RuntimeError("unexpected JUnit roster targets")
    tests = record.get("rehearsal_tests")
    if not isinstance(tests, list) or len(tests) != 3 or any(
            t.get("status") != "PENDING_EXACT_SHA_CI" for t in tests):
        raise RuntimeError("rehearsal status incorrectly self-approved")
    pva = record.get("real_provider_acceptance")
    if not isinstance(pva, list) or len(pva) != 16 or any(
            c.get("status") != "NOT_RUN_REAL_PROVIDER" for c in pva):
        raise RuntimeError("real provider tests incorrectly marked complete")
    decisions = record.get("owner_decisions")
    if not isinstance(decisions, list) or len(decisions) != 6 or any(
            row.get("status") != "PENDING_OWNER_APPROVAL" for row in decisions):
        raise RuntimeError("unapproved owner decisions marked complete")
    historical = record.get("historical_gates", {})
    if (historical.get("stage27", {}).get("reported_total") != 14
            or historical.get("stage27", {}).get("verified_closed") != 0
            or historical.get("phase16", {}).get("reported_total") != 17
            or historical.get("phase16", {}).get("reported_blocking") != 12):
        raise RuntimeError("historical 14- and 17-gate registers conflated")
    emit("scope", {
        "changed_files": sorted(changed),
        "frozen_source_blob_ids": blobs,
        "review_artifact_sha256": {
            file: hashlib.sha256(Path(file).read_bytes()).hexdigest()
            for file in sorted(REQUIRED)
        },
        "real_provider_test_cases_unexecuted": len(pva),
        "owner_approvals_pending": len(decisions),
    })

def junit(mode):
    head()
    directory = Path("target/surefire-reports")
    if not directory.is_dir():
        raise RuntimeError("JUnit report directory unavailable")
    cases = set()
    for file in directory.glob("TEST-*.xml"):
        for c in ET.parse(file).getroot().iter("testcase"):
            identity = (c.get("classname"), c.get("name"))
            if not all(identity) or identity in cases:
                raise RuntimeError("missing/duplicate JUnit test identity")
            if any(c.find(x) is not None for x in ("failure", "error", "skipped")):
                raise RuntimeError("failed or skipped JUnit case: " + str(identity))
            cases.add(identity)
    actual = {name for owner, name in cases if owner == CLASS}
    if mode == "full":
        if actual != METHODS:
            raise RuntimeError("new PG16 mapping rehearsal cases missing: " + str(actual))
    elif actual:
        raise RuntimeError("SECURITY selection included untagged mapping rehearsal")
    emit("junit-" + mode, {
        "observed_junit_methods": len(cases),
        "new_pg16_mapping_methods": sorted(actual),
        "scope": "named JUnit test evidence, not production migration certification"
    })

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("static", "full", "security"):
            raise RuntimeError("usage: wave34_5_release_control_gate.py static|full|security")
        if sys.argv[1] == "static":
            static()
        else:
            junit(sys.argv[1])
    except (OSError, ValueError, RuntimeError, KeyError, ET.ParseError,
            subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("NO-GO Wave34.5 verification: " + str(error), file=sys.stderr)
        sys.exit(1)
