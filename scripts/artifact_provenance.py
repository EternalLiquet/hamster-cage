#!/usr/bin/env python3
"""Bind one checked-out commit to the actual debug APK, checksum and signer."""

import hashlib
import json
from pathlib import Path
import re
import subprocess


APK_NAME = "hamster-cage-preview.apk"
CERT_LINE = re.compile(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")


def build_record(root: Path, commit: str) -> str:
    dist = root / "dist"
    apk = dist / APK_NAME
    metadata = json.loads((root / "app/build/outputs/apk/debug/output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata.get("elements", [])
    if len(elements) != 1 or elements[0].get("outputFile") != "app-debug.apk":
        raise ValueError("Expected one app-debug.apk output")
    element = elements[0]
    package = metadata["applicationId"]
    version = element["versionName"]
    code = element["versionCode"]
    if package != "dev.hamstercage.preview" or not version.endswith("-debug") or not isinstance(code, int) or code < 1:
        raise ValueError("Artifact is not the debug preview variant")

    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    sums = (dist / "SHA256SUMS").read_text(encoding="utf-8").strip().split()
    if len(sums) != 2 or sums[1] != APK_NAME or sums[0].lower() != digest:
        raise ValueError("SHA256SUMS does not match the packaged APK")
    signature = (dist / "SIGNATURE.txt").read_text(encoding="utf-8")
    certificates = CERT_LINE.findall(signature)
    if len(certificates) != 1:
        raise ValueError("Expected one verified signing-certificate SHA-256 digest")

    return "\n".join([
        f"Source commit: {commit}",
        f"Artifact: {APK_NAME}",
        f"Package: {package}",
        f"Version: {version} ({code})",
        "Build: debug preview; debuggable and development-signed, not a production release.",
        f"APK SHA-256: {digest}",
        f"Signing certificate SHA-256: {certificates[0].lower()}",
        "Compare SHA256SUMS and SIGNATURE.txt with this record before installing.",
        "Different development keys cannot update each other. Uninstalling erases local data.",
        "Physical-phone geofence delivery, permission recovery and battery checks are separate.",
    ]) + "\n"


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip():
        raise SystemExit("Commit or remove source changes before recording artifact provenance")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    (root / "dist/BUILD.txt").write_text(build_record(root, commit), encoding="utf-8")


if __name__ == "__main__":
    main()
