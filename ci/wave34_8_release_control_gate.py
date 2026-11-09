#!/usr/bin/env python3
"""Wave34.8 fail-closed source custody and role-test evidence; NEVER GO authority."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

BASE = "588dfb0661f38dc561316d5cc1358ca60e18091b"
CHANGED = {
    "docs/audit/wave34/wave34_8_DISPOSABLE_authenticated_principal_fence.sql",
    "src/test/java/com/revature/repository/jdbc/Wave34PostgresqlPrincipalIsolationTest.java",
    "docs/audit/wave34/ADR-W34-010-Database-Authenticated-Writer-Isolation.md",
    "docs/audit/wave34/Wave34_8_Operator_Role_Isolation_Runbook.md",
    "docs/audit/wave34/Wave34_8_Role_Trust_Gate_Register.json",
    "ci/wave34_8_release_control_gate.py",
    ".github/workflows/wave34-8-role-trust-regression.yml",
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
)
CLASS = "com.revature.repository.jdbc.Wave34PostgresqlPrincipalIsolationTest"
METHODS = {
    "databaseAuthenticatedPrincipalNotCallerEpochControlsWrites",
    "restrictedWriterCannotChangeEpochRegistryDisableTriggerOrImpersonateOperator",
    "independentOperatorFreezeWaitsForAuthenticatedWriterTransaction",
}
SCENARIOS = {
    "authenticated-epoch-transition",
    "restricted-privilege-denials",
    "independent-operator-drain",
}
REGISTRY = Path("docs/audit/wave34/Wave34_8_Role_Trust_Gate_Register.json")
OUT = Path("wave34-8-evidence")

def git(*args):
    return subprocess.check_output(["git", *args], text=True,
                                   stderr=subprocess.STDOUT).strip()

def head():
    value = git("rev-parse", "HEAD")
    if not re.fullmatch("[0-9a-f]{40}", value) or value != os.environ.get("EXPECTED_HEAD"):
        raise RuntimeError("exact immutable checked-out GitHub head not proven")
    return value

def write(kind, facts):
    OUT.mkdir(parents=True, exist_ok=True)
    record = {
        "kind":kind, "status":"PASS_EVIDENCE_ONLY", "source_head":head(),
        "release_disposition":"NO-GO", "production_authorized":False,
        "external_epoch_issuer_verified":False, "stage27_gates_closed":0,
        **facts
    }
    (OUT / (kind+".json")).write_text(
        json.dumps(record, indent=2, sort_keys=True)+"\n", encoding="utf-8")
    print(json.dumps(record, indent=2, sort_keys=True))

def static():
    head()
    git("merge-base", "--is-ancestor", BASE, "HEAD")
    if os.environ.get("EXPECTED_PARENT") != BASE:
        raise RuntimeError("Wave34.7 parent SHA is wrong")
    diff = set(filter(None, git("diff", "--name-only", BASE, "HEAD").splitlines()))
    if diff != CHANGED:
        raise RuntimeError("unexpected changed source paths: "+str(sorted(diff ^ CHANGED)))
    blobs = {}
    for name in FROZEN:
        old = git("rev-parse", BASE+":"+name)
        now = git("rev-parse", "HEAD:"+name)
        if old != now:
            raise RuntimeError("app source/schema/old fixture modified: "+name)
        blobs[name] = now
    for name in CHANGED:
        file = Path(name)
        if not file.is_file() or file.stat().st_size < 250:
            raise RuntimeError("missing/empty proposed artifact: "+name)
        if name.endswith(".md"):
            content = file.read_text(encoding="utf-8")
            if "PROPOSED" not in content or "NO-GO" not in content:
                raise RuntimeError("unapproved proposal labeled GO: "+name)
    sql = Path("docs/audit/wave34/wave34_8_DISPOSABLE_authenticated_principal_fence.sql").read_text()
    if (sql.count("-- W348_STATEMENT_BREAK") != 6
            or "__SCHEMA__" not in sql
            or "NOT A PRODUCTION MIGRATION" not in sql
            or "session_user" not in sql
            or "SECURITY DEFINER" not in sql):
        raise RuntimeError("privilege/SQL fixture drift")
    record = json.loads(REGISTRY.read_text(encoding="utf-8"))
    if record.get("parent_head_sha") != BASE or record.get("release_disposition") != "NO-GO":
        raise RuntimeError("wrong baseline/release status")
    invariants = record.get("invariants", {})
    if (invariants.get("app_schema_version") != 5
            or any(v is not False for k,v in invariants.items()
                   if k != "app_schema_version")):
        raise RuntimeError("unapproved production actions claimed")
    targets = record.get("qa_targets", {})
    if (targets.get("full", {}).get("methods"), targets.get("full", {}).get("classes"),
            targets.get("security", {}).get("methods"),
            targets.get("security", {}).get("classes")) != (399,114,151,64):
        raise RuntimeError("CI roster changed without review")
    cases = record.get("cases", [])
    if (len(cases) != 3 or {c.get("method") for c in cases} != METHODS
            or any(c.get("status") != "PENDING_EXACT_SHA_CI" for c in cases)):
        raise RuntimeError("synthetic cases omitted or prematurely certified")
    gates = record.get("gates", [])
    if len(gates) != 12 or any(g.get("approved_for_production") is not False for g in gates):
        raise RuntimeError("unapproved operator gate marked approved")
    pva = record.get("external_provider_cases", [])
    if len(pva) != 16 or any(x.get("status") != "NOT_RUN_REAL_PROVIDER" for x in pva):
        raise RuntimeError("real-provider cases improperly closed")
    owners = record.get("approvals", [])
    if len(owners) != 7 or any(x.get("status") != "PENDING" for x in owners):
        raise RuntimeError("independent approval incorrectly asserted")
    hist = record.get("historical_registers", {})
    a,b = hist.get("stage27", {}),hist.get("phase16", {})
    if (a.get("reported_total"),a.get("verified_closed"),
            b.get("reported_total"),b.get("reported_blocking")) != (14,0,17,12):
        raise RuntimeError("original Stage27/Phase16 counts altered")
    manifest = record.get("manifest_contract", {})
    if set(manifest.get("scenarios",[])) != SCENARIOS or manifest.get("signed") is not False:
        raise RuntimeError("test manifest/cryptographic custody wrongly asserted")
    write("scope", {
        "modified_files":sorted(diff),
        "unchanged_blob_sha":blobs,
        "artifact_sha256":{
            f:hashlib.sha256(Path(f).read_bytes()).hexdigest() for f in sorted(CHANGED)
        },
        "privileged_test_superuser_is_not_independent_issuer":True
    })

def junit(kind):
    head()
    folder=Path("target/surefire-reports")
    if not folder.is_dir(): raise RuntimeError("JUnit report directory missing")
    cases=set()
    for file in folder.glob("TEST-*.xml"):
        for t in ET.parse(file).getroot().iter("testcase"):
            identity=(t.get("classname"), t.get("name"))
            if not all(identity) or identity in cases:
                raise RuntimeError("duplicate/missing JUnit testcase identity")
            if any(t.find(x) is not None for x in ("failure","error","skipped")):
                raise RuntimeError("skipped or failed testcase: "+str(identity))
            cases.add(identity)
    actual={name for owner,name in cases if owner==CLASS}
    if kind=="full" and actual!=METHODS:
        raise RuntimeError("missing exact principal-isolation test methods: "+str(actual))
    if kind=="security" and actual:
        raise RuntimeError("untagged role test leaked into SECURITY selection")
    manifest_hashes={}
    if kind=="full":
        dir=Path("target/wave34-8-evidence")
        found={p.stem:p for p in dir.glob("*.json")}
        if set(found)!=SCENARIOS:
            raise RuntimeError("missing/extra scenario manifest(s): "+str(sorted(found)))
        for scenario,path in sorted(found.items()):
            j=json.loads(path.read_text(encoding="utf-8"))
            if (j.get("scenario")!=scenario or j.get("head_sha")!=head()
                    or j.get("fixture")!="DISPOSABLE_PG16_ROLES_ONLY"
                    or j.get("authorization_basis")!="POSTGRESQL_SESSION_USER"
                    or j.get("release")!="NO-GO" or j.get("schema_version")!=5
                    or j.get("mode") not in ("OPEN","FROZEN")
                    or not isinstance(j.get("epoch"),int)
                    or j.get("external_epoch_issuer_verified") is not False
                    or j.get("production_fencing_verified") is not False
                    or j.get("production_authorized") is not False
                    or not re.fullmatch("[0-9a-f]{64}",j.get("ledger_sha256",""))):
                raise RuntimeError("invalid synthetic role evidence: "+scenario)
            manifest_hashes[scenario]=hashlib.sha256(path.read_bytes()).hexdigest()
    write("junit-"+kind,{
        "all_executed_methods":len(cases),
        "new_named_methods":sorted(actual),
        "synthetic_manifest_sha256":manifest_hashes,
        "limitation":"Restricted local PostgreSQL LOGIN roles, not independent trusted issuer"
    })

if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("static","full","security"):
            raise RuntimeError("usage: wave34_8_release_control_gate.py static|full|security")
        if sys.argv[1]=="static": static()
        else: junit(sys.argv[1])
    except (OSError,RuntimeError,ValueError,KeyError,TypeError,
            ET.ParseError,subprocess.CalledProcessError,json.JSONDecodeError) as e:
        print("NO-GO Wave34.8 evidence: "+str(e),file=sys.stderr)
        sys.exit(1)
