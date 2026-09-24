# Hamster Cage engineering

Read `docs/ROADMAP.md`, `docs/MVP_STATUS.md` and the issue before editing. Product sources live in private `EternalLiquet/gpt-projects-files/hamster-cage/`; visual references live in its `visual-reference/` directory. Do not bundle that artwork.

The owner authorized autonomous implementation of the Product MVP and parallel agents with disjoint file ownership. One integrator owns commits and PR updates. Prefer the existing MVP PR; never race another automation on its branch. Preserve repository controls and report external blockers.

## Invariants

- Kotlin/Compose; framework-free `core-domain`; Room source facts and DataStore preferences.
- Fully local operation. No INTERNET permission, public backend, analytics or continuous GPS in MVP.
- Raw events immutable; corrections append; every credited minute explainable. Never fabricate raw events for an observed transition.
- Interval union prevents double credit. Holidays remove expected days; WFH does not. Time stored as Instant; policy timezone determines days. Missing history is explicit, never falsely zero or full coverage.
- Real coordinates, personal attendance, credentials, signing keys and sensitive logs never enter Git or public issues.
- Security passes in every epic; app-private storage, explicit backup exclusions, reviewed IPC/PendingIntent and permission flows.

## Verification and publication

Run `./gradlew :core-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and `python3 scripts/security_check.py`. Test attendance changes across happy, malformed and boundary cases including DST where relevant. Do not weaken checks to get green. Inspect exact-head CI. Keep APK build type, source SHA, checksum and signing caveat together. Do not claim emulator/device checks ran unless they did.

The local install preview is a debug build with a separate `.preview` package; production signing and real-device background/battery validation remain explicit gates. Public release hardening is post-MVP, but critical correctness/privacy defects block MVP. Each work checkpoint updates `docs/MVP_STATUS.md`. Source policy changes also update the corresponding private project source.
