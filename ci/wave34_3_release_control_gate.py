#!/usr/bin/env python3
"""W34.3: fail-closed architecture custody and exact-SHA regression evidence.

This tool NEVER approves releases, runs SQL or accesses provider payments.
It guards a documentation-only PR's source ancestry and records which tests
actually ran, without pretending to satisfy original Stage27 release gates.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

BASELINE = "c369e22a5dd87831286bf821bf7f59e30a9a7311"
REGISTER = Path("docs/audit/wave34/Wave34_3_Decision_And_Gate_Proposal.json")
OUT = Path("wave34-3-evidence")
REQUIRED = {
    "docs/audit/wave34/ADR-W34-003-Independent-Provider-Trust.md",
    "docs/audit/wave34/ADR-W34-004-Ledger-Sequence-Migration.md",
    "docs/audit/wave34/ADR-W34-005-Outbox-Refund-HA-Controls.md",
    "docs/audit/wave34/wave34_3_future_schema_DESIGN_ONLY.sql",
    str(REGISTER),
    "ci/wave34_3_release_control_gate.py",
    ".github/workflows/wave34-3-release-control.yml",
}
FROZEN_CODE = (
    "pom.xml",
    "src/main/java/com/revature/repository/jdbc/JdbcDatabase.java",
    "src/main/java/com/revature/repository/jdbc/JdbcPaymentProcessor.java",
    "src/main/java/com/revature/repository/jdbc/JdbcWave34PaymentReconciliationCandidate.java",
    "src/main/java/com/revature/application/ProviderPaymentIdentity.java",
    "docs/audit/wave34/wave34_candidate_additive_receipt.sql",
)
JUNIT = {
    "com.revature.application.ProviderPaymentIdentityTest": 4,
    "com.revature.repository.jdbc.Wave34PostgresqlReceiptUniquenessTest": 1,
    "com.revature.repository.jdbc.Wave34PostgresqlPaymentReconciliationTest": 3,
}

def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.STDOUT).strip()

def expected_sha():
    actual = git("rev-parse", "HEAD")
    supplied = os.environ.get("EXPECTED_HEAD", "")
    if len(supplied) != 40 or actual != supplied:
        raise RuntimeError("immutable checked-out head does not equal EXPECTED_HEAD")
    return actual

def report(name, record):
    OUT.mkdir(parents=True, exist_ok=True)
    result = {
        "status": "PASS_EVIDENCE_ONLY",
        "candidate_sha": expected_sha(),
        "release_disposition": "NO-GO",
        "proof_kind": name,
        "limitation": "Review-only evidence; no original Stage27 gate was independently approved",
        **record,
    }
    target = OUT / (name + ".json")
    target.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))

def static():
    head = expected_sha()
    if git("merge-base", "--is-ancestor", BASELINE, head) != "":
        raise RuntimeError("unexpected merge-base output")
    expected_parent = os.environ.get("EXPECTED_PARENT", BASELINE)
    if expected_parent != BASELINE:
        raise RuntimeError("wrong pinned Wave34.2 parent SHA")
    paths = set(git("diff", "--name-only", BASELINE, "HEAD").splitlines())
    if paths != REQUIRED:
        raise RuntimeError("unapproved file changes: expected %s, got %s" %
                           (sorted(REQUIRED), sorted(paths)))
    blobs = {}
    for path in FROZEN_CODE:
        before = git("rev-parse", BASELINE + ":" + path)
        after = git("rev-parse", "HEAD:" + path)
        if before != after:
            raise RuntimeError("frozen runtime source changed: " + path)
        blobs[path] = after
    register = json.loads(REGISTER.read_text(encoding="utf-8"))
    if register.get("source_baseline_sha") != BASELINE:
        raise RuntimeError("register based on unverified source")
    if register.get("release_disposition") != "NO-GO":
        raise RuntimeError("register attempts to approve a release")
    stage = register.get("historic_stage27_register", {})
    if (stage.get("reported_gate_total"), stage.get("reported_closed"),
            stage.get("status")) != (14, 0, "HISTORICAL_UNRECONCILED"):
        raise RuntimeError("historical Stage27 gate register improperly revised")
    phase16 = register.get("previous_phase16", {})
    if (phase16.get("reported_gate_total"), phase16.get("reported_blocking"),
            phase16.get("status")) != (17, 12, "HISTORICAL_UNRECONCILED"):
        raise RuntimeError("historical Phase16 gate claims improperly revised")
    decisions = register.get("decisions")
    if not isinstance(decisions, list) or len(decisions) != 12:
        raise RuntimeError("incomplete owner decision register")
    ids = [d.get("id") for d in decisions]
    if len(set(ids)) != 12:
        raise RuntimeError("duplicate owner decision IDs")
    for row in decisions:
        if (row.get("status") != "PENDING_OWNER_REVIEW"
                or row.get("production_implemented") is not False):
            raise RuntimeError("unapproved owner decision marked implemented/complete")
        file = Path(row.get("design_document", ""))
        if not file.is_file() or not str(file).startswith("docs/audit/wave34/"):
            raise RuntimeError("missing design evidence: " + str(file))
    approvals = register.get("permission_boundary", {})
    if not isinstance(approvals, dict) or any(v is not False for v in approvals.values()):
        raise RuntimeError("permission boundary is not a complete prohibition")
    targets = {r["id"]: r for r in register.get("qa_targets", [])}
    if (targets.get("QA-W34-FULL", {}).get("expected_methods") != 384
            or targets.get("QA-W34-FULL", {}).get("expected_classes") != 109
            or targets.get("QA-W34-SECURITY", {}).get("expected_methods") != 151
            or targets.get("QA-W34-SECURITY", {}).get("expected_classes") != 64):
        raise RuntimeError("unreviewed JUnit target drift")
    for name in REQUIRED:
        path = Path(name)
        if not path.is_file() or path.stat().st_size < 250:
            raise RuntimeError("missing/incomplete architecture artifact: " + name)
    for name in REQUIRED:
        if name.endswith(".md"):
            text = Path(name).read_text(encoding="utf-8")
            if "NO-GO" not in text or "PROPOSED" not in text:
                raise RuntimeError("architecture limitation absent: " + name)
    sql = Path("docs/audit/wave34/wave34_3_future_schema_DESIGN_ONLY.sql").read_text()
    if "NOT AN APPROVED MIGRATION" not in sql or "DO NOT RUN" not in sql:
        raise RuntimeError("SQL candidate not clearly marked as non-executable")
    report("architecture-scope", {
        "scope_control": "PASS_UNCHANGED_RUNTIME_CODE",
        "changes": sorted(paths),
        "frozen_source_blob_ids": blobs,
        "owner_decisions_pending": len(decisions),
        "historical_release_gate_status": "UNRECONCILED_0_OF_14_REPORTED_CLOSED",
        "documentation_hashes": {
            name: hashlib.sha256(Path(name).read_bytes()).hexdigest()
            for name in sorted(REQUIRED)
        },
    })

def junit(mode):
    expected_sha()
    directory = Path("target/surefire-reports")
    if not directory.is_dir():
        raise RuntimeError("Surefire XML directory absent")
    present = set()
    for file in directory.glob("TEST-*.xml"):
        for case in ET.parse(file).getroot().iter("testcase"):
            name, owner = case.get("name"), case.get("classname")
            if not name or not owner or (owner, name) in present:
                raise RuntimeError("missing/duplicated test method identity")
            if any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
                raise RuntimeError("skipped or failing test: " + str((owner, name)))
            present.add((owner, name))
    expected = JUNIT if mode == "full" else {k: v for k, v in JUNIT.items() if "repository.jdbc" in k}
    for cls, count in expected.items():
        actual = {name for owner, name in present if owner == cls}
        if len(actual) != count:
            raise RuntimeError("Wave34 named test roster missing: %s %s/%s" %
                               (cls, len(actual), count))
    report("junit-" + mode, {
        "required_wave34_method_count": sum(expected.values()),
        "observed_all_junit_cases": len(present),
        "scope": "Full/SECURITY test roster; generic total count separately verified",
        "required_classes": expected,
    })

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("static", "full", "security"):
            raise RuntimeError("Usage: wave34_3_release_control_gate.py static|full|security")
        if sys.argv[1] == "static":
            static()
        else:
            junit(sys.argv[1])
    except (RuntimeError, OSError, ValueError, KeyError, ET.ParseError,
            subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("NO-GO W34.3 EVIDENCE: " + str(error), file=sys.stderr)
        sys.exit(1)
