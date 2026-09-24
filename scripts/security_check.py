#!/usr/bin/env python3
"""Fail closed on accidental network/backup/unprotected IPC surface in the built MVP."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"
manifests = list((ROOT / "app/build/intermediates/merged_manifests").glob("debug/**/AndroidManifest.xml"))
assert manifests, "Build the debug APK before auditing its merged manifest"
manifest = ET.parse(manifests[0]).getroot()
permissions = {p.get(A + "name") for p in manifest.findall("uses-permission")}
assert "android.permission.INTERNET" not in permissions, "MVP must not have network permission"
assert not any("STORAGE" in p for p in permissions), "No shared storage permission"
assert not any("FOREGROUND_SERVICE" in p for p in permissions), "No persistent tracking service"
app = manifest.find("application")
assert app is not None
assert app.get(A + "allowBackup") == "false", "Cloud backup must be disabled"
assert app.get(A + "usesCleartextTraffic") == "false"
assert app.get(A + "dataExtractionRules") is not None
for component in app:
    if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
        continue
    name = component.get(A + "name", "")
    if component.get(A + "exported") == "true":
        if name.endswith(".MainActivity"):
            assert component.find("intent-filter/action[@" + A + "name='android.intent.action.MAIN']") is not None
        else:
            # WorkManager/AndroidX endpoints require signature-level OS permissions.
            assert component.get(A + "permission") in {"android.permission.BIND_JOB_SERVICE", "android.permission.DUMP"}, f"Unprotected exported component: {name}"
for rules in ["backup_rules.xml", "data_extraction_rules.xml"]:
    tree = ET.parse(ROOT / "app/src/main/res/xml" / rules).getroot()
    branches = [tree] if rules == "backup_rules.xml" else list(tree)
    for branch in branches:
        exclusions = {(x.get("domain"), x.get("path")) for x in branch.findall("exclude")}
        for domain in ["root", "database", "file", "sharedpref", "external", "device_root", "device_database", "device_file", "device_sharedpref"]:
            assert (domain, ".") in exclusions, f"Missing {domain} backup exclusion"
for path in (ROOT / "app/src/main").rglob("*.kt"):
    code = path.read_text()
    assert "fallbackToDestructiveMigration" not in code, f"Destructive migration in {path}"
    assert not re.search(r"\b(?:Log\.[vdiew]|println)\s*\(", code), f"Unreviewed runtime logging in {path}"
assert not list((ROOT / "app/src").rglob("*.jpg")), "Reference artwork must not be bundled"
print("PASS: merged manifest, app-private/backup boundary, IPC and sensitive logging checks")
