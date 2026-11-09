#!/usr/bin/env python3
"""Wave34.6: fail-closed exact-SHA source custody + synthetic cutover JUnit gate.

No original release gate, independent provider case, real financial mapping,
operator migration, or cutover approval may be inferred from a passing run.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

BASE = "8f5544c6ac55015e9ff86bdd2678444e6258921d"
REQUIRED = {
    "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlLedgerCutoverPreflightTest.java",
    "docs/audit/wave34/ADR-W34-008-Ledger-Cutover-Preflight-and-Recovery.md",
    "docs/audit/wave34/Wave34_6_Operator_Cutover_Decision_Runbook.md",
    "docs/audit/wave34/Wave34_6_Cutover_Readiness_Register.json",
    "ci/wave34_6_release_control_gate.py",
    ".github/workflows/wave34-6-ledger-cutover-preflight.yml",
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
CLASS = "com.revature.repository.jdbc.Wave34PostgresqlLedgerCutoverPreflightTest"
METHODS = {
    "mappingCoverageAndImmutableSourceDigestMustMatchBeforeSyntheticPreflight",
    "sourceDriftInvalidatesEarlierPassingSnapshotEvenWhenMappingIsRepaired",
    "failedMappingTransactionRevertsAllMappingsAndKeepsSourceSnapshotIntact",
}
REGISTER = Path("docs/audit/wave34/Wave34_6_Cutover_Readiness_Register.json")
OUT = Path("wave34-6-evidence")

def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.STDOUT).strip()

def verified_head():
    sha = git("rev-parse", "HEAD")
    if len(sha) != 40 or sha != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("exact immutable PR-head checkout required")
    return sha

def write_report(name, extra):
    OUT.mkdir(parents=True, exist_ok=True)
    record = {
        "kind": name, "status": "PASS_EVIDENCE_ONLY",
        "head_sha": verified_head(), "release_disposition": "NO-GO",
        "operator_cutover_authorized": False,
        "independent_provider_verified": False,
        "stage27_gates_closed": 0,
        **extra,
    }
    (OUT / (name + ".json")).write_text(
        json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(record, indent=2, sort_keys=True))

def static():
    verified_head()
    git("merge-base", "--is-ancestor", BASE, "HEAD")
    if os.environ.get("EXPECTED_PARENT") != BASE:
        raise RuntimeError("review parent unexpected; stop")
    paths = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
    if paths != REQUIRED:
        raise RuntimeError("unapproved source change path: " + str(sorted(paths ^ REQUIRED)))
    source_blobs = {}
    for file in FROZEN:
        before = git("rev-parse", BASE + ":" + file)
        after = git("rev-parse", "HEAD:" + file)
        if before != after:
            raise RuntimeError("runtime or prior disposable DDL modified: " + file)
        source_blobs[file] = after
    for file in REQUIRED:
        p = Path(file)
        if not p.is_file() or p.stat().st_size < 250:
            raise RuntimeError("required review evidence missing/empty: " + file)
        if file.endswith(".md"):
            text = p.read_text(encoding="utf-8")
            if "NO-GO" not in text or "PROPOSED" not in text:
                raise RuntimeError("unapproved production disclaimer absent: " + file)
    doc = json.loads(REGISTER.read_text())
    if doc.get("source_baseline_sha") != BASE or doc.get("release_disposition") != "NO-GO":
        raise RuntimeError("register provenance or release state invalid")
    controls = doc.get("controls")
    if (not isinstance(controls, dict)
            or controls.get("schema_version") != 5
            or any(v is not False for k, v in controls.items() if k != "schema_version")):
        raise RuntimeError("source and operator protection boundary violated")
    expected = doc.get("qa_targets", {})
    if (expected.get("full", {}).get("methods"), expected.get("full", {}).get("classes"),
            expected.get("security", {}).get("methods"),
            expected.get("security", {}).get("classes")) != (393, 112, 151, 64):
        raise RuntimeError("JUnit roster changed without review")
    tests = doc.get("synthetic_tests")
    if (not isinstance(tests, list) or len(tests) != 3
            or {t.get("method") for t in tests} != METHODS
            or any(t.get("status") != "PENDING_EXACT_SHA_CI" for t in tests)):
        raise RuntimeError("synthetic tests prematurely approved or omitted")
    gates = doc.get("cutover_gates")
    if (not isinstance(gates, list) or len(gates) != 12
            or any(g.get("authorized_for_production") is not False for g in gates)):
        raise RuntimeError("missing/approved operator cutover controls")
    pva = doc.get("provider_acceptance")
    if (not isinstance(pva, list) or len(pva) != 16
            or any(t.get("status") != "NOT_RUN_REAL_PROVIDER" for t in pva)):
        raise RuntimeError("independent provider acceptance incorrectly claimed")
    approvals = doc.get("review_approvals")
    if (not isinstance(approvals, list) or len(approvals) != 7
            or any(a.get("status") != "PENDING" for a in approvals)):
        raise RuntimeError("unapproved signoffs incorrectly claimed")
    history = doc.get("historical_registers", {})
    stage = history.get("stage27", {})
    phase = history.get("phase16", {})
    if (stage.get("reported_total"), stage.get("verified_closed"),
            phase.get("reported_total"), phase.get("reported_blocking")) != (14, 0, 17, 12):
        raise RuntimeError("historical Stage27 and Phase16 gate counts conflated")
    write_report("scope", {
        "changed_files": sorted(paths),
        "frozen_source_blobs": source_blobs,
        "review_sha256": {
            f: hashlib.sha256(Path(f).read_bytes()).hexdigest()
            for f in sorted(REQUIRED)
        },
        "operator_cutover_gates_pending": len(gates),
        "provider_cases_not_run": len(pva),
    })

def junit(mode):
    verified_head()
    directory = Path("target/surefire-reports")
    if not directory.is_dir():
        raise RuntimeError("Surefire JUnit reports missing")
    all_cases = set()
    for file in directory.glob("TEST-*.xml"):
        for testcase in ET.parse(file).getroot().iter("testcase"):
            pair = (testcase.get("classname"), testcase.get("name"))
            if not all(pair) or pair in all_cases:
                raise RuntimeError("duplicate/missing JUnit test case identity")
            if any(testcase.find(x) is not None for x in ("skipped", "failure", "error")):
                raise RuntimeError("failed or skipped JUnit case " + str(pair))
            all_cases.add(pair)
    seen = {m for owner, m in all_cases if owner == CLASS}
    if mode == "full":
        if seen != METHODS:
            raise RuntimeError("new exact named cutover preflight cases incomplete: " + str(seen))
    elif seen:
        raise RuntimeError("untagged migration preflight unexpectedly in SECURITY selection")
    write_report("junit-" + mode, {
        "all_reported_cases": len(all_cases),
        "wave34_6_expected": sorted(METHODS) if mode == "full" else [],
        "wave34_6_observed": sorted(seen),
        "limitation": "synthetic evidence only; real writer fencing and provider trust NOT RUN",
    })

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("static", "full", "security"):
            raise RuntimeError("Usage: wave34_6_release_control_gate.py static|full|security")
        if sys.argv[1] == "static":
            static()
        else:
            junit(sys.argv[1])
    except (OSError, RuntimeError, ValueError, KeyError,
            ET.ParseError, subprocess.CalledProcessError, json.JSONDecodeError) as e:
        print("NO-GO Wave34.6 evidence: " + str(e), file=sys.stderr)
        sys.exit(1)
