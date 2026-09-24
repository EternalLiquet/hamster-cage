"""Adversarial archive fixtures for the final packaged-data boundary."""
import io
import unittest
import warnings
import zipfile
from apk_inventory import audit


def fixture(extra=()):
    data = io.BytesIO()
    with warnings.catch_warnings(), zipfile.ZipFile(data, "w") as archive:
        warnings.simplefilter("ignore", UserWarning)
        for name in ("AndroidManifest.xml", "resources.arsc", "classes.dex", *extra):
            entry = zipfile.ZipInfo("placeholder")
            entry.filename = entry.orig_filename = name  # Keep adversarial bytes on Windows too.
            archive.writestr(entry, b"synthetic archive fixture, not an installable APK")
    data.seek(0)
    return data


class ApkInventoryTest(unittest.TestCase):
    def test_reviewed_code_resources_and_public_signature_are_allowed(self):
        self.assertEqual(7, audit(fixture(("res/mipmap-mdpi/ic_launcher.png", "META-INF/CERT.RSA",
                                          "assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"))))

    def test_private_payloads_and_unreviewed_assets_are_rejected(self):
        for name in ("assets/attendance.db", "res/raw/record.DB-wal", "res/raw/policy.preferences_pb",
                     "assets/backup.json", "res/raw/signing.JKS", "private.key", "capture.log",
                     "visual-reference/hamster.png", "assets/unknown.bin"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                audit(fixture((name,)))

    def test_ambiguous_paths_and_duplicate_entries_are_rejected(self):
        for name in ("/private.dat", "../private.dat", "res\\private.dat", "C:/private.dat", "classes.dex"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                audit(fixture((name,)))


if __name__ == "__main__":
    unittest.main()
