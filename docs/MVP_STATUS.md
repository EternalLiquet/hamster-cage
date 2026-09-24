# Product MVP checkpoint

Delivery uses one issue per PR, with separate implementation, verification and review agents. PR #50 remains an unmerged extraction reference. Only phases 0–5 are in scope; no scheduled continuation tasks or post-MVP work.

## Local source storage issue #12

The storage slice adds a versioned Room database for offices, raw observations, corrections, manual sessions, excluded dates and WFH labels, plus versioned DataStore policy. Raw observations and append-only edits have stable IDs and no normal update/delete path. Repository reads form a consistent transaction and derive attendance from source facts when requested; no aggregate is persisted. Failed reads emit a sanitized unavailable state. Room has no destructive fallback, and its corruption callback preserves the file. The existing backup exclusions cover both database and policy files.

Implementation checks: `:core-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and `python scripts/security_check.py` pass. Five instrumented storage tests passed on the local Pixel 10 Pro XL emulator (API 37): all-fact restart and aggregate recomputation, atomic failed batch, version-one schema compatibility, legacy policy migration with future-version rejection, unsupported Room version and corrupt-file preservation. Independent verification/review, current-head CI and PR merge remain pending. No version-two schema exists; any future upgrade requires an explicit, preservation-tested migration.


## Merged foundation

- Shell #10 / [PR #51](https://github.com/EternalLiquet/hamster-cage/pull/51): merge `e5b3eef41fd263996a8ca0058e552c43afb9e2d6`, verified source `e6deefc8621230ece676808ee4b816fe8153048c`. Kotlin/Compose, four offline destinations, framework-free time contract, explicit privacy/backup boundaries and `.preview` debug package. [Independent evidence](https://github.com/EternalLiquet/hamster-cage/pull/51#issuecomment-5806094303) includes actual offline installation and adversarial audit failures.
- CI #14 / [PR #52](https://github.com/EternalLiquet/hamster-cage/pull/52): merge `1103903a8edef601d117665d1064043312d0f930`, verified source `883b1b3603977dd15bd9eaede0326f8f93b63ffd`. Exact-source build/lint/JVM/device checks, wrapper integrity, privacy, OSV, Gitleaks, CodeQL and APK provenance. [Independent evidence](https://github.com/EternalLiquet/hamster-cage/pull/52#issuecomment-5806253123) verifies failure reporting and downloaded APK checksum/signature. Native dependency review is unavailable and unclaimed; resolved runtime OSV auditing is active. No check or protection was weakened.
- Design #11 / [PR #53](https://github.com/EternalLiquet/hamster-cage/pull/53): merge `1783e67c24f6dd14bf85bb0a80c0531477842e8e`, verified source `d44eee2c7254c4e512bc1ba393e25f92e8e8dd24`. Shared dark tokens/components, labelled status, 48dp controls, 200% text navigation and selected hamster-at-computer launcher with adaptive, legacy and monochrome resources. [Independent evidence](https://github.com/EternalLiquet/hamster-cage/pull/53#issuecomment-5806542809) covers source provenance, exact asset regeneration, device/layout checks and passing API35 hosted instrumentation. The installed-icon test accommodates Android selecting either declared regular or round artwork; both variants remain checked. Private source artwork stays private. [Visual evidence](evidence/issue-11/README.md).

## Attendance reconstruction #13 / PR #54

The focused domain slice normalizes immutable raw observations per office, applies separate entry/exit grace, reconciles configured same-office gaps and globally unions credit. Every interval retains session provenance. Open sessions stop at injected `now`; stale or missing-boundary evidence remains reviewable. Manual facts remain separate from device events. Source aliases retain corrections across late replay or repaired missing boundaries.

At source `f82de27cfd361383b3eb58052ba2548d50fcbd19`, 43 attendance and two clock tests pass. The matrix covers documented 380/490-minute examples, duplicate/repeated/missing/conflicting facts, malformed numeric/extreme time values, midnight/DST, multi-office union, manual overlap and correction identity. Independent findings were repaired: a simultaneous EXIT/ENTER closes an existing visit before starting the next, while an isolated zero-length observation gets no grace; a malformed newer correction cannot erase an earlier valid edit. No raw fact is fabricated or rewritten.

[Independent verification](https://github.com/EternalLiquet/hamster-cage/pull/54#issuecomment-5806548852) and all hosted checks passed that source. This branch now incorporates the merged design prerequisite. Renewed verification/review and current-head CI are required after this integration; earlier approval does not cover the new commit. See [domain contract](ATTENDANCE_RECONSTRUCTION.md).

## Remaining work and device gates

Storage #12, reconstruction #13 final integration, Phase0 Security Pass #15, and capture/pages/settings/integration issues #16–35 remain open until their own acceptance criteria pass. Calendar totals and departure estimates are separate from reconstruction. Product MVP is not complete.

No physical-phone checks have run. Actual phone background delivery, permission changes, reboot/force-stop recovery and battery/OEM behavior remain explicit integration gates. Local and hosted emulator checks are identified as such. Production signing remains separate from the debug preview; every delivered APK must carry source SHA, checksum and signing caveat. No continuous GPS, INTERNET permission, public backend, analytics or sensitive fixture data is permitted.
