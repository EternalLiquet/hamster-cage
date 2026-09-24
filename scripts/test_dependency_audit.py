"""Exercise scanner failure behavior without using the network or real findings."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import dependency_audit


class DependencyAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.reports = self.root / "app/build/reports"
        self.reports.mkdir(parents=True)
        (self.reports / "runtime-dependencies.json").write_text('[{"name":"example:synthetic","version":"1.0"}]')
        root_patch = patch.object(dependency_audit, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

    def response(self, results):
        return patch.object(dependency_audit.urllib.request, "urlopen", return_value=io.BytesIO(json.dumps({"results": results}).encode()))

    def test_complete_clean_response_passes(self):
        with self.response([{}]):
            dependency_audit.main()
        report = json.loads((self.reports / "dependency-audit.json").read_text())
        self.assertEqual({"packages_checked": 1, "findings": []}, report)

    def test_known_vulnerability_fails_and_preserves_finding(self):
        with self.response([{"vulns": [{"id": "SYNTHETIC-TEST-1"}]}]):
            with self.assertRaisesRegex(SystemExit, "Known runtime"):
                dependency_audit.main()
        report = json.loads((self.reports / "dependency-audit.json").read_text())
        self.assertEqual("SYNTHETIC-TEST-1", report["findings"][0]["vulnerabilities"][0]["id"])

    def test_incomplete_response_cannot_pass(self):
        with self.response([]):
            with self.assertRaisesRegex(SystemExit, "Incomplete"):
                dependency_audit.main()

    def test_paginated_response_cannot_pass(self):
        with self.response([{"next_page_token": "more-results"}]):
            with self.assertRaisesRegex(SystemExit, "Paginated"):
                dependency_audit.main()

    def test_unavailable_service_cannot_pass(self):
        with patch.object(dependency_audit.urllib.request, "urlopen", side_effect=OSError("synthetic offline")):
            with self.assertRaises(OSError):
                dependency_audit.main()
        self.assertFalse((self.reports / "dependency-audit.json").exists())

    def test_empty_inventory_cannot_pass(self):
        (self.reports / "runtime-dependencies.json").write_text("[]")
        with self.assertRaisesRegex(SystemExit, "empty"):
            dependency_audit.main()


if __name__ == "__main__":
    unittest.main()
