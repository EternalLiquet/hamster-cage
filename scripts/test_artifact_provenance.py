"""The published record must match actual bytes, variant and signing identity."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from artifact_provenance import build_record


class ArtifactProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        dist = self.root / "dist"
        dist.mkdir()
        output = self.root / "app/build/outputs/apk/debug"
        output.mkdir(parents=True)
        (output / "output-metadata.json").write_text(json.dumps({
            "applicationId": "dev.hamstercage.preview",
            "elements": [{"outputFile": "app-debug.apk", "versionName": "0.1.0-preview-debug", "versionCode": 2}],
        }), encoding="utf-8")
        self.apk = dist / "hamster-cage-preview.apk"
        self.apk.write_bytes(b"synthetic apk fixture")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        (dist / "SHA256SUMS").write_text(f"{self.digest}  hamster-cage-preview.apk\n", encoding="utf-8")
        (dist / "SIGNATURE.txt").write_text(
            "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n", encoding="utf-8")

    def test_record_binds_commit_bytes_version_and_signer(self):
        record = build_record(self.root, "c" * 40)
        self.assertIn("Source commit: " + "c" * 40, record)
        self.assertIn("Version: 0.1.0-preview-debug (2)", record)
        self.assertIn("APK SHA-256: " + self.digest, record)
        self.assertIn("Signing certificate SHA-256: " + "a" * 64, record)
        self.assertIn("not a production release", record)

    def test_changed_apk_is_rejected_before_publication(self):
        self.apk.write_bytes(b"changed synthetic apk")
        with self.assertRaisesRegex(ValueError, "SHA256SUMS does not match"):
            build_record(self.root, "c" * 40)

    def test_missing_signer_or_wrong_variant_is_rejected(self):
        (self.root / "dist/SIGNATURE.txt").write_text("verified without certificate digest\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "signing-certificate"):
            build_record(self.root, "c" * 40)
        metadata = self.root / "app/build/outputs/apk/debug/output-metadata.json"
        wrong = json.loads(metadata.read_text(encoding="utf-8"))
        wrong["applicationId"] = "dev.hamstercage"
        metadata.write_text(json.dumps(wrong), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "debug preview"):
            build_record(self.root, "c" * 40)


if __name__ == "__main__":
    unittest.main()
