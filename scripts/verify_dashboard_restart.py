#!/usr/bin/env python3
"""Opt-in synthetic emulator check: persisted timer survives a real app-process stop/restart.

Requires built debug/test APKs and an empty preview database. Does not erase or uninstall data.
The synthetic fixture remains on the emulator and must not be mistaken for personal attendance.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.hamstercage.preview"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise SystemExit("This synthetic source check is restricted to an emulator; no physical phone is modified")

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True, encoding="utf-8", errors="replace")

    def device_seconds():
        return int(adb("shell", "date", "+%s").strip())

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/hamster-dashboard-restart.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/hamster-dashboard-restart.xml"))

    def launch():
        output = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/dev.hamstercage.MainActivity")
        if "Status: ok" not in output:
            raise RuntimeError("Preview activity did not start successfully")
        return adb("shell", "pidof", PACKAGE).strip()

    def assert_credit(start):
        for _ in range(5):
            before = device_seconds()
            xml = tree()
            after = device_seconds()
            credit = [node.get("text") for node in xml.iter("node") if node.get("resource-id", "").endswith("today_credit")]
            state = [node.get("text") for node in xml.iter("node") if node.get("resource-id", "").endswith("office_state")]
            expected = {f"{(value - start) // 60}m" for value in [before, after]}
            if len(credit) == 1 and credit[0] in expected and state == ["Office state unknown"]:
                return credit[0], xml
            time.sleep(0.5)
        raise RuntimeError(f"Restart credit/state did not match persisted source and current device time: {credit}, {state}")

    adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    adb("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    seed = adb("shell", "am", "instrument", "-w", "-e", "class", "dev.hamstercage.data.DashboardRestartFixture",
               "-e", "fixtureAction", "seed-dashboard-restart", PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    match = re.search(r"fixtureStartEpochSecond=(\d+)", seed)
    if not match or "OK (1 test)" not in seed:
        raise RuntimeError("Synthetic fixture refused to seed; existing source data was not reset")
    start = int(match[1])
    first_pid = launch()
    first_credit, first_xml = assert_credit(start)
    adb("shell", "am", "force-stop", PACKAGE)
    # Advance to the next device minute while the app has no running process.
    next_minute = start + (int(first_credit[:-1]) + 1) * 60 + 1
    while (remaining := next_minute - device_seconds()) > 0:
        time.sleep(min(20, remaining))
    second_pid = launch()
    second_credit, second_xml = assert_credit(start)
    if first_pid == second_pid or int(second_credit[:-1]) <= int(first_credit[:-1]):
        raise RuntimeError("A fresh app process did not reconstruct increased credit from persisted source")
    output = ROOT / "app/build/reports/dashboard-restart"
    output.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(first_xml).write(output / "before.xml", encoding="utf-8")
    ET.ElementTree(second_xml).write(output / "after.xml", encoding="utf-8")
    evidence = f"PASS: actual process restart {first_pid} -> {second_pid}; recorded credit {first_credit} -> {second_credit}; office state remains unknown.\nSynthetic source remains on emulator; no personal attendance or physical-device check.\n"
    (output / "RESULT.txt").write_text(evidence, encoding="utf-8")
    print(evidence)


if __name__ == "__main__":
    main()
