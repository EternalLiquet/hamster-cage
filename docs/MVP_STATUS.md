# Product MVP checkpoint

## CI issue #14 extraction

The expanded CI slice replaces the bootstrap workflow with source-head build, lint, unit/instrumentation tests, wrapper-integrity checks, OSV runtime dependency audit, Gitleaks, CodeQL and traceable APK/report artifacts. A post-test reinstall fixes the reference workflow's launch failure. No recurring workflows are created; dependency updates remain reviewed PRs.

Implementation checks: ten wrapper/dependency-audit tests pass in ordinary and optimized Python, including known-vulnerability and unavailable/incomplete-service failures; the three shell JVM tests, lint, APK/test APK assembly, dependency inventory and privacy audit pass. OSV reports no known vulnerabilities in 57 resolved external Maven runtime dependencies. Provenance refuses dirty checkouts. Instrumentation/hosted scanners, independent verification/review and current-head CI are required in the issue PR; the preserved PR #50 results are not substituted for them. This does not complete other Phase 0 features or the Product MVP.

This branch adds issue #14's CI slice to the focused issue #10 shell. The shell extracted pinned build/privacy defaults from PR #50 commit `12a2cc8`; none of that reference branch's broader feature-completion claims apply here.

## Implemented for #10

- Kotlin/Compose Android shell with Dashboard, Offices, History, and Settings destinations. Pages describe unavailable features explicitly; there are no fabricated attendance totals.
- Framework-free `core-domain` time contract, an injectable system-clock adapter in `app/data`, and UI receiving the domain dependency from the Activity.
- Pinned Gradle wrapper, version catalog, JDK 17/SDK 36 build documentation, JVM clock tests, and instrumented navigation/recreation smoke tests.
- No user-granted permissions or network SDKs, explicit backup exclusions, and a `.preview` debug package. The manifest audit includes AndroidX's signature-protected receiver permission and OS-protected profile installer. The launcher mark is an original geometric placeholder until the selected artwork ships in #11.

## Evidence

Implementation checks passed on 2026-09-24 UTC:

- `:core-domain:test` (2 tests), `:app:testDebugUnitTest` (1 test), `:app:lintDebug` (0 errors; 3 dependency-update warnings), and debug/app-test APK assembly passed.
- `python scripts/security_check.py` passed against the merged debug manifest.
- Independent review required fail-closed audit validation: explicit checks now remain active under `python -O` and `PYTHONOPTIMIZE=1`; subprocess tests cover forbidden networking, malformed XML, ambiguous manifest outputs and missing backup branches.
- Independent verification also exercised SDK-conditioned permission tags. Both `uses-permission-sdk-23` and the legacy `uses-permission-sdk-m` alias now receive the same forbidden-permission and malformed-entry checks in all interpreter modes.
- The built manifest must reference the exact backup resources that the audit inspects; a different or missing policy reference fails in every Python mode.
- `adb install -r` and explicit launcher start succeeded on the local Pixel emulator (Android 17, API 37).
- `:app:connectedDebugAndroidTest` passed both tests: all four destinations open, and selection survives Activity recreation. Airplane mode was enabled and the active default network was `none` throughout these checks.
- A clean build compiled the app and tests. Windows Gradle cache-transform locks required retries; no check was disabled. The old instrumentation dependency's removed InputManager call was fixed by pinning AndroidX Test runner 1.7.0, JUnit 1.3.0 and Espresso 3.7.0.

Independent verification, independent review, exact-head CI, and merge remain required. The PR records the exact implementation commit and repeats these checks on the committed head; this checkpoint is not independent sign-off.

The issue PR will record the tested commit, commands, actual emulator install/launch results, and any unavailable criteria. Product MVP issues remain open until their individual acceptance criteria and security passes are satisfied.

## Remaining scope

Design/icon (#11), storage (#12), attendance reconstruction (#13), the Phase 0 Security Pass (#15), and all page/integration issues (#16–35) remain outstanding on this extraction path. No physical-phone checks have run. Real-device background delivery, permission changes, reboot recovery, and battery/OEM behavior remain explicit later integration gates. Production signing and release distribution remain separate from the debug preview.
