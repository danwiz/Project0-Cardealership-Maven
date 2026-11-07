#!/usr/bin/env python3
"""Read-only exact-SHA OSV vulnerability lookups for the Wave20 CycloneDX BOM.

Queries all resolved Maven components by group:artifact and version through the
documented OSV /v1/querybatch endpoint. Preserves findings rather than auto-
waiving them. A valid query is *not* proof that there are no other vulnerabilities.
No credentials, writes to OSV, build/deploy operations, or GitHub mutations.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

ENDPOINT = "https://api.osv.dev/v1/querybatch"
BOM = Path("target/bom.json")
OUTPUT = Path("wave20-sca")
LIMIT_COMPONENTS = 250
MAX_HTTP_BYTES = 3 * 1024 * 1024

def main():
    sha = os.environ.get("EXPECTED_HEAD", "")
    if len(sha) != 40 or any(ch not in "0123456789abcdef" for ch in sha):
        raise RuntimeError("exact immutable source SHA required")
    if not BOM.is_file() or BOM.stat().st_size < 100:
        raise RuntimeError("nonempty CycloneDX BOM required")
    raw = BOM.read_bytes()
    doc = json.loads(raw)
    if doc.get("bomFormat") != "CycloneDX":
        raise RuntimeError("not a CycloneDX BOM")
    components = doc.get("components")
    if not isinstance(components, list) or not (3 <= len(components) <= LIMIT_COMPONENTS):
        raise RuntimeError("missing or unsupported BOM component count")

    coordinates = []
    for c in components:
        if not isinstance(c, dict):
            raise RuntimeError("unrecognized BOM component")
        group, artifact, version = (c.get("group"), c.get("name"), c.get("version"))
        if not all(isinstance(x, str) and x.strip() for x in (group, artifact, version)):
            raise RuntimeError("unqueryable Maven dependency: incomplete coordinates")
        coord = {"name": group + ":" + artifact, "version": version}
        if coord in coordinates:
            raise RuntimeError("duplicate Maven package coordinate " + str(coord))
        coordinates.append(coord)
    coordinates.sort(key=lambda x: (x["name"], x["version"]))
    payload = {"queries": [
        {"package": {"ecosystem":"Maven", "name": c["name"]}, "version": c["version"]}
        for c in coordinates
    ]}
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(payload, separators=(",",":")).encode("utf-8"),
        headers={"Content-Type":"application/json","Accept":"application/json",
                 "User-Agent":"wisdom-dealership-wave20-readonly-sca/1.0"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=35) as response:
        if response.status != 200:
            raise RuntimeError("OSV API returned unsuccessful HTTP status")
        data = response.read(MAX_HTTP_BYTES + 1)
        if len(data) > MAX_HTTP_BYTES:
            raise RuntimeError("OSV reply size exceeds controlled limit")
        raw_result = json.loads(data)

    results = raw_result.get("results")
    if not isinstance(results,list) or len(results) != len(coordinates):
        raise RuntimeError("OSV batch response does not match full package count")
    normalized = []
    for i, row in enumerate(results):
        if not isinstance(row,dict) or row.get("next_page_token"):
            raise RuntimeError("unexpected/paginated OSV result, cannot claim complete coverage")
        vulns = row.get("vulns",[])
        if not isinstance(vulns,list):
            raise RuntimeError("OSV reported unrecognized vulnerability payload")
        ids=[]
        for v in vulns:
            if not isinstance(v,dict) or not isinstance(v.get("id"),str) or not v["id"]:
                raise RuntimeError("OSV returned incomplete vulnerability ID")
            ids.append(v["id"])
        normalized.append({
            "package":coordinates[i]["name"],
            "version":coordinates[i]["version"],
            "advisory_ids":sorted(set(ids)),
            "osv_matches":len(set(ids)),
            "component_status":"OSV_MATCH_REVIEW" if ids else "NO_KNOWN_OSV_MATCH_AT_QUERY_TIME",
        })

    OUTPUT.mkdir(parents=True,exist_ok=True)
    (OUTPUT/"osv_query.json").write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
    (OUTPUT/"osv_raw_response.json").write_text(json.dumps(raw_result,indent=2)+"\n",encoding="utf-8")
    (OUTPUT/"osv_component_results.json").write_text(json.dumps(normalized,indent=2)+"\n",encoding="utf-8")
    findings = sum(c["osv_matches"] for c in normalized)
    report = {
        "source_sha":sha,
        "checked_utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "api":ENDPOINT,
        "ecosystem":"Maven",
        "bom_sha256":hashlib.sha256(raw).hexdigest(),
        "component_count":len(components),
        "queried_count":len(normalized),
        "packages_with_findings":sum(c["osv_matches"]>0 for c in normalized),
        "package_advisory_matches":findings,
        "scan_status":"MATCHES_REQUIRE_TRIAGE" if findings else "NO_KNOWN_OSV_MATCHES_AT_QUERY_TIME",
        "release_status":"NOT_APPROVED",
        "limitations":[
            "OSV only, not a complete vulnerability or malicious-code assessment",
            "Results time-sensitive and affected by OSV coverage",
            "Build plugin dependency closure not yet inventoried",
            "No legal license review, secret scan or signed release attestation",
            "An OSV match needs independent applicability, fix and exception triage",
        ],
    }
    (OUTPUT/"osv_summary.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2))
    # Diagnostic evidence must be available even when findings exist. Release
    # approval is NEVER granted by this workflow.
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError,ValueError,RuntimeError,urllib.error.URLError,json.JSONDecodeError) as error:
        print("NO-GO OSV QUERY: "+str(error),file=sys.stderr)
        sys.exit(1)
