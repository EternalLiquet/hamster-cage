# Product MVP checkpoint

## Departure estimates #23 checkpoint

The pure estimator names all five target contexts and keeps estimated exit time separate from accrued credit. It uses prior unioned credit and office exit grace, suppresses predictions for missing history, relevant ambiguous/malformed bounds or competing open offices, and checks those conditions before apparent target satisfaction. Projections stop at the target window and configured safe open-session horizon. No source fact or historical aggregate is changed.

Eighteen focused departure tests and the 62 existing domain tests pass. They cover target names, prior credit, met/unmet states, overlap, grace, leave-now projections, exclusions/WFH, rolling/week boundaries, correction resolution, clock anomalies, midnight and DST. Independent verification/review and exact-head CI remain required. See [departure contract](DEPARTURE_ESTIMATES.md); UI/tracking-health integration remains separate.

## Calendar metrics #21 checkpoint

The pure domain slice adds today/week/rolling 30/90-date summaries, explicit full-week projection, policy-local day boundaries and denominator/coverage accounting. Exclusions reduce requirement while preserving actual credit; WFH does not reduce requirement. Missing weekend/holiday coverage is explicitly unknown, and no expected-day denominator is shortened to the install date. A zero denominator has an undefined average. Raw daily totals remain separate from corrections/manual/grace credit.

All 17 focused calendar tests pass alongside the 45 existing reconstruction/clock tests. They cover holidays/WFH, future and inclusive-window boundaries, midnight/DST, policy/timezone changes, unknown coverage and malformed/extreme input bounds. This implementation checkpoint is not independent acceptance; exact-head CI and separate verification/review are required. See [calendar contract](CALENDAR_METRICS.md). Departure and UI features remain separate issues.

Delivery uses one issue per PR, with separate implementation, verification and review agents. PR #50 remains an unmerged extraction reference. Only phases 0–5 are in scope; no scheduled continuation tasks or post-MVP work.

## Merged foundation

- Shell #10 / [PR #51](https://github.com/EternalLiquet/hamster-cage/pull/51): merge `e5b3eef41fd263996a8ca0058e552c43afb9e2d6`, verified source `e6deefc8621230ece676808ee4b816fe8153048c`. Kotlin/Compose, four offline destinations, framework-free time contract, explicit privacy/backup boundaries and `.preview` debug package. [Independent evidence](https://github.com/EternalLiquet/hamster-cage/pull/51#issuecomment-5806094303) includes actual offline installation and adversarial audit failures.
- CI #14 / [PR #52](https://github.com/EternalLiquet/hamster-cage/pull/52): merge `1103903a8edef601d117665d1064043312d0f930`, verified source `883b1b3603977dd15bd9eaede0326f8f93b63ffd`. Exact-source build/lint/JVM/device checks, wrapper integrity, privacy, OSV, Gitleaks, CodeQL and APK provenance. [Independent evidence](https://github.com/EternalLiquet/hamster-cage/pull/52#issuecomment-5806253123) verifies failure reporting and downloaded APK checksum/signature. Native dependency review is unavailable and unclaimed; resolved runtime OSV auditing is active. No check or protection was weakened.
- Design #11 / [PR #53](https://github.com/EternalLiquet/hamster-cage/pull/53): merge `1783e67c24f6dd14bf85bb0a80c0531477842e8e`, verified source `d44eee2c7254c4e512bc1ba393e25f92e8e8dd24`. Shared dark tokens/components, labelled status, 48dp controls, 200% text navigation and selected hamster-at-computer launcher with adaptive, legacy and monochrome resources. [Independent evidence](https://github.com/EternalLiquet/hamster-cage/pull/53#issuecomment-5806542809) covers source provenance, exact asset regeneration, device/layout checks and passing API35 hosted instrumentation. The installed-icon test accommodates Android selecting either declared regular or round artwork; both variants remain checked. Private source artwork stays private. [Visual evidence](evidence/issue-11/README.md).

## Attendance reconstruction #13 / PR #54

The focused domain slice normalizes immutable raw observations per office, applies separate entry/exit grace, reconciles configured same-office gaps and globally unions credit. Every interval retains session provenance. Open sessions stop at injected `now`; stale or missing-boundary evidence remains reviewable. Manual facts remain separate from device events. Source aliases retain corrections across late replay or repaired missing boundaries.

At source `f82de27cfd361383b3eb58052ba2548d50fcbd19`, 43 attendance and two clock tests pass. The matrix covers documented 380/490-minute examples, duplicate/repeated/missing/conflicting facts, malformed numeric/extreme time values, midnight/DST, multi-office union, manual overlap and correction identity. Independent findings were repaired: a simultaneous EXIT/ENTER closes an existing visit before starting the next, while an isolated zero-length observation gets no grace; a malformed newer correction cannot erase an earlier valid edit. No raw fact is fabricated or rewritten.

Reconstruction #13 merged through PR #54 at `ffe699ab0b9e0a8e4b65c5ce8882707ed17060dc`, preserving verified source `399e2106a5f79400de032f60236a41f22b81a571`. [Renewed independent verification](https://github.com/EternalLiquet/hamster-cage/pull/54#issuecomment-5806683656), independent review and all current-head checks passed after design integration. Issue #13 is closed. See [domain contract](ATTENDANCE_RECONSTRUCTION.md).

## Remaining work and device gates

Storage #12, Phase0 Security Pass #15, and capture/pages/settings/integration issues #16–35 remain open until their own acceptance criteria pass. Calendar #21 is the current focused implementation; departure estimates remain separate. Product MVP is not complete.

No physical-phone checks have run. Actual phone background delivery, permission changes, reboot/force-stop recovery and battery/OEM behavior remain explicit integration gates. Local and hosted emulator checks are identified as such. Production signing remains separate from the debug preview; every delivered APK must carry source SHA, checksum and signing caveat. No continuous GPS, INTERNET permission, public backend, analytics or sensitive fixture data is permitted.
