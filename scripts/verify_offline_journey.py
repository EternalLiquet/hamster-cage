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
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.hamstercage.preview"


def offline_device_state(airplane: str, wifi: str, connectivity: str) -> bool:
    return airplane == "1" and wifi == "0" and re.search(
        r"(?m)^Active default network:\s*none\s*$", connectivity
    ) is not None


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
        output = adb("shell", "am", "instrument", "-w", "-e", "class", "dev.hamstercage.ui.OfflineJourneyHostTest#completeOfflineJourneyAndFreshProcessReopen",
                     "-e", "offlineJourneyAction", action, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in output or "FAILURES" in output:
            raise RuntimeError("Synthetic journey failed; source data was not erased.\n" + output)

    adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    adb("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    wifi = adb("shell", "settings", "get", "global", "wifi_on").strip()
    data = adb("shell", "settings", "get", "global", "mobile_data").strip()
    airplane = adb("shell", "settings", "get", "global", "airplane_mode_on").strip()
    try:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        # On some emulators mobile_data remains 1 in settings even after the radio
        # disconnects. Require airplane mode, Wi-Fi off and no default network.
        deadline = time.monotonic() + 15
        while True:
            if offline_device_state(
                adb("shell", "settings", "get", "global", "airplane_mode_on").strip(),
                adb("shell", "settings", "get", "global", "wifi_on").strip(),
                adb("shell", "dumpsys", "connectivity"),
            ):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Airplane mode/Wi-Fi/default network did not become offline; evidence is unavailable")
            time.sleep(0.5)
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
                       serial=args.serial, networking="Airplane mode on, Wi-Fi off, no active default network during journey",
                       locationPermission="Fine location denied; correction still saved", physicalDevice=False)
        output = ROOT / "app/build/reports/offline-journey"
        output.mkdir(parents=True, exist_ok=True)
        (output / "RESULT.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print("PASS: real Activity office/capture-fixture/correction/calendar flow, offline and denied-location operation, Activity recreation, fresh-process persistence and confirmed privacy deletion/restart")
    finally:
        if airplane != "1":
            adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
        if wifi == "1":
            adb("shell", "svc", "wifi", "enable")
        if data == "1":
            adb("shell", "svc", "data", "enable")


if __name__ == "__main__":
    main()
