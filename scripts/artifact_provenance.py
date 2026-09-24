#!/usr/bin/env python3
"""Record actual checked-out source and packaged variant, not a PR merge ref."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
if subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip():
    raise SystemExit("Commit or remove source changes before recording artifact provenance")
metadata = json.loads((root / "app/build/outputs/apk/debug/output-metadata.json").read_text())
element = metadata["elements"][0]
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
lines = [
    f"Source commit: {commit}",
    f"Package: {metadata['applicationId']}",
    f"Version: {element['versionName']} ({element['versionCode']})",
    "Build: debug preview; development signing, not a production release.",
    "SHA-256: see SHA256SUMS. Public signing-certificate digest: see SIGNATURE.txt.",
    "Different development keys cannot update each other. Uninstalling erases local data.",
    "Physical-phone geofence delivery, permission recovery and battery checks are separate.",
]
(root / "dist/BUILD.txt").write_text("\n".join(lines) + "\n")
