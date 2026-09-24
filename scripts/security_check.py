#!/usr/bin/env python3
"""Fail closed on accidental network/backup/unprotected IPC surface in the built app."""
from pathlib import Path
import argparse
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


def audit(root, variant="debug"):
    require(variant in {"debug", "release"}, "Unsupported build variant")
    directory = root / "app/build/intermediates/merged_manifests"
    manifests = list(directory.glob(f"{variant}/**/AndroidManifest.xml"))
    require(len(manifests) == 1, f"Expected exactly one {variant} merged manifest; build first and remove ambiguous stale outputs")
    intended = directory / f"{variant}/process{variant.title()}Manifest/AndroidManifest.xml"
    require(manifests[0] == intended, "Unexpected debug merged-manifest location")
    manifest = ET.parse(intended).getroot()
    require(manifest.tag == "manifest", "Invalid Android manifest root")
    # SDK-conditioned declarations still grant permission on supported devices.
    # Retain the legacy M alias because Android manifest parsers accept it too.
    permission_tags = {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"}
    permission_elements = [element for element in manifest if element.tag in permission_tags]
    permissions = {element.get(A + "name") for element in permission_elements}
    require(all(isinstance(p, str) and p for p in permissions), "Malformed permission entry")
    require("android.permission.INTERNET" not in permissions, "MVP must not have network permission")
    require(not any("STORAGE" in p for p in permissions), "No shared storage permission")
    require(not any("FOREGROUND_SERVICE" in p for p in permissions), "No persistent tracking service")
    package = manifest.get("package", "")
    internal_permission = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    # Location is staged by #17. New permission surfaces need an explicit reviewed audit change.
    reviewed_permissions = {"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
                            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.RECEIVE_BOOT_COMPLETED",
                            internal_permission}
    require(permissions <= reviewed_permissions, "Unreviewed Android permission")
    if internal_permission in permissions:
        definitions = [item for item in manifest.findall("permission") if item.get(A + "name") == internal_permission]
        require(len(definitions) == 1 and definitions[0].get(A + "protectionLevel") == "signature",
                "Internal receiver permission must be signature protected")
    app = manifest.find("application")
    require(app is not None, "Manifest must contain an application")
    require(app.get(A + "allowBackup") == "false", "Cloud backup must be disabled")
    require(app.get(A + "usesCleartextTraffic") == "false", "Cleartext traffic must be disabled")
    require(app.get(A + "dataExtractionRules") == "@xml/data_extraction_rules", "Manifest must use the audited data extraction rules")
    require(app.get(A + "fullBackupContent") == "@xml/backup_rules", "Manifest must use the audited backup rules")
    if variant == "release":
        require(app.get(A + "debuggable", "false") == "false", "Release must not be debuggable")
        require(app.get(A + "testOnly", "false") == "false", "Release must not be test-only")
    for component in app:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = component.get(A + "name", "")
        require(component.get(A + "exported") in {"true", "false"}, f"Component needs explicit exported state: {name}")
        if name == "dev.hamstercage.capture.GeofenceTransitionReceiver":
            require(component.tag == "receiver" and component.get(A + "exported") == "false" and
                    component.get(A + "permission") is None and not component.findall("intent-filter"),
                    "Geofence transition receiver must remain private and filter-free")
        if component.tag == "provider":
            require(component.get(A + "exported") == "false" and component.get(A + "grantUriPermissions", "false") == "false"
                    and component.find("grant-uri-permission") is None, "No exported or grantable data provider")
        if component.get(A + "exported") == "true":
            if name == "dev.hamstercage.MainActivity" and component.tag == "activity":
                filters = component.findall("intent-filter")
                require(len(filters) == 1 and len(filters[0]) == 2 and {(item.tag, item.get(A + "name")) for item in filters[0]} == {
                    ("action", "android.intent.action.MAIN"), ("category", "android.intent.category.LAUNCHER")},
                    "The exported main Activity must be only the launcher")
            elif name == "dev.hamstercage.capture.CaptureRecoveryReceiver" and component.tag == "receiver":
                filters = component.findall("intent-filter")
                require("android.permission.RECEIVE_BOOT_COMPLETED" in permissions and
                        component.get(A + "permission") is None and len(filters) == 1 and
                        len(filters[0]) == 2 and {(item.tag, item.get(A + "name")) for item in filters[0]} == {
                            ("action", "android.intent.action.BOOT_COMPLETED"),
                            ("action", "android.intent.action.MY_PACKAGE_REPLACED")},
                        "Recovery receiver must accept only reviewed protected system actions")
            else:
                # A permission string alone must not authorize an arbitrary new endpoint.
                require((component.tag, name, component.get(A + "permission")) == (
                    "receiver", "androidx.profileinstaller.ProfileInstallReceiver", "android.permission.DUMP"),
                    f"Unreviewed exported component: {name}")
    for filename in ["backup_rules.xml", "data_extraction_rules.xml"]:
        audited_rules = root / "app/src/main/res/xml" / filename
        require(set((root / "app/src").glob(f"*/res/xml*/{filename}")) == {audited_rules},
                "Backup resource overlays need an explicit security review")
        tree = ET.parse(audited_rules).getroot()
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
        require(not re.search(r"\b(?:Log\.(?:[vdiew]|wtf|println)|print(?:ln)?|printStackTrace)\s*\(", code),
                f"Unreviewed runtime logging in {path}")
        require(not re.search(r"\b(?:requestLocationUpdates|requestSingleUpdate|startLocationUpdates|getCurrentLocation|getLastLocation)\s*\(", code),
                f"Continuous or direct location collection needs explicit review: {path}")
    require(not list((root / "app/src").rglob("*.jpg")), "Reference artwork must not be bundled")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=["debug", "release"], default="debug")
    options = parser.parse_args()
    try:
        audit(ROOT, options.variant)
    except (AuditFailure, ET.ParseError, OSError) as error:
        sys.exit(f"FAIL: {error}")
    print("PASS: merged manifest, app-private/backup boundary, IPC and sensitive logging checks")
