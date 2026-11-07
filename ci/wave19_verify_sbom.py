#!/usr/bin/env python3
"""Fail-closed structure and exact-SHA validation of Wave19 Maven dependency + CycloneDX artifacts.

This is a dependency inventory gate ONLY. It does not run SCA, evaluate
vulnerabilities, approve source licenses or attest an artifact signature.
"""
import hashlib
import json
import os
import pathlib
import sys

ROOT=pathlib.Path("wave19-sbom")
TREE=ROOT/"dependency-tree.json"
BOM=pathlib.Path("target/bom.json")
BOM_XML=pathlib.Path("target/bom.xml")

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def validate():
    for p in (TREE,BOM,BOM_XML):
        if not p.is_file() or p.stat().st_size<100:
            raise RuntimeError("missing or empty required inventory: "+str(p))
    tree=json.loads(TREE.read_text(encoding="utf-8"))
    bom=json.loads(BOM.read_text(encoding="utf-8"))
    if not isinstance(tree,dict) or not isinstance(tree.get("children"),list):
        raise RuntimeError("no parsed resolved Maven dependency tree")
    if not tree["children"]:
        raise RuntimeError("resolved Maven dependency tree contains no dependencies")
    if bom.get("bomFormat")!="CycloneDX":
        raise RuntimeError("unexpected BOM format")
    components=bom.get("components")
    if not isinstance(components,list) or len(components)<3:
        raise RuntimeError("CycloneDX component inventory missing/incomplete")
    expected={"com.h2database:h2","org.postgresql:postgresql","org.junit.jupiter:junit-jupiter"}
    observed={str(x.get("group",""))+":"+str(x.get("name","")) for x in components
              if isinstance(x,dict)}
    missing=sorted(expected-observed)
    if missing:
        raise RuntimeError("missing direct dependencies in BOM: "+str(missing))
    commit=os.environ.get("EXPECTED_HEAD","")
    if len(commit)!=40 or any(ch not in "0123456789abcdef" for ch in commit):
        raise RuntimeError("missing exact immutable commit SHA")
    payload={
        "status":"PASS_INVENTORY_ONLY",
        "candidate_sha":commit,
        "dependency_tree_nodes":len(tree["children"]),
        "bom_components":len(components),
        "direct_components_verified":sorted(expected),
        "license_assessment":"NOT PERFORMED",
        "vulnerability_scan":"NOT PERFORMED",
        "secrets_scan":"NOT PERFORMED",
        "artifact_signing":"NOT PERFORMED",
        "hashes":{
            str(p):sha256(p)
            for p in (TREE,BOM,BOM_XML)
        }
    }
    ROOT.mkdir(parents=True,exist_ok=True)
    (ROOT/"inventory-check.json").write_text(json.dumps(payload,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(payload,indent=2))

if __name__=="__main__":
    try:
        validate()
    except (RuntimeError,ValueError,OSError,KeyError,json.JSONDecodeError) as exc:
        print("NO-GO: "+str(exc),file=sys.stderr)
        sys.exit(1)
