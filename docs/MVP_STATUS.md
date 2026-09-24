# Product MVP checkpoint

Implementation branch: `feat/local-first-mvp`. This is a preview in integration, not a completed Product MVP claim.

## Implemented

- Kotlin/Compose app shell, shared dark design tokens, four functional destinations.
- Framework-free attendance engine; Room immutable source evidence and independent manual sessions; DataStore policy.
- Office configuration, staged location onboarding, passive ENTER/EXIT capture and recovery diagnostics.
- Dashboard daily/week/30/90 metrics and target-specific departure estimates.
- History, day detail, explainable raw/manual/correction provenance and explicit full-day review.
- Policy/weekdays/timezone, exclusions, WFH labels and typed attendance erasure.
- No app INTERNET permission; cloud/device-transfer backup exclusions; nonexported app receivers; practical CI security gates.

## Verification evidence so far

- 47 meaningful JVM domain tests passed using the installed Kotlin compiler and JUnit runner. Full Gradle check pending integrated build.
- Independent review found and implementation addressed overlapping manual source reconstruction, false first-day coverage, stale registration after cancellation, and unresolvable capture-failure diagnostics. Follow-up review pending.
- Persistence and Compose instrumentation tests written. Compiling tests is not running them.
- APK compilation, Android lint, merged-manifest audit, CI security scans, installation and physical-device checks remain pending until actual evidence is recorded below.

## Next integrator actions

1. Run Gradle unit tests, Android lint, APK assembly and instrumentation APK assembly. Fix real failures without weakening gates.
2. Run `python3 scripts/security_check.py` against built merged manifest. Inspect exact-head GitHub Actions and address findings.
3. Run instrumentation on an emulator if available; document unavailable device evidence honestly.
4. Deliver APK checksum, source commit and development-signing caveat. Keep Product MVP issues open until their acceptance criteria are verified.
5. Owner phone smoke: install, create synthetic test office, grant foreground then background permission, confirm live health, leave/reenter with app closed, reopen history, reboot, inspect recovery, deny/regrant permission, review a gap, verify offline history/settings. Real employer coordinates remain local.

## Known deliberate limits

- No homelab sync, exports, widget or target notification in this milestone.
- No shipped corporate holiday calendar: excluded dates are explicit user configuration.
- New/unknown history is labeled incomplete; review a day only after checking its complete attendance record.
- Disabling an office or changing eligibility/grace recomputes derived history under current source rules; original evidence remains intact.
- Preview uses `.preview` package and development signing. A different signing key cannot update it in place; uninstall erases local data. Production signing/recovery are a later explicit gate.
- Phone geofence delivery can be late and OEM-dependent. This is a personal estimator, not employer badge-system evidence.
- Native GitHub hierarchy, blockers and custom Project fields are currently incomplete because the browser connection failed; issue checklists/dependency links and phase milestones exist. See ROADMAP.

## Overnight continuation

Six bounded hourly continuation runs are scheduled for the overnight window. Reuse this branch/PR and re-read current state. A single integrator owns writes; parallel agents own disjoint file areas. Once APK/acceptance is green, review and fix within MVP rather than starting enhancements.
