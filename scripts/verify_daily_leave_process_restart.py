#!/usr/bin/env python3
"""Opt-in API emulator check: saved synthetic facts recompute Today after app process restart.

Requires built debug and androidTest APKs. Refuses existing attendance or policy data;
never clears app storage. The synthetic fixture remains installed after the check.
"""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.hamstercage.preview"
FIXTURE = "dev.hamstercage.data.DailyLeaveProcessFixture"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise SystemExit("Synthetic process check is restricted to an emulator")

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True,
                                       encoding="utf-8", errors="replace")

    def fixture(action, offset=0):
        output = adb("shell", "am", "instrument", "-w", "-e", "class", FIXTURE,
                     "-e", "fixtureAction", action, "-e", "evaluationOffsetSeconds", str(offset),
                     PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in output:
            raise RuntimeError("Synthetic fixture refused or failed; no app data was cleared:\n" + output)
        values = dict(re.findall(r"(fixture\w+)=(\d+)", output))
        if not {"fixturePid", "fixtureExitEpochSecond", "fixtureCreditedSeconds"} <= values.keys():
            raise RuntimeError("Synthetic fixture did not report a bounded Today projection:\n" + output)
        return {key: int(value) for key, value in values.items()}

    adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    adb("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    before = fixture("seed-daily-leave")
    adb("shell", "am", "force-stop", PACKAGE)
    launch = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/dev.hamstercage.MainActivity")
    if "Status: ok" not in launch:
        raise RuntimeError("MainActivity did not restart:\n" + launch)
    after = fixture("probe-daily-leave", offset=90)
    if before["fixturePid"] == after["fixturePid"]:
        raise RuntimeError("Fixture ran in the same process after force-stop")
    if before["fixtureExitEpochSecond"] != after["fixtureExitEpochSecond"]:
        raise RuntimeError("Saved facts produced a different projected leave time after restart")
    if after["fixtureCreditedSeconds"] <= before["fixtureCreditedSeconds"]:
        raise RuntimeError("Fresh process did not recompute increasing current-day credit")
    print("PASS: synthetic saved office/event/policy reopened after MainActivity process restart; "
          f"PID {before['fixturePid']} -> {after['fixturePid']}; "
          f"Today credit {before['fixtureCreditedSeconds']}s -> {after['fixtureCreditedSeconds']}s; "
          f"projected leave epoch {after['fixtureExitEpochSecond']} stable. "
          "Coverage remains unknown; no actual geofence delivery is claimed.")


if __name__ == "__main__":
    main()
