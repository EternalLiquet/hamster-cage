#!/usr/bin/env python3
"""Fail closed on private files or unreviewed assets in an actual APK archive.

This complements manifest/backup, source, signature and dependency checks; it
does not establish that arbitrary application code or image pixels are safe.
"""
import argparse
from pathlib import Path
import re
import sys
import zipfile

ALLOWED_ASSETS = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
PRIVATE_FILE = re.compile(r"\.(?:(?:db|sqlite3?)(?:-wal|-shm|-journal)?|preferences_pb|keystore|jks|key|pem|p12|pfx|hprof|log)$", re.I)


def audit(apk):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive entries need review")
        if not {"AndroidManifest.xml", "resources.arsc", "classes.dex"} <= set(names):
            raise ValueError("Expected application APK entries are missing")
        for entry in entries:
            name = entry.filename
            normalized = name.lower()
            if entry.orig_filename != name or name.startswith("/") or "\\" in name or ":" in name or any(part in {".", ".."} for part in name.split("/")):
                raise ValueError("Ambiguous archive path needs review")
            if PRIVATE_FILE.search(normalized) or any(x in normalized for x in ("visual-reference", "gpt-projects-files")):
                raise ValueError("Private data, key, log or reference path in APK")
            if normalized.startswith("assets/") and not normalized.endswith("/") and name not in ALLOWED_ASSETS:
                raise ValueError("Unreviewed packaged asset")
        if archive.testzip() is not None:
            raise ValueError("Invalid archive checksum")
        return len(names)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        count = audit(args.apk)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(f"FAIL: {error}")
    print(f"PASS: {count} packaged entries; no private files or unreviewed assets")
