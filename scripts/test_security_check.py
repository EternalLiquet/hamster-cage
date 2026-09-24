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
<uses-permission android:name="android.permission.INTERNET" />
<application android:allowBackup="false" android:usesCleartextTraffic="false"
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/backup_rules" /></manifest>'''


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

    def run_modes(self, script, success, expected, arguments=()):
        for flags, optimized in [([], None), (["-O"], None), ([], "1")]:
            with self.subTest(flags=flags, PYTHONOPTIMIZE=optimized):
                environment = dict(os.environ)
                environment.pop("PYTHONOPTIMIZE", None)
                if optimized is not None:
                    environment["PYTHONOPTIMIZE"] = optimized
                result = subprocess.run([sys.executable, *flags, str(script), *arguments], capture_output=True, text=True, env=environment, check=False)
                self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
                self.assertIn(expected, result.stdout + result.stderr)
                if not success:
                    self.assertNotIn("PASS:", result.stdout)

    def test_valid_fixture_passes_all_interpreter_modes(self):
        with tempfile.TemporaryDirectory() as directory:
            script, _, _ = self.fixture(Path(directory))
            self.run_modes(script, True, "PASS:")

    def test_reviewed_network_permission_is_allowed_without_cleartext(self):
        with tempfile.TemporaryDirectory() as directory:
            reviewed = SAFE_MANIFEST
            script, _, _ = self.fixture(Path(directory), reviewed)
            self.run_modes(script, True, "PASS:")
            target = Path(directory) / "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
            target.write_text(reviewed.replace('android:usesCleartextTraffic="false"', 'android:usesCleartextTraffic="true"'), encoding="utf-8")
            self.run_modes(script, False, "Cleartext traffic")
            target.write_text(reviewed.replace('<uses-permission android:name="android.permission.INTERNET" />', ''), encoding="utf-8")
            self.run_modes(script, False, "requires exactly one INTERNET permission")

    def test_malformed_manifest_rejected_under_optimization(self):
        with tempfile.TemporaryDirectory() as directory:
            script, _, _ = self.fixture(Path(directory), "<manifest><application>")
            self.run_modes(script, False, "FAIL:")

    def test_sdk_conditioned_forbidden_permissions_rejected_in_all_modes(self):
        for tag in ["uses-permission-sdk-23", "uses-permission-sdk-m"]:
            for permission in ["INTERNET", "READ_EXTERNAL_STORAGE", "FOREGROUND_SERVICE_LOCATION"]:
                with self.subTest(tag=tag, permission=permission), tempfile.TemporaryDirectory() as directory:
                    unsafe = SAFE_MANIFEST.replace("<application", f'<{tag} android:name="android.permission.{permission}" /><application')
                    script, _, _ = self.fixture(Path(directory), unsafe)
                    self.run_modes(script, False, "FAIL:")

    def test_sdk_conditioned_malformed_permissions_rejected_in_all_modes(self):
        for tag in ["uses-permission-sdk-23", "uses-permission-sdk-m"]:
            with self.subTest(tag=tag), tempfile.TemporaryDirectory() as directory:
                unsafe = SAFE_MANIFEST.replace("<application", f"<{tag} /><application")
                script, _, _ = self.fixture(Path(directory), unsafe)
                self.run_modes(script, False, "Malformed permission entry")

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

    def test_manifest_cannot_point_to_unaudited_backup_resources(self):
        for reference in ["@xml/data_extraction_rules", "@xml/backup_rules"]:
            with self.subTest(reference=reference), tempfile.TemporaryDirectory() as directory:
                unsafe = SAFE_MANIFEST.replace(reference, "@xml/other_rules")
                script, _, _ = self.fixture(Path(directory), unsafe)
                self.run_modes(script, False, "must use the audited")

    def test_new_sensitive_permission_and_weak_internal_permission_are_rejected(self):
        for permission in ['<uses-permission android:name="android.permission.READ_CONTACTS" />',
                           '<permission android:name=".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="normal" />'
                           '<uses-permission android:name=".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />']:
            with self.subTest(permission=permission), tempfile.TemporaryDirectory() as directory:
                script, _, _ = self.fixture(Path(directory), SAFE_MANIFEST.replace("<application", permission + "<application"))
                self.run_modes(script, False, "FAIL:")

    def test_arbitrary_exported_component_cannot_borrow_a_reviewed_permission(self):
        for component in [
            '<receiver android:name="synthetic.UnreviewedReceiver" android:exported="true" android:permission="android.permission.DUMP" />',
            '<service android:name="synthetic.UnreviewedService" android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE" />',
            '<receiver android:name="synthetic.ImplicitReceiver"><intent-filter><action android:name="synthetic.ACTION" /></intent-filter></receiver>',
        ]:
            with self.subTest(component=component), tempfile.TemporaryDirectory() as directory:
                manifest = SAFE_MANIFEST.replace(" /></manifest>", ">" + component + "</application></manifest>")
                script, _, _ = self.fixture(Path(directory), manifest)
                self.run_modes(script, False, "FAIL:")

    def test_recovery_receiver_accepts_only_exact_system_actions(self):
        component = '<receiver android:name="dev.hamstercage.capture.CaptureRecoveryReceiver" android:exported="true"><intent-filter>' \
            '<action android:name="android.intent.action.BOOT_COMPLETED" />' \
            '<action android:name="android.intent.action.MY_PACKAGE_REPLACED" />' \
            '</intent-filter></receiver>'
        for candidate, allowed in [(component, True),
                                   (component.replace("MY_PACKAGE_REPLACED", "PACKAGE_REPLACED"), False),
                                   (component.replace("BOOT_COMPLETED", "synthetic.ACTION"), False)]:
            with self.subTest(candidate=candidate), tempfile.TemporaryDirectory() as directory:
                manifest = SAFE_MANIFEST.replace("<application", '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" /><application')
                manifest = manifest.replace(" /></manifest>", ">" + candidate + "</application></manifest>")
                script, _, _ = self.fixture(Path(directory), manifest)
                self.run_modes(script, allowed, "PASS:" if allowed else "Recovery receiver")

    def test_geofence_transition_receiver_stays_private_and_filter_free(self):
        safe = '<receiver android:name="dev.hamstercage.capture.GeofenceTransitionReceiver" android:exported="false" />'
        candidates = [(safe, True), (safe.replace('android:exported="false"', 'android:exported="true"'), False),
                      (safe.replace(' />', '><intent-filter><action android:name="synthetic.ACTION" /></intent-filter></receiver>'), False)]
        for candidate, allowed in candidates:
            with self.subTest(candidate=candidate), tempfile.TemporaryDirectory() as directory:
                manifest = SAFE_MANIFEST.replace(" /></manifest>", ">" + candidate + "</application></manifest>")
                script, _, _ = self.fixture(Path(directory), manifest)
                self.run_modes(script, allowed, "PASS:" if allowed else "Geofence transition receiver")

    def test_direct_location_collection_requires_review(self):
        for call in ("requestLocationUpdates(request, callback)", "getCurrentLocation(priority, token)",
                     "getLastLocation()"):
            with self.subTest(call=call), tempfile.TemporaryDirectory() as directory:
                script, _, _ = self.fixture(Path(directory))
                source = Path(directory) / "app/src/main/java/synthetic/Tracking.kt"
                source.parent.mkdir(parents=True)
                source.write_text("client." + call, encoding="utf-8")
                expected = "One-shot location must stay in reviewed office setup" if call.startswith("getCurrentLocation") else "Continuous or cached location collection"
                self.run_modes(script, False, expected)

    def test_network_calls_outside_office_setup_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            script, _, _ = self.fixture(Path(directory))
            source = Path(directory) / "app/src/main/java/synthetic/Tracking.kt"
            source.parent.mkdir(parents=True)
            source.write_text('URL("https://example.test/visit")', encoding="utf-8")
            self.run_modes(script, False, "Network calls must stay in reviewed office lookup/map setup")

    def test_office_tile_provider_must_be_exact_https_host_without_redirects(self):
        for code, expected in [
            ('URL("https://unreviewed.example/visit").openConnection()\ninstanceFollowRedirects = false', "reviewed HTTPS provider"),
            ('URL("https://tile.openstreetmap.org/16/1/1.png").openConnection()', "must not follow redirects"),
        ]:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                script, _, _ = self.fixture(Path(directory))
                source = Path(directory) / "app/src/main/java/dev/hamstercage/offices/OfficeLocationServices.kt"
                source.parent.mkdir(parents=True)
                source.write_text(code, encoding="utf-8")
                self.run_modes(script, False, expected)

    def test_office_lookup_rejects_cleartext_and_continuous_location(self):
        for code, expected in [('URL("http://example.test/map")', "Office lookup/map must not use cleartext HTTP"),
                               ("requestLocationUpdates(request, callback)", "Continuous or cached location collection")]:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                script, _, _ = self.fixture(Path(directory))
                source = Path(directory) / "app/src/main/java/dev/hamstercage/offices/OfficeLocationServices.kt"
                source.parent.mkdir(parents=True)
                source.write_text(code, encoding="utf-8")
                self.run_modes(script, False, expected)

    def test_provider_must_neither_export_nor_grant_data(self):
        for attributes in ['android:exported="true" android:permission="android.permission.DUMP"',
                           'android:exported="false" android:grantUriPermissions="true"']:
            with self.subTest(attributes=attributes), tempfile.TemporaryDirectory() as directory:
                component = f'<provider android:name="synthetic.DataProvider" {attributes} />'
                script, _, _ = self.fixture(Path(directory), SAFE_MANIFEST.replace(" /></manifest>", ">" + component + "</application></manifest>"))
                self.run_modes(script, False, "No exported or grantable")

    def test_launcher_must_not_silently_add_a_data_or_intent_surface(self):
        safe = '<activity android:name="dev.hamstercage.MainActivity" android:exported="true"><intent-filter>' \
               '<action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" />' \
               '</intent-filter></activity>'
        for extra in ['', '<data android:scheme="synthetic" />', '<action android:name="android.intent.action.SEND" />']:
            with self.subTest(extra=extra), tempfile.TemporaryDirectory() as directory:
                activity = safe.replace('</intent-filter>', extra + '</intent-filter>')
                script, _, _ = self.fixture(Path(directory), SAFE_MANIFEST.replace(" /></manifest>", ">" + activity + "</application></manifest>"))
                self.run_modes(script, not extra, "only the launcher" if extra else "PASS:")

    def test_release_debug_or_test_flags_are_rejected(self):
        for attribute in ['', 'android:debuggable="true"', 'android:testOnly="true"']:
            with self.subTest(attribute=attribute), tempfile.TemporaryDirectory() as directory:
                script, target, _ = self.fixture(Path(directory), SAFE_MANIFEST.replace("<application", "<application " + attribute))
                release = target.parents[2] / "release/processReleaseManifest/AndroidManifest.xml"
                release.parent.mkdir(parents=True)
                shutil.copyfile(target, release)
                self.run_modes(script, not attribute, "Release must not" if attribute else "PASS:", ["--variant", "release"])

    def test_sensitive_diagnostic_logging_is_rejected(self):
        for code in ['Log.wtf("tag", "synthetic")', 'Log.println(1, "tag", "synthetic")',
                     'print("synthetic")', 'failure.printStackTrace()']:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                script, _, _ = self.fixture(Path(directory))
                source = Path(directory) / "app/src/main/java/synthetic/Diagnostic.kt"
                source.parent.mkdir(parents=True)
                source.write_text(code, encoding="utf-8")
                self.run_modes(script, False, "Unreviewed runtime logging")

    def test_variant_or_qualified_backup_overrides_cannot_bypass_audit(self):
        for relative in ["release/res/xml/backup_rules.xml", "main/res/xml-v31/data_extraction_rules.xml"]:
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                script, _, _ = self.fixture(Path(directory))
                override = Path(directory) / "app/src" / relative
                override.parent.mkdir(parents=True)
                override.write_text("<unrestricted-backup />", encoding="utf-8")
                self.run_modes(script, False, "Backup resource overlays")


if __name__ == "__main__":
    unittest.main()
