# Dashboard Security Pass (#24)

Scope: dashboard, calendar metrics and departure guidance (#21–23). This pass introduces no data collection, permission, Android component, credential, dependency, network request, export or persistent file. It only sanitizes user-controlled office labels at display time; stored source facts are unchanged.

## Boundaries and findings

Room/DataStore source facts cross the attendance engine before entering dashboard presentation. Coordinates and source identifiers are not dashboard fields. Calendar notes are not rendered there. Names are user-controlled: the presence label now removes control and Unicode format characters and bounds the displayed name to 120 characters, with a neutral fallback. A regression includes bidirectional controls, null/newline characters and a 100,000-character name.

Conflicting identifiers and future transition evidence cannot establish a healthy outside state or a confident departure estimate. Regression tests require review/incomplete-history states. Invalid/non-finite coordinates are rejected by the domain model. Failed tracking remains unknown; unavailable storage presents a sanitized notice without database paths, exception details or falsely empty totals. No dashboard credential dependency exists.

The dashboard intentionally displays attendance and the chosen office name. Screenshots and the app switcher can therefore contain that visible information; this app does not promise screenshot prevention. It does not render exact coordinates, internal source IDs or private calendar notes. There is no analytics or export flow. The instrumented screenshot fixture uses synthetic data only and writes solely to a test-only app-private cache file; that test code/image is absent from the application APK.

## Concrete evidence

- Three focused JVM regressions pass; two API37 emulator UI checks pass, including unavailable-storage and incomplete-coverage presentation. The synthetic screenshot and bounded log/artifact probe results are in [evidence/issue-24](evidence/issue-24/probe-results.txt). Visual inspection shows a normalized office name, unknown balance and explicit incomplete-history guidance, with no coordinate, internal ID or private note disclosure.
- The inspected run's 3,104 recent logcat lines contained none of the synthetic private sentinels. This is bounded run evidence. The source audit independently rejects runtime logging APIs throughout production Kotlin.
- This #24-era debug APK binary manifest had no INTERNET permission; the current #97 package has audited office-setup INTERNET. Both compiled backup resources retain the audited root/database/file/preferences/external/device-protected exclusions. The archive contains no database, preferences file, private-key file or test screenshot. The merged-manifest/source audit and 16 adversarial audit tests pass.
- The inherited permissions are precise/approximate/background location, boot-completed, and the AndroidX signature receiver permission. Reachable components remain the launcher, protected system recovery actions and the named DUMP-protected ProfileInstaller receiver; the capture receiver is nonexported. No new IPC or PendingIntent is introduced here. Actual private-file denial and capture PendingIntent controls are covered by their separate foundation/capture Security Passes.
- Exact-head hosted build/lint/JVM/API35 instrumentation, OSV dependency audit, Gitleaks, wrapper validation and CodeQL evidence must be linked in this PR before integration. GitHub native dependency review is unsupported and is not claimed.

The debug preview remains debuggable and development-signed. No physical-phone checks ran. OEM backup/device-transfer and physical capture reliability remain explicit delivery checks. Final issue/epic closure also requires the dashboard's #22 readiness/coverage acceptance evidence; this scoped pass does not waive that dependency or declare Product MVP complete.
