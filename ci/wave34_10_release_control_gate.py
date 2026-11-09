#!/usr/bin/env python3
"""Wave34.10 exact-SHA source custody and synthetic signed-lease acceptance.

PASS is JCA/JUnit evidence ONLY, not an independent issuer, payment approval,
durable replay store, production database migration or release GO.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

BASE = "0ddb87448705f3d091693e508b730b21348fbfc2"
REQUIRED = {
    "src/test/java/com/revature/repository/jdbc/Wave34SenderConstrainedLeaseContractTest.java",
    "docs/audit/wave34/ADR-W34-012-Sender-Constrained-Issuer-Contract.md",
    "docs/audit/wave34/Wave34_10_Sender_Proof_Security_Review_Runbook.md",
    "docs/audit/wave34/Wave34_10_Issuer_Proof_Gate_Register.json",
    "ci/wave34_10_release_control_gate.py",
    ".github/workflows/wave34-10-sender-proof-regression.yml",
}
FROZEN = (
    "pom.xml",
    "src/main/java/com/revature/repository/jdbc/JdbcDatabase.java",
    "src/main/java/com/revature/repository/jdbc/JdbcPaymentProcessor.java",
    "src/main/java/com/revature/repository/jdbc/JdbcWave34PaymentReconciliationCandidate.java",
    "src/main/java/com/revature/application/ProviderPaymentIdentity.java",
    "docs/audit/wave34/wave34_candidate_additive_receipt.sql",
    "docs/audit/wave34/wave34_5_disposable_ledger_identity_mapping.sql",
    "docs/audit/wave34/wave34_7_DISPOSABLE_cooperative_writer_fence.sql",
    "docs/audit/wave34/wave34_8_DISPOSABLE_authenticated_principal_fence.sql",
    "docs/audit/wave34/wave34_9_DISPOSABLE_lease_fence.sql",
)
CLASS = "com.revature.repository.jdbc.Wave34SenderConstrainedLeaseContractTest"
METHODS = {
    "authenticIssuerSignatureRequiredAndTamperedLeaseClaimsRejected",
    "senderProofIsBoundToWriterKeyAndExactRequestFields",
    "exactChallengeReplayAndConcurrentDoubleAuthorizationAreRejected",
    "expiryRevocationIssuerRotationEpochAndAudienceFailClosed",
}
SCENARIOS = {
    "issuer-signature-and-tamper",
    "sender-proof-request-binding",
    "concurrent-replay-rejection",
    "expiry-revocation-rotation",
}
REGISTER = Path("docs/audit/wave34/Wave34_10_Issuer_Proof_Gate_Register.json")
OUT = Path("wave34-10-evidence")

def git(*args):
    return subprocess.check_output(["git", *args], text=True,
                                   stderr=subprocess.STDOUT).strip()

def head():
    value = git("rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", value) or value != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("exact immutable PR-head checkout required")
    return value

def emit(kind, data):
    OUT.mkdir(parents=True, exist_ok=True)
    record = {
        "kind": kind,
        "source_head": head(),
        "status": "PASS_EVIDENCE_ONLY",
        "release_disposition": "NO-GO",
        "independent_issuer_approved": False,
        "real_provider_tested": False,
        "production_authorized": False,
        "stage27_gates_closed": 0,
        **data,
    }
    (OUT / (kind + ".json")).write_text(
        json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(record, indent=2, sort_keys=True))

def static():
    head()
    git("merge-base", "--is-ancestor", BASE, "HEAD")
    if os.environ.get("EXPECTED_PARENT") != BASE:
        raise RuntimeError("source parent SHA drift")
    changed = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
    if changed != REQUIRED:
        raise RuntimeError("unexpected changed paths: " + str(sorted(changed ^ REQUIRED)))
    blobs = {}
    for path in FROZEN:
        old = git("rev-parse", BASE + ":" + path)
        new = git("rev-parse", "HEAD:" + path)
        if old != new:
            raise RuntimeError("unreviewed runtime/SQL migration edit: " + path)
        blobs[path] = new
    for path in REQUIRED:
        file = Path(path)
        if not file.is_file() or file.stat().st_size < 250:
            raise RuntimeError("missing or empty source artifact: " + path)
        if path.endswith(".md"):
            contents = file.read_text(encoding="utf-8")
            if "PROPOSED" not in contents or "NO-GO" not in contents:
                raise RuntimeError("documentation lacks source/release limitations: " + path)
    register = json.loads(REGISTER.read_text(encoding="utf-8"))
    if register.get("parent_head_sha") != BASE or register.get("release_disposition") != "NO-GO":
        raise RuntimeError("invalid source baseline/release disposition")
    flags = register.get("invariants", {})
    if (flags.get("app_schema_version") != 5 or any(
            value is not False for key, value in flags.items()
            if key != "app_schema_version")):
        raise RuntimeError("production or independent-issuer control wrongly approved")
    qa = register.get("test_targets", {})
    if (qa.get("full", {}).get("methods"), qa.get("full", {}).get("classes"),
            qa.get("security", {}).get("methods"),
            qa.get("security", {}).get("classes")) != (406, 116, 151, 64):
        raise RuntimeError("test-roster size drift")
    cases = register.get("cases", [])
    if (len(cases) != 4
            or {case.get("method") for case in cases} != METHODS
            or {case.get("scenario") for case in cases} != SCENARIOS
            or any(case.get("status") != "PENDING_EXACT_SHA_CI" for case in cases)):
        raise RuntimeError("incomplete or prematurely certified test register")
    manifest = register.get("manifest_contract", {})
    if (set(manifest.get("scenarios", [])) != SCENARIOS
            or manifest.get("signed") is not False
            or manifest.get("independent_timestamp") is not False):
        raise RuntimeError("manifest contract incorrectly claims trust/custody")
    gates = register.get("gates", [])
    if len(gates) != 12 or any(g.get("approved_for_production") is not False for g in gates):
        raise RuntimeError("unapproved production release gate")
    pva = register.get("external_provider_cases", [])
    if len(pva) != 16 or any(x.get("status") != "NOT_RUN_REAL_PROVIDER" for x in pva):
        raise RuntimeError("real provider tests improperly marked passed")
    approvers = register.get("approvals", [])
    if len(approvers) != 7 or any(x.get("status") != "PENDING" for x in approvers):
        raise RuntimeError("independent approvals falsely recorded")
    history = register.get("historical_registers", {})
    a, b = history.get("stage27", {}), history.get("phase16", {})
    if (a.get("reported_total"), a.get("verified_closed"),
            b.get("reported_total"), b.get("reported_blocking")) != (14, 0, 17, 12):
        raise RuntimeError("original release registers improperly conflated")
    emit("source-custody", {
        "allowed_modified_paths": sorted(changed),
        "unchanged_blob_sha": blobs,
        "artifact_sha256": {
            path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
            for path in sorted(REQUIRED)
        },
        "in_process_issuer_only": True,
    })

def junit(kind):
    head()
    folder = Path("target/surefire-reports")
    if not folder.is_dir():
        raise RuntimeError("JUnit XML reports missing")
    cases = set()
    for file in folder.glob("TEST-*.xml"):
        for case in ET.parse(file).getroot().iter("testcase"):
            ident = (case.get("classname"), case.get("name"))
            if not all(ident) or ident in cases:
                raise RuntimeError("missing or duplicate testcase identity")
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise RuntimeError("skipped/failing testcase: " + str(ident))
            cases.add(ident)
    present = {name for owner, name in cases if owner == CLASS}
    if kind == "full" and present != METHODS:
        raise RuntimeError("four sender-constrained test methods not all executed")
    if kind == "security" and present:
        raise RuntimeError("untagged synthetic issuer tests included in SECURITY selection")
    manifests = {}
    if kind == "full":
        directory = Path("target/wave34-10-evidence")
        files = {file.stem: file for file in directory.glob("*.json")}
        if set(files) != SCENARIOS:
            raise RuntimeError("four synthetic issuer manifests missing/extra: " + str(sorted(files)))
        for scenario, file in sorted(files.items()):
            data = json.loads(file.read_text(encoding="utf-8"))
            if (data.get("scenario") != scenario
                    or data.get("source_head") != head()
                    or data.get("fixture") != "IN_PROCESS_JCA_P256_ONLY"
                    or data.get("signature_algorithm") != "SHA256withECDSA"
                    or data.get("release_disposition") != "NO-GO"
                    or data.get("external_issuer_operated") is not False
                    or data.get("durable_replay_store") is not False
                    or data.get("real_payment_processed") is not False
                    or data.get("production_authorized") is not False
                    or not isinstance(data.get("synthetic_accepted_requests"), int)):
                raise RuntimeError("synthetic manifest invalid: " + scenario)
            manifests[scenario] = hashlib.sha256(file.read_bytes()).hexdigest()
    emit("junit-" + kind, {
        "reported_test_cases": len(cases),
        "new_named_methods": sorted(present),
        "manifest_sha256": manifests,
        "limitation": "in-process P-256 contract only; no independent issuer or durable replay",
    })

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("static", "full", "security"):
            raise RuntimeError("usage: wave34_10_release_control_gate.py static|full|security")
        if sys.argv[1] == "static":
            static()
        else:
            junit(sys.argv[1])
    except (RuntimeError, OSError, ValueError, KeyError, TypeError,
            ET.ParseError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print("NO-GO Wave34.10 evidence: " + str(error), file=sys.stderr)
        sys.exit(1)
