"""Adversarial bootstrap tests run before any Gradle invocation in CI."""
from pathlib import Path
import shutil
import tempfile
import unittest
from verify_wrapper import ROOT, verify


class WrapperIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.wrapper = self.root / "gradle/wrapper"
        shutil.copytree(ROOT / "gradle/wrapper", self.wrapper)

    def test_reviewed_wrapper_passes(self):
        verify(self.root)

    def test_tampered_jar_is_rejected(self):
        with (self.wrapper / "gradle-wrapper.jar").open("ab") as jar:
            jar.write(b"unexpected-payload")
        with self.assertRaisesRegex(ValueError, "JAR checksum"):
            verify(self.root)

    def test_missing_distribution_checksum_is_rejected(self):
        path = self.wrapper / "gradle-wrapper.properties"
        path.write_text("\n".join(line for line in path.read_text().splitlines() if not line.startswith("distributionSha256Sum=")))
        with self.assertRaisesRegex(ValueError, "distribution checksum"):
            verify(self.root)

    def test_unreviewed_distribution_host_is_rejected(self):
        path = self.wrapper / "gradle-wrapper.properties"
        path.write_text(path.read_text().replace("services.gradle.org", "untrusted.invalid"))
        with self.assertRaisesRegex(ValueError, "distribution URL"):
            verify(self.root)


if __name__ == "__main__":
    unittest.main()
