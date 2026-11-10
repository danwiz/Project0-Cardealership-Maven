#!/usr/bin/env python3
"""W34.22 exact-head custody, 8-case PG16 evidence, and RELEASE NO-GO controls."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

PARENT = "db5dcf00ec9f3a9455f031fa8523548e310a4ade"
ALLOWED = {
    ".github/workflows/wave34-22-deterministic-pg16-acceptance.yml",
    "ci/wave34_22_release_control_gate.py",
    "docs/audit/wave34/Wave34_22_Deterministic_PG16_Operator_Runbook.md",
    "src/test/java/com/revature/repository/jdbc/Wave3422PgFixture.java",
    "src/test/java/com/revature/repository/jdbc/Wave3422DeterministicPgInterleavingTest.java",
}
FROZEN = [
    "pom.xml",
    "src/main/java/com/revature/repository/jdbc/JdbcDatabase.java",
    "src/main/java/com/revature/repository/jdbc/JdbcPaymentProcessor.java",
    "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlSettlementBatchApprovalTest.java",
    "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlBalancedGlReconciliationTest.java",
    "src/test/java/com/revature/repository/jdbc/Wave3421PostgresqlReplayStressHarness.java",
    "docs/audit/wave34/wave34_15_DISPOSABLE_balanced_gl.sql",
    "docs/audit/wave34/wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql",
    "docs/audit/wave34/wave34_19_DISPOSABLE_witness_revocation_outbox.sql",
    "docs/audit/wave34/Wave34_19_Witness_Outbox_Release_Gate_Register.json",
]
CLASS = "com.revature.repository.jdbc.Wave3422DeterministicPgInterleavingTest"
NAMES = {
    "W3422_01_sameSettlementWaitsOnUncommittedUniqueKeyAndReadbacksExact",
    "W3422_02_foreignBatchMakerApprovalWaitsThenFailsClosed",
    "W3422_03_mutatedSettlementDigestAmountAndApprovalsFailClosed",
    "W3422_04_duplicateGlEventWaitsForCommittedBalancedLines",
    "W3422_05_crossEventProviderSequenceApprovalReuseDenied",
    "W3422_06_deferredBalanceFailureRollsBackPartialHeaderAndLines",
    "W3422_07_deadlockVictimAbortsAndOtherTransactionRecovers",
    "W3422_08_repeatedExactReadbackRejectsChangedIdentity",
}
CASES = {f"W3422-{i:02d}" for i in range(1, 9)}

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

def head():
    value = git("rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", value) or value != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("immutable pull-request head required")
    return value

def write(kind, obj):
    directory = Path("wave34-22-evidence")
    directory.mkdir(parents=True, exist_ok=True)
    obj.update({"source_head": head(), "release_disposition": "NO-GO",
                "production_authorized": False, "review_only": True})
    out = directory / (kind + ".json")
    out.write_text(json.dumps(obj, indent=2, sort_keys=True) + "\n", encoding="utf-8")

def static():
    sha = head()
    subprocess.check_call(["git", "merge-base", "--is-ancestor", PARENT, sha])
    changed = set(filter(None, git("diff", "--name-only", PARENT, sha).splitlines()))
    if changed != ALLOWED:
        raise RuntimeError("unexpected source custody paths: " + repr(changed ^ ALLOWED))
    for path in ALLOWED:
        if not Path(path).is_file():
            raise RuntimeError("missing review-only artifact: " + path)
    frozen = {p: git("rev-parse", sha + ":" + p) for p in FROZEN}
    for path in FROZEN:
        if git("rev-parse", PARENT + ":" + path) != frozen[path]:
            raise RuntimeError("frozen source modified: " + path)
    source = Path("src/test/java/com/revature/repository/jdbc/Wave3422DeterministicPgInterleavingTest.java").read_text()
    if any(name not in source for name in NAMES) or source.count("@Test @Timeout") != 8:
        raise RuntimeError("expected eight explicitly bounded test cases")
    helper = Path("src/test/java/com/revature/repository/jdbc/Wave3422PgFixture.java").read_text()
    for text in ("W34_DISPOSABLE_ONLY", "DROP SCHEMA", "pg_blocking_pids",
                 "wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql",
                 "wave34_15_DISPOSABLE_balanced_gl.sql"):
        if text not in helper:
            raise RuntimeError("missing disposable barrier/cleanup safeguard: " + text)
    docs = Path("docs/audit/wave34/Wave34_22_Deterministic_PG16_Operator_Runbook.md").read_text()
    if "NOT EXECUTED" not in docs or "NO-GO" not in docs:
        raise RuntimeError("test-only status must remain explicit")
    write("source-custody", {"status": "PASS", "changed_paths": sorted(changed),
        "frozen_blobs": frozen, "cases": sorted(CASES),
        "historical_failures_preserved": True,
        "independent_witness": False, "external_receiver": False})

def junit(kind):
    all_cases = set()
    classes = set()
    for file in Path("target/surefire-reports").glob("TEST-*.xml"):
        root = ET.parse(file).getroot()
        for case in root.iter("testcase"):
            identity = (case.get("classname"), case.get("name"))
            if None in identity or identity in all_cases:
                raise RuntimeError("missing or repeated testcase identity")
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise RuntimeError("failed/skipped method " + repr(identity))
            classes.add(identity[0]); all_cases.add(identity)
    expected = (450, 126) if kind == "full" else (151, 64)
    if (len(all_cases), len(classes)) != expected:
        raise RuntimeError("JUnit roster mismatch: " + str((len(all_cases),len(classes))))
    selected = {name for owner,name in all_cases if owner == CLASS}
    if kind == "full":
        if selected != NAMES:
            raise RuntimeError("eight W34.22 methods not present")
        files = set()
        for file in Path("target/wave34-22-evidence").glob("W3422-*.json"):
            files.add(file.stem)
            manifest = json.loads(file.read_text())
            if (manifest.get("case_id") != file.stem
                or manifest.get("source_head") != head()
                or manifest.get("status") != "PASS"
                or not 160000 <= manifest.get("pg_server_version_num", 0) < 170000
                or manifest.get("historical_ddl_modified") is not False
                or manifest.get("real_provider_tested") is not False
                or manifest.get("independent_witness") is not False
                or manifest.get("external_receiver_verified") is not False
                or manifest.get("production_authorized") is not False
                or manifest.get("release_disposition") != "NO-GO"):
                raise RuntimeError("unaccepted bounded PG16 evidence: " + str(file))
        if files != CASES:
            raise RuntimeError("missing/extra exact-head test manifests: " + repr(files ^ CASES))
    elif selected:
        raise RuntimeError("untagged W34.22 methods entered independent SECURITY roster")
    write(kind + "-roster", {"status":"PASS", "method_count":len(all_cases),
        "class_count":len(classes), "new_case_count":len(selected),
        "external_provider_tested": False, "production_release_go": False})

if __name__ == "__main__":
    try:
        mode = sys.argv[1]
        if mode == "static":
            static()
        elif mode in ("full", "security"):
            junit(mode)
        else:
            raise RuntimeError("unknown gate")
    except Exception as exc:
        print("W34.22 gate held: " + str(exc), file=sys.stderr)
        sys.exit(1)
