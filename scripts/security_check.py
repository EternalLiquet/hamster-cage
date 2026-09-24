#!/usr/bin/env python3
"""Fail closed on accidental network/backup/unprotected IPC surface in the built app."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"


class AuditFailure(ValueError):
    """An explicitly rejected privacy boundary; never conditional on Python assertions."""


def require(condition, message):
    if not condition:
        raise AuditFailure(message)


def audit(root):
    directory = root / "app/build/intermediates/merged_manifests"
    manifests = list(directory.glob("debug/**/AndroidManifest.xml"))
    require(len(manifests) == 1, "Expected exactly one debug merged manifest; build first and remove ambiguous stale outputs")
    intended = directory / "debug/processDebugManifest/AndroidManifest.xml"
    require(manifests[0] == intended, "Unexpected debug merged-manifest location")
    manifest = ET.parse(intended).getroot()
    require(manifest.tag == "manifest", "Invalid Android manifest root")
    permissions = {p.get(A + "name") for p in manifest.findall("uses-permission")}
    require(all(isinstance(p, str) and p for p in permissions), "Malformed permission entry")
    require("android.permission.INTERNET" not in permissions, "MVP must not have network permission")
    require(not any("STORAGE" in p for p in permissions), "No shared storage permission")
    require(not any("FOREGROUND_SERVICE" in p for p in permissions), "No persistent tracking service")
    app = manifest.find("application")
    require(app is not None, "Manifest must contain an application")
    require(app.get(A + "allowBackup") == "false", "Cloud backup must be disabled")
    require(app.get(A + "usesCleartextTraffic") == "false", "Cleartext traffic must be disabled")
    require(app.get(A + "dataExtractionRules") is not None, "Explicit data extraction rules are required")
    for component in app:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = component.get(A + "name", "")
        if component.get(A + "exported") == "true":
            if name == "dev.hamstercage.MainActivity" and component.tag == "activity":
                require(component.find("intent-filter/action[@" + A + "name='android.intent.action.MAIN']") is not None,
                        "The exported main Activity must be the launcher")
            else:
                # AndroidX endpoints require OS permissions unavailable to ordinary apps.
                require(component.get(A + "permission") in {"android.permission.BIND_JOB_SERVICE", "android.permission.DUMP"},
                        f"Unprotected exported component: {name}")
    for filename in ["backup_rules.xml", "data_extraction_rules.xml"]:
        tree = ET.parse(root / "app/src/main/res/xml" / filename).getroot()
        if filename == "backup_rules.xml":
            require(tree.tag == "full-backup-content", "Invalid backup rules root")
            branches = [tree]
        else:
            require(tree.tag == "data-extraction-rules", "Invalid data extraction root")
            require({branch.tag for branch in tree} == {"cloud-backup", "device-transfer"},
                    "Cloud and device-transfer exclusions are both required")
            branches = list(tree)
        for branch in branches:
            exclusions = {(x.get("domain"), x.get("path")) for x in branch.findall("exclude")}
            for domain in ["root", "database", "file", "sharedpref", "external", "device_root", "device_database", "device_file", "device_sharedpref"]:
                require((domain, ".") in exclusions, f"Missing {domain} backup exclusion")
    for path in (root / "app/src/main").rglob("*.kt"):
        code = path.read_text(encoding="utf-8")
        require("fallbackToDestructiveMigration" not in code, f"Destructive migration in {path}")
        require(not re.search(r"\b(?:Log\.[vdiew]|println)\s*\(", code), f"Unreviewed runtime logging in {path}")
    require(not list((root / "app/src").rglob("*.jpg")), "Reference artwork must not be bundled")


if __name__ == "__main__":
    try:
        audit(ROOT)
    except (AuditFailure, ET.ParseError, OSError) as error:
        sys.exit(f"FAIL: {error}")
    print("PASS: merged manifest, app-private/backup boundary, IPC and sensitive logging checks")
