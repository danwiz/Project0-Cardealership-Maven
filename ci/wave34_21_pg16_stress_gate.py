#!/usr/bin/env python3
"""W34.21-B non-release, exact-head source-custody and stress-evidence gate."""
import json
import os
import pathlib
import re
import subprocess
import sys

PARENT = "d4e784cc69e9497f6b7b56bce27134b2ef027a28"
ALLOWED = {
    ".github/workflows/wave34-21-pg16-replay-stress.yml",
    "ci/wave34_21_pg16_stress_gate.py",
    "docs/audit/wave34/Wave34_21_B_Review_Only_Stress_Runbook.md",
    "src/test/java/com/revature/repository/jdbc/Wave3421PostgresqlReplayStressHarness.java",
}

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

def exact_head():
    head = git("rev-parse", "HEAD")
    expected = os.environ.get("EXPECTED_HEAD", "")
    if not re.fullmatch(r"[0-9a-f]{40}", expected) or head != expected:
        raise ValueError("Exact source-head custody mismatch")
    return head

def static():
    head = exact_head()
    subprocess.check_call(["git", "merge-base", "--is-ancestor", PARENT, head])
    changed = set(filter(None, git("diff", "--name-only", PARENT, head).splitlines()))
    if changed != ALLOWED:
        raise ValueError("W34.21-B unexpected changed paths: " + repr(changed ^ ALLOWED))
    for file in ALLOWED:
        if not pathlib.Path(file).is_file():
            raise ValueError("W34.21-B missing review-only artifact: " + file)
    frozen = ["pom.xml",
              "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlSettlementBatchApprovalTest.java",
              "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlBalancedGlReconciliationTest.java",
              "docs/audit/wave34/wave34_15_DISPOSABLE_balanced_gl.sql",
              "docs/audit/wave34/wave34_16_DISPOSABLE_settlement_batch_reconciliation.sql",
              "docs/audit/wave34/wave34_19_DISPOSABLE_witness_revocation_outbox.sql",
              "docs/audit/wave34/Wave34_19_Witness_Outbox_Release_Gate_Register.json"]
    for file in frozen:
        if git("rev-parse", PARENT + ":" + file) != git("rev-parse", head + ":" + file):
            raise ValueError("FROZEN file changed: " + file)
    print(json.dumps({"scope": "REVIEW_ONLY", "source_head": head,
                      "changed_paths": sorted(changed),
                      "production_authorized": False, "release_disposition": "NO-GO"},
                     sort_keys=True))

def stress():
    head = exact_head()
    issue = int(os.environ["W3421_ISSUE"])
    shard = int(os.environ["W3421_SHARD"])
    java = os.environ["W3421_JDK"]
    if issue not in [51, 53] or shard not in range(4) or java not in ["11", "17"]:
        raise ValueError("Unexpected stress matrix dimension")
    path = pathlib.Path("wave34-21-evidence") / (
        f"issue-{issue}-jdk-{java}-shard-{shard}.jsonl"
    )
    entries = [json.loads(line) for line in path.read_text().splitlines()]
    if len(entries) != 250:
        raise ValueError(f"Expected 250 attempts, found {len(entries)}")
    for offset, x in enumerate(entries):
        index = shard * 250 + offset
        if (x.get("issue") != issue or str(x.get("jdk")) != java
                or x.get("shard") != shard or x.get("schedule_index") != index
                or x.get("outer_seed") != 342100000 + issue * 100000 + index
                or x.get("source_head") != head or x.get("status") != "PASS"
                or not 160000 <= x.get("pg_server_version_num", 0) < 170000):
            raise ValueError(f"Failed or incomplete schedule record {index}")
    print(json.dumps({"source_head": head, "issue": issue, "jdk": java,
                      "shard": shard, "successful_replays": 250,
                      "formal_race_reproduction": False,
                      "pg_internal_interleavings_replayable": False,
                      "production_authorized": False,
                      "release_disposition": "NO-GO"}, sort_keys=True))

if __name__ == "__main__":
    try:
        {"static": static, "stress": stress}[sys.argv[1]]()
    except (KeyError, IndexError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print("W34.21-B gate blocked:", error, file=sys.stderr)
        sys.exit(1)
