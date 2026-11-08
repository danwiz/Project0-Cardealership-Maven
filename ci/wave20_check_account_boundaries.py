#!/usr/bin/env python3
"""Check Wave20 privilege and custom hasher negative cases actually ran and passed."""
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

EXPECTED = {
    "com.revature.repository.jdbc.PostgresqlAccountPrivilegeBoundaryTest":
        {"ordinaryReadOnlyRuntimeCannotPromoteDeleteOrCreateAccounts"},
    "com.revature.service.CustomHasherTrustedAdministrationBoundaryTest":
        {"unsupportedConfiguredPasswordHasherNeverBypassesTrustedAdministrator"},
}
found = {}
for classname, expected in EXPECTED.items():
    xml_file = Path("target/surefire-reports") / ("TEST-" + classname + ".xml")
    if not xml_file.is_file():
        raise SystemExit("NO_GO: missing Wave20 test XML " + classname)
    root = ET.parse(xml_file).getroot()
    values = {}
    for item in root.iter("testcase"):
        name = item.attrib.get("name","")
        if name in values:
            raise SystemExit("NO_GO: duplicate test " + classname + ":" + name)
        bad = [element for element in ("failure","error","skipped")
               if item.find(element) is not None]
        values[name] = "PASS" if not bad else "+".join(bad)
    if set(values) != expected or set(values.values()) != {"PASS"}:
        raise SystemExit("NO_GO: Wave20 test identity/status error: " + str(values))
    found[classname] = values

dest = Path(sys.argv[1]) if len(sys.argv)>1 else Path("wave20-evidence/privilege-tests.json")
dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps({"status":"PASS","test_methods":2,"classes":2,
                            "cases":found,
                            "scope":"real PostgreSQL16 SELECT-only account role + unsupported custom hasher",
                            "not_verified":["production role grants","privileged SQL role isolation",
                                            "all hasher plugins or credential migrations"]},
                           indent=2)+"\n",encoding="utf-8")
print("Wave20: both new security/privilege regressions PASS without skips")
