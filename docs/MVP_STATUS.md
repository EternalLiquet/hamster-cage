# Product MVP checkpoint

The extraction path delivers one issue per PR from the preserved PR #50 reference. Issue #10 merged through PR #51 at `e5b3eef41fd263996a8ca0058e552c43afb9e2d6`; its verified source was `e6deefc8621230ece676808ee4b816fe8153048c`. CI issue #14 merged through PR #52 at `1103903a8edef601d117665d1064043312d0f930`, verified source `883b1b3603977dd15bd9eaede0326f8f93b63ffd`. Both passed their independent stages and current-head hosted checks. This checkpoint adds issue #11 only. It does not claim the reference branch's wider feature set is complete.

## Merged CI and security tooling (#14)

Exact-source CI runs build/lint/JVM/device tests, wrapper-integrity checks, OSV runtime dependency audit, Gitleaks and CodeQL, and publishes the APK with source/checksum/signing provenance. Its wrapper configuration fails closed against alternate Java Properties syntax. PR #52 records independent verification of failing-test reports, tampered-wrapper rejection, dirty-provenance rejection, signed artifacts and API 35 cold launch after reinstall. Native GitHub dependency review remains unavailable and unclaimed; resolved runtime dependencies are audited through OSV. No recurring workflow was introduced. See [CI details](CI.md).

## Foundation delivered

- Kotlin/Compose shell with Dashboard, Offices, History and Settings. Unavailable features are explicit; no attendance totals are fabricated.
- Framework-free time contract, injectable platform clock and documented UI/data/domain boundaries.
- Pinned build, JVM and instrumentation checks, offline manifest/backup audit, and separate `.preview` debug package.
- Shared charcoal/navy surfaces, amber/peach/pink tokens, typography, spacing, shapes, headings, panels, metric rows, status tags, empty states, errors and 48dp buttons.
- Navigation adapts to large text; status has readable labels and selection has both visual and accessibility state.
- Owner-selected hamster-at-computer icon, adaptive layers, density fallbacks and API 33 monochrome variant. Only derived local resources are bundled; the selected original remains private. No aesthetic reference artwork or network download is added.

## Issue #11 implementation evidence

Checks passed on 2026-09-24 UTC after refreshing the prerequisite to merged master:

- `:core-domain:test` (2 tests), `:app:testDebugUnitTest` (3 tests), `:app:lintDebug` (0 errors), debug APK and test APK assembly.
- Lint reports 6 warnings: 3 dependency updates, the redundant API 26 resource qualifier at min SDK 26, and monochrome warnings against the API 26 definitions. API 33 definitions supply the tested monochrome layer; no lint check is disabled.
- All 24 combined Python test methods pass normally and with `-O` after the #14 refresh, including fail-closed audit regressions and source provenance/resource wiring checks. Wrapper integrity and `python scripts/security_check.py` pass; OSV reports no known vulnerabilities among 58 resolved runtime dependencies.
- `:app:connectedDebugAndroidTest`: 5 tests pass on the local Pixel emulator, Android 17/API 37. The suite was repeated at 360dp screen width and 200% system font scale; all 5 pass. Navigation text layout does not overflow, controls meet 48dp minimums, and the content remains scrollable.
- Installed APK launches with the selected artwork. Actual screenshots cover full-color and monochrome Circle/rounded Square launcher masks plus 100%/200% text. A separate simulated sheet covers 24/36/48px and circle/squircle masks. See [evidence](evidence/issue-11/README.md).
- Emulator display, font and launcher style settings were restored after evidence capture. No physical-phone checks have run.

The PR records the committed source SHA and debug APK checksum with the local development signing caveat. Independent verification, independent review and exact-head CI are required before merging #11; implementation results are not independent sign-off.

## Remaining scope

Storage, attendance derivation, capture, feature pages, settings and corresponding Security Passes remain governed by their own issues (#12–13, #15–35). Real-device background delivery, permission changes, reboot recovery and battery/OEM behavior remain explicit integration gates. Production signing and release distribution are separate from the debug preview. Product MVP completion requires all Phase 0–5 acceptance criteria and security passes; no post-MVP enhancements or scheduled continuation tasks are included.
