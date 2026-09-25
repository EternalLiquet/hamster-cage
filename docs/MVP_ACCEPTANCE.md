# Integrated offline acceptance (#33)

The computer-runnable acceptance journey uses the real `MainActivity`, Room/DataStore and engine. Its opt-in fixture requires no source facts, exact default policy, generation-zero idle privacy state, empty coverage and no prior journey receipt before it starts; customized empty installations are refused too. It creates two synthetic offices through the UI, supplies explicitly synthetic observations through the capture parser/repository boundary, compares dashboard totals with the same engine, confirms a correction while fine location permission is denied, changes target/timezone policy, verifies that a WFH label preserves the requirement, adds a holiday, recreates the Activity and verifies the persisted record from a different process. It then checks deletion cancellation, confirmed attendance/calendar deletion with offices and base policy retained, and another fresh-process reopen. Synthetic parser input does **not** establish OS geofence delivery.

`scripts/verify_offline_journey.py` installs built debug/test APKs on an emulator, disables Wi-Fi/mobile data for the journey, checks both controls, restores their prior enabled states and writes a report under `app/build/reports/offline-journey`. It never clears or uninstalls existing data. The Android CI job runs this host journey after the ordinary instrumentation suite, which uninstalls its test packages, so the opt-in journey begins with a fresh preview installation. Public evidence contains synthetic records only.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify_offline_journey.py --serial emulator-5554
```

The full API37 host journey and the existing-private-state refusal regression passed at source `266ca2d0f91dc2f9537f0ca412596aa19d473e74` after the verifier-requested guard repair. The [receipt](evidence/issue-33/offline-journey.json) records process 13671 → 13806 retaining two offices, four synthetic events, a correction, target/timezone, WFH and holiday; confirmed deletion ran in process 13873 and reopened empty in process 13943 with offices/base policy retained. Wi-Fi/mobile data were disabled and fine location remained denied throughout. A separate adversarial test rejects a customized empty policy, prior/pending privacy generation and existing coverage before synthetic mutation. The earlier pre-privacy receipt remains explicitly preliminary. PR #74 later merged after independent verification/review and exact-head CI. PR #96 extended the host journey to use the correction date/time pickers and assert the exact persisted Instant; its final head passed independent verification and hosted gates. These synthetic emulator journeys do not establish physical capture acceptance.

## Engine acceptance coverage

The table below records the 24-case baseline used for #33's earlier exact-head verification; it is historical evidence, not #78 acceptance. Issue #78 supersedes its arrival/exit grace assertions and example totals. Revalidate the affected cases against uncredited arrival walking grace and projection-only exit/departure grace on the focused #78 head before citing them as current behavior. `A` is `AttendanceEngineTest`, `C` is `CalendarMetricsTest`, and `D` is `DepartureTest` in `core-domain/src/test/kotlin/dev/hamstercage/domain`.

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

The #97 candidate APK was installed and cold-launched on a Samsung API36 phone ([receipt](https://github.com/EternalLiquet/hamster-cage/issues/35#issuecomment-5827146852)). Actual phone office flow, UI-closed geofence delivery at multiple offices, weekday missed-transition reconciliation, location revocation/restoration during a real visit, reboot/force-stop recovery under manufacturer background rules, battery behavior, small-device accessibility and manufacturer backup/device-transfer behavior remain unverified. Run these privately with real coordinates kept out of Git/GitHub. Registration success and synthetic parser observations do not substitute for those checks. Product MVP completion must not be declared while its explicit required acceptance remains unverified.
