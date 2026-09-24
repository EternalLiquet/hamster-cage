#!/usr/bin/env python3
"""Opt-in real-Activity journey on a fresh synthetic emulator preview installation.

Installs built debug/test APKs without deleting data. The fixture refuses existing
attendance/offices. Network controls are restored; synthetic facts remain afterward.
This is not an OS geofence-delivery or physical-phone test.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.hamstercage.preview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise SystemExit("This synthetic journey is restricted to an emulator")

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True, encoding="utf-8", errors="replace")

    def check(action):
        output = adb("shell", "am", "instrument", "-w", "-e", "class", "dev.hamstercage.ui.OfflineJourneyHostTest",
                     "-e", "offlineJourneyAction", action, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in output or "FAILURES" in output:
            raise RuntimeError("Synthetic journey failed; source data was not erased.\n" + output)

    adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    adb("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    wifi = adb("shell", "settings", "get", "global", "wifi_on").strip()
    data = adb("shell", "settings", "get", "global", "mobile_data").strip()
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        if adb("shell", "settings", "get", "global", "wifi_on").strip() != "0":
            raise RuntimeError("Wi-Fi did not turn off; offline evidence is unavailable")
        if adb("shell", "settings", "get", "global", "mobile_data").strip() != "0":
            raise RuntimeError("Mobile data did not turn off; offline evidence is unavailable")
        check("exercise")
        adb("shell", "am", "force-stop", PACKAGE)
        check("restart")
        check("delete")
        adb("shell", "am", "force-stop", PACKAGE)
        check("deleted-restart")
        receipt = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/synthetic-offline-journey.json"))
        if not receipt.get("freshProcessVerified") or receipt["pid"] == receipt["reopenedPid"]:
            raise RuntimeError("A different app process did not verify the saved record")
        if not receipt.get("deletedRestartVerified") or receipt["deletedPid"] == receipt["afterDeleteReopenedPid"]:
            raise RuntimeError("Confirmed deletion did not remain deleted in a different process")
        receipt.update(sourceSha=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                       api=adb("shell", "getprop", "ro.build.version.sdk").strip(),
                       serial=args.serial, networking="Wi-Fi and mobile data disabled during journey",
                       locationPermission="Fine location denied; correction still saved", physicalDevice=False)
        output = ROOT / "app/build/reports/offline-journey"
        output.mkdir(parents=True, exist_ok=True)
        (output / "RESULT.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print("PASS: real Activity office/capture-fixture/correction/calendar flow, offline and denied-location operation, Activity recreation, fresh-process persistence and confirmed privacy deletion/restart")
    finally:
        if wifi == "1":
            adb("shell", "svc", "wifi", "enable")
        if data == "1":
            adb("shell", "svc", "data", "enable")


if __name__ == "__main__":
    main()
