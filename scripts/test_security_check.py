"""Exercise the CLI under Python optimization against synthetic build outputs."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parent
XML = SOURCE.parent / "app/src/main/res/xml"
SAFE_MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:allowBackup="false" android:usesCleartextTraffic="false"
android:dataExtractionRules="@xml/data_extraction_rules" /></manifest>'''


class SecurityAuditTest(unittest.TestCase):
    def fixture(self, root, manifest=SAFE_MANIFEST):
        script = root / "scripts/security_check.py"
        script.parent.mkdir()
        shutil.copyfile(SOURCE / "security_check.py", script)
        target = root / "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
        target.parent.mkdir(parents=True)
        target.write_text(manifest, encoding="utf-8")
        rules = root / "app/src/main/res/xml"
        shutil.copytree(XML, rules)
        return script, target, rules

    def run_modes(self, script, success, expected):
        for flags, optimized in [([], None), (["-O"], None), ([], "1")]:
            with self.subTest(flags=flags, PYTHONOPTIMIZE=optimized):
                environment = dict(os.environ)
                environment.pop("PYTHONOPTIMIZE", None)
                if optimized is not None:
                    environment["PYTHONOPTIMIZE"] = optimized
                result = subprocess.run([sys.executable, *flags, str(script)], capture_output=True, text=True, env=environment, check=False)
                self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
                self.assertIn(expected, result.stdout + result.stderr)
                if not success:
                    self.assertNotIn("PASS:", result.stdout)

    def test_valid_fixture_passes_all_interpreter_modes(self):
        with tempfile.TemporaryDirectory() as directory:
            script, _, _ = self.fixture(Path(directory))
            self.run_modes(script, True, "PASS:")

    def test_forbidden_network_permission_rejected_under_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            unsafe = SAFE_MANIFEST.replace("<application", '<uses-permission android:name="android.permission.INTERNET" /><application')
            script, _, _ = self.fixture(Path(directory), unsafe)
            self.run_modes(script, False, "network permission")

    def test_malformed_manifest_rejected_under_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            script, _, _ = self.fixture(Path(directory), "<manifest><application>")
            self.run_modes(script, False, "FAIL:")

    def test_ambiguous_manifests_rejected_under_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            script, target, _ = self.fixture(Path(directory))
            duplicate = target.parent.parent / "stale/AndroidManifest.xml"
            duplicate.parent.mkdir()
            duplicate.write_text(SAFE_MANIFEST, encoding="utf-8")
            self.run_modes(script, False, "exactly one")

    def test_empty_backup_rules_rejected_under_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            script, _, rules = self.fixture(Path(directory))
            (rules / "data_extraction_rules.xml").write_text("<data-extraction-rules />", encoding="utf-8")
            self.run_modes(script, False, "both required")


if __name__ == "__main__":
    unittest.main()
