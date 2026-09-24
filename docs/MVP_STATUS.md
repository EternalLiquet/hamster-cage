# Product MVP status

Product MVP is **not complete**. Delivery ends at Phase 5, with one issue per PR, independent implementation/verification/review, exact-head CI and serialized merges. PR #50 stays a draft/reference and is never merged wholesale. No scheduled tasks or post-MVP work.

Phase 0 is complete: issues #10–15 and [epic #1](https://github.com/EternalLiquet/hamster-cage/issues/1#issuecomment-5807277962) closed after their independent gates. Later phases remain open. Update the relevant issue row below instead of prepending duplicate checkpoints; keep detailed evidence in the linked PR/contract.

| Issue | Status / PR | Evidence or remaining acceptance |
| --- | --- | --- |
| #10 Shell | Merged [#51](https://github.com/EternalLiquet/hamster-cage/pull/51) | Verified e6deefc; merge e5b3eef; offline API37 install, four destinations, time contract and privacy boundary. |
| #11 Design/icon | Merged [#53](https://github.com/EternalLiquet/hamster-cage/pull/53) | Verified d44eee2; merge 1783e67; selected hamster-at-computer adaptive/legacy/monochrome assets, exact private-source derivation, 200% text and API35/37 checks. [Visual evidence](evidence/issue-11/README.md). |
| #12 Storage | Merged [#55](https://github.com/EternalLiquet/hamster-cage/pull/55) | Verified 494c741; merge 6b5a049; immutable facts, atomic replay handling, fresh Room/DataStore reopen, preserved schema/corrupt data and sanitized failure UI; 12 local device tests plus hosted gates. |
| #13 Reconstruction | Merged [#54](https://github.com/EternalLiquet/hamster-cage/pull/54) | Verified 399e210; merge ffe699a; 43 attendance and 2 clock tests; simultaneous boundaries and latest-valid correction fixes. [Contract](ATTENDANCE_RECONSTRUCTION.md). |
| #14 CI | Merged [#52](https://github.com/EternalLiquet/hamster-cage/pull/52) | Verified 883b1b3; merge 1103903; exact-head build/lint/JVM/device, wrapper, OSV, Gitleaks, CodeQL and APK provenance. Native dependency review unsupported/unclaimed. |
| #15 Foundation Security Pass | Merged [#59](https://github.com/EternalLiquet/hamster-cage/pull/59) | Verified 3e93512; merge 3c7b9ef; 15 adversarial audit methods, debug/release packaged controls and actual private-file denial on API37/35. [Inventory](FOUNDATION_SECURITY.md). |
| #16 Offices | Review/rework [#60](https://github.com/EternalLiquet/hamster-cage/pull/60) | Scoped form/storage/registration-intent implementation. Physical registration/future capture stop requires #18; keep issue open until evidenced. |
| #17 Permissions | Scoped verification [#56](https://github.com/EternalLiquet/hamster-cage/pull/56) | Real API37 denial/settings/approximate/grant/revocation/location-off checks and staged API routing. App source is unchanged from independently verified 5cd010c; hosted CI passed 538a6be before this status/security integration. Current-head independent gates must renew. Keep issue open for correction editing while permission is denied (#27/#33). [Contract](LOCATION_SETUP.md). |
| #18 Geofence capture | Implementation | Depends on office and permission components; authenticated local observations, registration and duplicate handling require their own evidence. |
| #19 Recovery/coverage | Open | Recovery and unknown-history accounting still required. |
| #20 Capture Security Pass | Open | Must audit completed capture/recovery surfaces. |
| #21 Calendar metrics | Merged [#57](https://github.com/EternalLiquet/hamster-cage/pull/57) | Verified ea87d52; merge 2a538b4; 17 calendar plus 45 existing domain tests, inclusive rolling windows, policy-timezone/DST, holidays/WFH and explicit unknown coverage. [Contract](CALENDAR_METRICS.md). |
| #22 Dashboard | Implementation | Seven focused presentation tests pass. Engine-backed observed/credited totals, target guidance, unknown/review states and visible-only clock implemented; UI/lifecycle and actual process-restart checks pending. Windows Gradle transform retries preserve all gates. |
| #23 Departure | Merged [#58](https://github.com/EternalLiquet/hamster-cage/pull/58) | Verified d758b58; merge c384b7b; 18 departure plus 62 existing domain tests, five named targets, ambiguity before satisfaction, union/grace/window/horizon checks. [Contract](DEPARTURE_ESTIMATES.md). |
| #24 Dashboard Security Pass | Open | Requires completed dashboard and estimates. |
| #25 History | Open | Daily history remains to implement and verify. |
| #26 Explanations | Open | Raw/effective bounds and credited interval explanations remain. |
| #27 Corrections | Open | Preview, confirm, append-only correction and revert remain. |
| #28 History Security Pass | Open | Requires history/correction surfaces. |
| #29 Policy | Open | Policy editing/recomputation remains. |
| #30 Calendar controls | Open | Exclusions and WFH controls remain. |
| #31 Privacy controls | Open | Local data explanation/deletion controls remain. |
| #32 Settings Security Pass | Open | Requires policy/calendar/privacy controls. |
| #33 Integrated journeys | Open | Full offline journeys, lifecycle/process/migration/failure evidence remains. |
| #34 Installable APK | Open | Final Product MVP artifact/source SHA/checksum/install instructions are not yet delivered. |
| #35 Product Security Pass | Open | Final merged artifact and privacy/correctness gate remains. |

Full tested SHAs, exact-head CI and independent PASS/review records are linked from each PR. Prior evidence applies only to unchanged source and must be renewed for every new head. Scoped PR merges do not close a feature with outstanding acceptance criteria; epics wait for every child and their Security Pass.

No physical-phone checks have run. Phone geofence delivery, permission changes, reboot/force-stop recovery, battery/OEM behavior and manufacturer backup/device-transfer behavior remain explicit checks. Emulator evidence is labelled as such. The debug `.preview` build uses a development key and permits authorized debugging; production signing is separate. Every delivered APK must include its source SHA, SHA-256 and signing caveat. Uninstalling can erase local data.

Raw observations remain immutable; corrections append; policy-local interval union prevents double credit. Missing history is never claimed as zero or complete. No INTERNET permission, backend, analytics, continuous GPS, real coordinates, personal attendance, credentials or sensitive logs may enter the public product evidence. Private visual references remain in the private project-source repository.
