"""Verify committed branding provenance and resource wiring without the private source."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
A = "{http://schemas.android.com/apk/res/android}"


class LauncherAssetTest(unittest.TestCase):
    def test_derived_assets_match_the_pinned_provenance_inventory(self):
        record = json.loads((ROOT / "docs/branding-resources.json").read_text())
        self.assertEqual(record["source_sha256"], "6c1904727b647cf36c08eda6d799e4529e925b3720c961df821c50ab108675f6")
        self.assertEqual(len(record["assets"]), 12)
        for name, expected in record["assets"].items():
            path = ROOT / name
            with self.subTest(asset=name):
                self.assertTrue(path.resolve().is_relative_to(RES.resolve()))
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected)

    def test_legacy_resources_cover_standard_android_launcher_sizes(self):
        for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
            for name in ["ic_launcher", "ic_launcher_round"]:
                data = (RES / f"mipmap-{density}/{name}.png").read_bytes()
                self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
                self.assertEqual(struct.unpack(">II", data[16:24]), (size, size))

    def test_manifest_and_both_adaptive_variants_have_local_layers(self):
        app = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        self.assertEqual(app.get(A + "icon"), "@mipmap/ic_launcher")
        self.assertEqual(app.get(A + "roundIcon"), "@mipmap/ic_launcher_round")
        for api in [26, 33]:
            for name in ["ic_launcher", "ic_launcher_round"]:
                icon = ET.parse(RES / f"mipmap-anydpi-v{api}/{name}.xml").getroot()
                self.assertEqual(icon.tag, "adaptive-icon")
                self.assertEqual(icon.find("background").get(A + "drawable"), "@color/ic_launcher_background")
                self.assertEqual(icon.find("foreground").get(A + "drawable"), "@drawable/ic_launcher_foreground")
                if api == 33:
                    self.assertEqual(icon.find("monochrome").get(A + "drawable"), "@drawable/ic_launcher_monochrome")


if __name__ == "__main__":
    unittest.main()
