# Product MVP checkpoint

This branch implements issue #10 only. It extracts the pinned build and privacy defaults from PR #50 commit `12a2cc8`, then supplies a focused shell. It does not carry forward the reference branch's broader feature-completion claims.

## Implemented for #10

- Kotlin/Compose Android shell with Dashboard, Offices, History, and Settings destinations. Pages describe unavailable features explicitly; there are no fabricated attendance totals.
- Framework-free `core-domain` time contract, an injectable system-clock adapter in `app/data`, and UI receiving the domain dependency from the Activity.
- Pinned Gradle wrapper, version catalog, JDK 17/SDK 36 build documentation, JVM clock tests, and instrumented navigation/recreation smoke tests.
- No user-granted permissions or network SDKs, explicit backup exclusions, and a `.preview` debug package. The manifest audit includes AndroidX's signature-protected receiver permission and OS-protected profile installer. The launcher mark is an original geometric placeholder until the selected artwork ships in #11.

## Evidence

Implementation checks passed on 2026-09-24 UTC:

- `:core-domain:test` (2 tests), `:app:testDebugUnitTest` (1 test), `:app:lintDebug` (0 errors; 3 dependency-update warnings), and debug/app-test APK assembly passed.
- `python scripts/security_check.py` passed against the merged debug manifest.
- `adb install -r` and explicit launcher start succeeded on the local Pixel emulator (Android 17, API 37).
- `:app:connectedDebugAndroidTest` passed both tests: all four destinations open, and selection survives Activity recreation. Airplane mode was enabled and the active default network was `none` throughout these checks.
- A clean build compiled the app and tests. Windows Gradle cache-transform locks required retries; no check was disabled. The old instrumentation dependency's removed InputManager call was fixed by pinning AndroidX Test runner 1.7.0, JUnit 1.3.0 and Espresso 3.7.0.

Independent verification, independent review, exact-head CI, and merge remain required. The PR records the exact implementation commit and repeats these checks on the committed head; this checkpoint is not independent sign-off.

The issue PR will record the tested commit, commands, actual emulator install/launch results, and any unavailable criteria. Product MVP issues remain open until their individual acceptance criteria and security passes are satisfied.

## Remaining scope

All attendance/storage/capture/settings implementation and corresponding security passes (#11–35) remain outstanding on this extraction path. No physical-phone checks have run. Real-device background delivery, permission changes, reboot recovery, and battery/OEM behavior remain explicit later integration gates. Production signing and release distribution remain separate from the debug preview.
