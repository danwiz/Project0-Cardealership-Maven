#!/usr/bin/env python3
"""Draft integration gate: summarize and enforce JUnit Surefire XML counts.
Not a substitute for source-method roster attestation or independent approval.
"""
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

def check(label, expected_methods, expected_classes, directory, output):
    files = sorted(pathlib.Path(directory).glob("TEST-*.xml"))
    if not files:
        raise SystemExit("NO_GO: no Surefire XML reports")
    methods = set()
    classes = set()
    for file in files:
        root = ET.parse(file).getroot()
        for case in root.iter("testcase"):
            name = case.attrib.get("name", "")
            owner = case.attrib.get("classname", "")
            if not name or not owner:
                raise SystemExit(f"NO_GO: incomplete testcase in {file.name}")
            key = (owner, name)
            if key in methods:
                raise SystemExit(f"NO_GO: duplicate test {key}")
            methods.add(key)
            classes.add(owner)
            if any(child.tag in ("failure", "error", "skipped") for child in case):
                raise SystemExit(f"NO_GO: failure, error or skip for {key}")
    if len(methods) != expected_methods or len(classes) != expected_classes:
        raise SystemExit(
            f"NO_GO: {label} expected {expected_methods} methods/{expected_classes} "
            f"classes, got {len(methods)}/{len(classes)}"
        )
    record = {
        "label": label,
        "status": "PASS_COUNT_ONLY",
        "actual_methods": len(methods),
        "actual_classes": len(classes),
        "xml_files": len(files),
        "limitation": "Exact named-method manifest comparison remains an independent release gate",
    }
    pathlib.Path(output).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(output).write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record))

if __name__ == "__main__":
    if len(sys.argv) != 6:
        raise SystemExit("Usage: check_surefire.py LABEL METHODS CLASSES XML_DIR OUTPUT")
    check(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5])
