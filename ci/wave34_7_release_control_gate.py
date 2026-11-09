#!/usr/bin/env python3
"""Wave34.7 exact-SHA source custody and synthetic fencing evidence validator.

The passing artifacts do NOT certify independent principal fencing, quorum,
real provider trust, operator migration or production release readiness.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

BASE = "e2b55f30836d99fe81b03254a0b42e533f8e5fa5"
REQUIRED = {
    "docs/audit/wave34/wave34_7_DISPOSABLE_cooperative_writer_fence.sql",
    "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlWriterFencingRecoveryTest.java",
    "docs/audit/wave34/ADR-W34-009-Cooperative-Fencing-and-Cutover-Evidence.md",
    "docs/audit/wave34/Wave34_7_Operator_Fencing_Recovery_Runbook.md",
    "docs/audit/wave34/Wave34_7_Fencing_Evidence_Decision_Register.json",
    "ci/wave34_7_release_control_gate.py",
    ".github/workflows/wave34-7-writer-fencing-recovery.yml",
}
FROZEN = (
    "pom.xml",
    "src/main/java/com/revature/repository/jdbc/JdbcDatabase.java",
    "src/main/java/com/revature/repository/jdbc/JdbcPaymentProcessor.java",
    "src/main/java/com/revature/repository/jdbc/JdbcWave34PaymentReconciliationCandidate.java",
    "src/main/java/com/revature/application/ProviderPaymentIdentity.java",
    "docs/audit/wave34/wave34_candidate_additive_receipt.sql",
    "docs/audit/wave34/wave34_5_disposable_ledger_identity_mapping.sql",
)
CLASS = "com.revature.repository.jdbc.Wave34PostgresqlWriterFencingRecoveryTest"
METHODS = {
    "committedFreezeDeniesStaleAndFrozenWritersUntilNewEpochIsOpened",
    "activeWriterShareLockPreventsFenceCommitUntilWriterCompletes",
    "abortedOperatorFreezeRollsBackEpochAndPreservesLedgerDigest",
}
SCENARIOS = {"committed-freeze", "writer-drain-barrier", "aborted-operator-recovery"}
REGISTER = Path("docs/audit/wave34/Wave34_7_Fencing_Evidence_Decision_Register.json")
OUT = Path("wave34-7-evidence")

def git(*args):
    return subprocess.check_output(["git", *args], text=True,
                                   stderr=subprocess.STDOUT).strip()

def checked_head():
    sha = git("rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or sha != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("checked-out SHA does not match immutable PR source")
    return sha

def emit(kind, details):
    OUT.mkdir(parents=True, exist_ok=True)
    record = {
        "kind": kind,
        "status": "PASS_EVIDENCE_ONLY",
        "source_sha": checked_head(),
        "release_disposition": "NO-GO",
        "production_migration": False,
        "real_provider_identity_verified": False,
        "stage27_gates_closed": 0,
        **details,
    }
    (OUT / (kind + ".json")).write_text(
        json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(record, indent=2, sort_keys=True))

def static():
    checked_head()
    git("merge-base", "--is-ancestor", BASE, "HEAD")
    if os.environ.get("EXPECTED_PARENT") != BASE:
        raise RuntimeError("head not stacked on expected Wave34.6 source")
    diff = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
    if diff != REQUIRED:
        raise RuntimeError("unapproved changed source paths: " + str(sorted(diff ^ REQUIRED)))
    unchanged = {}
    for path in FROZEN:
        old = git("rev-parse", BASE + ":" + path)
        new = git("rev-parse", "HEAD:" + path)
        if old != new:
            raise RuntimeError("runtime or inherited SQL modified: " + path)
        unchanged[path] = new
    for path in REQUIRED:
        file = Path(path)
        if not file.is_file() or file.stat().st_size < 250:
            raise RuntimeError("missing or incomplete reviewed artifact: " + path)
        if path.endswith(".md"):
            words = file.read_text(encoding="utf-8")
            if "NO-GO" not in words or "PROPOSED" not in words:
                raise RuntimeError("review-only warning missing: " + path)
    sql = Path("docs/audit/wave34/wave34_7_DISPOSABLE_cooperative_writer_fence.sql").read_text()
    if "DISPOSABLE" not in sql or "NOT A MIGRATION" not in sql or sql.count("-- W347_STATEMENT_BREAK") != 4:
        raise RuntimeError("test-only DDL scope / delimiter mismatch")
    record = json.loads(REGISTER.read_text(encoding="utf-8"))
    if (record.get("parent_head_sha") != BASE
            or record.get("release_disposition") != "NO-GO"):
        raise RuntimeError("invalid baseline or release status")
    flags = record.get("invariants", {})
    if (flags.get("app_schema_version") != 5 or any(
            v is not False for k, v in flags.items() if k != "app_schema_version")):
        raise RuntimeError("review-only source and production invariants contradicted")
    qa = record.get("test_targets", {})
    if (qa.get("full", {}).get("methods"), qa.get("full", {}).get("classes"),
            qa.get("security", {}).get("methods"),
            qa.get("security", {}).get("classes")) != (396, 113, 151, 64):
        raise RuntimeError("regression roster target drift")
    tests = record.get("characterized_tests")
    if not isinstance(tests, list) or len(tests) != 3 or {x.get("method") for x in tests} != METHODS:
        raise RuntimeError("named fencing tests missing from decision register")
    if any(x.get("status") != "PENDING_EXACT_SHA_CI" for x in tests):
        raise RuntimeError("fencing tests prematurely certified")
    gates = record.get("cutover_fencing_gates", [])
    if len(gates) != 12 or any(g.get("approved_for_production") is not False for g in gates):
        raise RuntimeError("cutover gate improperly approved")
    provider = record.get("provider_acceptance", [])
    if len(provider) != 16 or any(p.get("status") != "NOT_RUN_REAL_PROVIDER" for p in provider):
        raise RuntimeError("provider acceptance incorrectly claimed")
    owners = record.get("decision_owners", [])
    if len(owners) != 7 or any(o.get("signoff") != "PENDING" for o in owners):
        raise RuntimeError("missing owner-independent signoffs")
    historical = record.get("historical_registers", {})
    stage = historical.get("stage27", {})
    old = historical.get("phase16", {})
    if (stage.get("reported_total"), stage.get("verified_closed"),
            old.get("reported_total"), old.get("reported_blocking")) != (14, 0, 17, 12):
        raise RuntimeError("Stage27 and Phase16 historical registers were conflated")
    emit("source-custody", {
        "changed": sorted(diff),
        "unchanged_source_blobs": unchanged,
        "artifact_sha256": {
            path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
            for path in sorted(REQUIRED)
        },
        "production_fencing_cases_approved": 0,
    })

def junit(kind):
    checked_head()
    reports = Path("target/surefire-reports")
    if not reports.is_dir():
        raise RuntimeError("JUnit reports missing")
    cases = set()
    for file in reports.glob("TEST-*.xml"):
        for case in ET.parse(file).getroot().iter("testcase"):
            key = (case.get("classname"), case.get("name"))
            if not all(key) or key in cases:
                raise RuntimeError("duplicate or absent testcase identity")
            if any(case.find(status) is not None for status in ("failure", "error", "skipped")):
                raise RuntimeError("failed/skipped testcase: " + str(key))
            cases.add(key)
    new_tests = {method for owner, method in cases if owner == CLASS}
    if kind == "full" and new_tests != METHODS:
        raise RuntimeError("named PostgreSQL16 fencing cases did not all execute")
    if kind == "security" and new_tests:
        raise RuntimeError("untagged fencing tests leaked into SECURITY selection")
    manifests = {}
    if kind == "full":
        folder = Path("target/wave34-7-evidence")
        if not folder.is_dir():
            raise RuntimeError("test-generated fencing manifests not retained")
        files = {file.stem: file for file in folder.glob("*.json")}
        if set(files) != SCENARIOS:
            raise RuntimeError("expected 3 independent test manifests, got: " + str(sorted(files)))
        for scenario, file in sorted(files.items()):
            record = json.loads(file.read_text(encoding="utf-8"))
            if (record.get("scenario") != scenario
                    or record.get("head_sha") != checked_head()
                    or record.get("fixture") != "DISPOSABLE_PG16_ONLY"
                    or record.get("release") != "NO-GO"
                    or record.get("schema_version") != 5
                    or record.get("production_writer_fenced") is not False
                    or record.get("production_authorized") is not False
                    or record.get("mode") not in ("OPEN", "FROZEN")
                    or not isinstance(record.get("epoch"), int)
                    or not re.fullmatch(r"[0-9a-f]{64}", record.get("ledger_sha256", ""))):
                raise RuntimeError("invalid synthetic evidence manifest: " + scenario)
            manifests[scenario] = hashlib.sha256(file.read_bytes()).hexdigest()
    emit("junit-" + kind, {
        "observed_test_methods": len(cases),
        "named_fencing_methods": sorted(new_tests),
        "synthetic_manifest_sha256": manifests,
        "limitation": "Tests model a cooperative single-DB epoch, not unspoofable production fencing",
    })

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("static", "full", "security"):
            raise RuntimeError("usage: wave34_7_release_control_gate.py static|full|security")
        if sys.argv[1] == "static":
            static()
        else:
            junit(sys.argv[1])
    except (OSError, RuntimeError, ValueError, KeyError, TypeError,
            ET.ParseError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("NO-GO Wave34.7 custody/evidence: " + str(error), file=sys.stderr)
        sys.exit(1)
