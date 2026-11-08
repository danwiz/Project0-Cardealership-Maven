#!/usr/bin/env python3
"""Wave34.4 exact-SHA, source-custody and NEGATIVE characterization gates.
Pass validates evidence only and cannot close Stage27 release gates.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

BASE = "946fdaf2362aadff3e4dc38db36a253040242913"
REQUIRED = {
    "src/test/java/com/revature/repository/jdbc/Wave34LedgerIdentityCharacterizationTest.java",
    "docs/audit/wave34/ADR-W34-006-Ledger-Identity-Design-and-Cutover.md",
    "docs/audit/wave34/Provider-Authentication-Acceptance-Contract-W34-4.md",
    "docs/audit/wave34/Wave34_4_Characterization_Gate_Register.json",
    "ci/wave34_4_release_control_gate.py",
    ".github/workflows/wave34-4-ledger-identity.yml",
}
FROZEN = (
    "pom.xml",
    "src/main/java/com/revature/repository/jdbc/JdbcDatabase.java",
    "src/main/java/com/revature/repository/jdbc/JdbcPaymentProcessor.java",
    "src/main/java/com/revature/repository/jdbc/JdbcWave34PaymentReconciliationCandidate.java",
    "src/main/java/com/revature/application/ProviderPaymentIdentity.java",
    "docs/audit/wave34/wave34_candidate_additive_receipt.sql",
)
CLASS = "com.revature.repository.jdbc.Wave34LedgerIdentityCharacterizationTest"
METHODS = {
    "sevenDigitBoundarySortsAfterLegacyMaximumAndBlocksNextPayment",
    "concurrentFirstPaymentLookupsFindNoRowToLock",
    "disposableDatabaseSequenceAllocatesDistinctIdsBeforeAnyLedgerRow",
}
OUT = Path("wave34-4-evidence")

def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.STDOUT).strip()

def head():
    actual = git("rev-parse", "HEAD")
    if len(actual) != 40 or actual != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("expected immutable exact PR head unavailable")
    return actual

def emit(name, details):
    OUT.mkdir(parents=True, exist_ok=True)
    report = {
        "kind": name,
        "status": "PASS_EVIDENCE_ONLY",
        "source_sha": head(),
        "release_disposition": "NO-GO",
        "stage27_gates_closed_by_this_check": 0,
        **details,
    }
    (OUT / (name + ".json")).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))

def static():
    head()
    git("merge-base", "--is-ancestor", BASE, "HEAD")
    if os.environ.get("EXPECTED_PARENT") != BASE:
        raise RuntimeError("unexpected review parent")
    changed = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
    if changed != REQUIRED:
        raise RuntimeError("unexpected changed file list: " + str(sorted(changed)))
    hashes = {}
    for file in FROZEN:
        before = git("rev-parse", BASE + ":" + file)
        after = git("rev-parse", "HEAD:" + file)
        if before != after:
            raise RuntimeError("runtime or schema changed: " + file)
        hashes[file] = after
    for file in REQUIRED:
        source = Path(file)
        if not source.exists() or source.stat().st_size < 300:
            raise RuntimeError("missing required file: " + file)
    for file in REQUIRED:
        if file.endswith(".md"):
            words = Path(file).read_text()
            if "NO-GO" not in words or "PROPOSED" not in words:
                raise RuntimeError("approval limitations absent: " + file)
    register = json.loads(Path(
        "docs/audit/wave34/Wave34_4_Characterization_Gate_Register.json").read_text())
    if (register.get("source_baseline_sha") != BASE
            or register.get("release_disposition") != "NO-GO"
            or register.get("payment_production_migration") is not False
            or register.get("real_provider_authenticated") is not False
            or register.get("protected_branches_changed") is not False):
        raise RuntimeError("release control register contradicts review scope")
    if (register.get("qa_targets", {}).get("full") or {}).get("methods") != 387:
        raise RuntimeError("full roster target drift")
    if (register.get("qa_targets", {}).get("full") or {}).get("classes") != 110:
        raise RuntimeError("full class target drift")
    if (register.get("qa_targets", {}).get("security") or {}).get("methods") != 151:
        raise RuntimeError("security roster target drift")
    pva = register.get("real_provider_contract_cases")
    if not isinstance(pva, list) or len(pva) != 16 or any(
            r.get("status") != "NOT_RUN_REAL_PROVIDER" for r in pva):
        raise RuntimeError("real provider acceptance cases improperly certified")
    old = register.get("historical_gates", {})
    if (old.get("stage27", {}).get("reported_gates") != 14
            or old.get("stage27", {}).get("reported_closed") != 0
            or old.get("phase16", {}).get("reported_gates") != 17
            or old.get("phase16", {}).get("reported_blocking") != 12):
        raise RuntimeError("historical release gates misrepresented")
    emit("scope", {
        "changed_files": sorted(changed),
        "frozen_runtime_blobs": hashes,
        "provider_cases_unexecuted": len(pva),
        "review_only_artifact_sha256": {
            file: hashlib.sha256(Path(file).read_bytes()).hexdigest()
            for file in sorted(REQUIRED)
        },
    })

def junit(mode):
    head()
    folder = Path("target/surefire-reports")
    if not folder.is_dir():
        raise RuntimeError("no test reports")
    cases = set()
    for report in folder.glob("TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            pair = (case.get("classname"), case.get("name"))
            if not all(pair) or pair in cases:
                raise RuntimeError("duplicated/incomplete test identity")
            if any(case.find(t) is not None for t in ("failure", "error", "skipped")):
                raise RuntimeError("skipped/failed test " + str(pair))
            cases.add(pair)
    observed = {method for owner, method in cases if owner == CLASS}
    if mode == "full":
        if observed != METHODS:
            raise RuntimeError("new characterized methods missing: " + str(observed))
    elif observed:
        raise RuntimeError("SECURITY-only selector incorrectly includes untagged characterization")
    emit("junit-" + mode, {
        "testcase_count": len(cases),
        "wave34_4_expected": sorted(METHODS) if mode == "full" else [],
        "wave34_4_observed": sorted(observed),
        "description": "Counts and exact method names only; provider acceptance not executed",
    })

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("static", "full", "security"):
            raise RuntimeError("use static|full|security")
        if sys.argv[1] == "static":
            static()
        else:
            junit(sys.argv[1])
    except (OSError, ValueError, KeyError, RuntimeError, ET.ParseError,
            subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("NO-GO Wave34.4 evidence: " + str(error), file=sys.stderr)
        sys.exit(1)
