# Integrated offline acceptance (#33)

The computer-runnable acceptance journey uses the real `MainActivity`, Room/DataStore and engine. Its opt-in fixture refuses an existing record. It creates two synthetic offices through the UI, supplies explicitly synthetic observations through the capture parser/repository boundary, compares dashboard totals with the same engine, confirms a correction while fine location permission is denied, changes target/timezone policy, verifies that a WFH label preserves the requirement, adds a holiday, recreates the Activity and verifies the persisted record from a different process. It then checks deletion cancellation, confirmed attendance/calendar deletion with offices and base policy retained, and another fresh-process reopen. Synthetic parser input does **not** establish OS geofence delivery.

`scripts/verify_offline_journey.py` installs built debug/test APKs on an emulator, disables Wi-Fi/mobile data for the journey, checks both controls, restores their prior enabled states and writes a report under `app/build/reports/offline-journey`. It never clears or uninstalls existing data. The Android CI job runs this host journey after the ordinary instrumentation suite, which uninstalls its test packages, so the opt-in journey begins with a fresh preview installation. Public evidence contains synthetic records only.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify_offline_journey.py --serial emulator-5554
```

The full API37 host journey passed at source `1b286418038b9432194b4933fc685dd89e84adff`, after privacy integration. The [receipt](evidence/issue-33/offline-journey.json) records process 12275 → 12388 retaining two offices, four synthetic events, a correction, target/timezone, WFH and holiday; confirmed deletion ran in process 12454 and reopened empty in process 12524 with offices/base policy retained. Wi-Fi/mobile data were disabled and fine location remained denied throughout. The earlier pre-privacy receipt remains explicitly preliminary. Current-head CI and independent verification/review remain required; this local run does not establish physical-phone acceptance.

## Engine acceptance coverage

The existing domain suites supply the full 24-case baseline below; CI runs them on the exact PR head. This is a mapping to behavioral assertions, not a replacement test suite. `A` is `AttendanceEngineTest`, `C` is `CalendarMetricsTest`, and `D` is `DepartureTest` in `core-domain/src/test/kotlin/dev/hamstercage/domain`.

| # | Behavior asserted | Existing test |
| --- | --- | --- |
| 1 | Observed time and credited grace stay distinct | `A.cleanSingleSessionSeparatesRawFromGrace` |
| 2 | Split visit totals the documented 380 minutes | `A.splitLunchMatchesSourceExample380Minutes` |
| 3 | Each office supplies its own grace | `A.customGracePerOffice` |
| 4 | Overlapping grace does not add duplicate minutes | `A.graceOverlapUnionDoesNotDoubleCount` |
| 5 | Gap threshold retains reconciliation provenance | `A.shortGapAtThresholdIsReconciledWithProvenance` |
| 6 | Repeated arrival retains the earliest boundary with review | `A.repeatedEnterPreservesEarliestAndFlagsLowConfidence` |
| 7 | Repeated departure does not invent a new visit | `A.repeatedExitDoesNotInventAnotherSessionStart` |
| 8 | An unmatched departure has no invented start | `A.exitWithoutEnterOrInstallInsideOfficeIsUnknownNotInventedHistory` |
| 9 | An open visit receives no future exit grace | `A.openSessionGetsEntryGraceButNoFutureExitGrace` |
| 10 | Installation inside an office cannot backfill attendance | `A.exitWithoutEnterOrInstallInsideOfficeIsUnknownNotInventedHistory`; `C.installingTodayDoesNotShrinkHistoricalDenominator` |
| 11 | Eligible offices contribute to one total | `A.twoEligibleOfficesPoolTheirMinutes` |
| 12 | Overlapping offices are globally unioned | `A.overlappingOfficesAndGraceAreGloballyUnioned` |
| 13 | Local midnight splits credited time correctly | `C.midnightSplitsGraceUsingPolicyDayBoundary` |
| 14 | Spring DST uses elapsed time | `A.springForwardUsesRealElapsedTime` |
| 15 | Fall DST uses elapsed time | `A.fallBackUsesRealElapsedTime` |
| 16 | Holiday removes requirement while retaining recorded credit | `C.excludedDayRemovesRequirementAndPreservesActualAttendance` |
| 17 | WFH does not remove an expected day | `C.wfhIsALabelAndDoesNotRemoveRequirement` |
| 18 | Future requirement is separate from through-today totals | `C.fullWeekSeparatelyProjectsFutureRequirements` |
| 19 | Rolling 30-day window includes only its intended boundaries | `C.rolling30IncludesTodayAnd29PreviousDates` |
| 20 | Rolling 90-day window includes only its intended boundaries | `C.rolling90IncludesTodayAnd89PreviousDates` |
| 21 | Corrections preserve raw evidence | `A.correctionsRetainRawEventsAndResolveReview` |
| 22 | A satisfied target requires no additional attendance | `D.cleanAlreadyMetTargetRequestsNoAdditionalTimeWithNoExitPrediction` |
| 23 | Zero expected days gives no divided-by-zero average | `C.excludedDayRemovesRequirementAndPreservesActualAttendance`; `C.changedPolicyRecomputesSameRawFacts` |
| 24 | Policy edits recompute existing facts | `C.changedPolicyRecomputesSameRawFacts` |

Additional suites cover malformed/extreme input, same-millisecond correction/revert order, immutable replay, unknown coverage, timezone travel, migration 1→2, corrupt/unsupported storage preservation, permissions, sensitive logging and packaged backup/IPC controls. Exact-head CI is the evidence for these unchanged suites; independent verification still inspects the acceptance criteria and any changed integration behavior.

## Remaining physical checks

No physical phone has been tested. Actual UI-closed geofence delivery at multiple offices, location revocation/restoration during a real visit, reboot/force-stop recovery under the manufacturer's background rules, battery behavior and manufacturer backup/device-transfer behavior remain unverified. Run these privately with real coordinates kept out of Git/GitHub. Registration success and synthetic parser observations do not substitute for those checks. Product MVP completion must not be declared while its explicit required acceptance remains unverified.
