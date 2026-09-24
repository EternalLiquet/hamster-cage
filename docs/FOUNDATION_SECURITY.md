# Phase 0 Security Pass (#15)

Scope: the merged shell, selected launcher/design, source storage, reconstruction engine and CI (#10–14). This pass adds no collection. Phase 1 capture/permissions and later features require their own Security Passes. Product MVP is not complete.

The table below records the Phase 0 baseline, not the integrated app. Later capture adds location/boot permissions, the protected exported recovery receiver and a non-exported geofence callback/PendingIntent; current inventories are in [Capture Security](CAPTURE_SECURITY.md) and [Product Security](PRODUCT_SECURITY.md). Storage later migrates to schema 2 for append-only correction ordering.

## Data and reachable surfaces

| Surface | Inventory and actual control |
| --- | --- |
| Local source database | Office names/coordinates/radii/eligibility/grace; immutable ENTER/EXIT IDs and timestamps/receipt metadata; append-only correction/manual bounds and optional notes; excluded-date reasons and WFH labels. Room schema 1, app-private internal database path. No stored aggregates. |
| Local policy | Timezone, weekday set, target, reconciliation gap and open-session horizon in versioned app-private DataStore. No credentials. |
| Android permissions | Foundation APK requests only AndroidX's signature-protected dynamic-receiver permission. No location collection or runtime permission flow in this baseline. The audit explicitly allows the three location permissions reviewed by the separate #17 setup issue; future permission additions require a reviewed audit change. |
| Components | Exported `MainActivity` is launcher-only and reads no caller extras/data. AndroidX ProfileInstallReceiver is the sole additional exported component and requires OS `DUMP`. Room invalidation service and Startup provider are not exported. No grantable data provider, deep link, app PendingIntent or custom external IPC input. |
| Network/secrets | No INTERNET permission, network client, server, analytics, sync credential, application secret or production signing key. CI's short-lived repository token stays in CI; it is not an app input. |
| Backups/exports | `allowBackup=false`; explicit root/database/file/preferences/external/device-protected exclusions in both cloud and device-transfer branches. No export feature or shared-storage writes. |
| Images/logs/artifacts | Only the selected icon's derived runtime assets are bundled; original artwork/reference files stay private. Public screenshots and all data fixtures are synthetic. No application runtime logging, database fixture, attendance file, credential or private key is tracked/bundled. Debug signing is explicitly a development key. |

## Trust boundaries and failure behavior

User-entered or delayed/replayed source data crosses repository validation before a transaction. Office coordinates/radius/grace are range/finite checked; IDs/notes are bounded; office edits require the expected version; unknown offices and conflicting event IDs fail. An entire conflicting event batch rolls back. Identical replay is idempotent. Delayed authenticated observations for a disabled existing office remain immutable while current policy controls credit.

Room/DataStore bytes are untrusted on read. Unknown schema versions, invalid policy and corrupt databases surface a sanitized unavailable notice; there is no destructive migration, corruption reset, exception-detail UI or falsely empty history. The existing storage instrumentation proves retained bytes/facts and fresh-scope restart. Malformed times, missing/repeated transitions, conflicting IDs and invalid edits become deterministic engine review states; no synthetic transition repairs them.

An ordinary external identity must not read source files. New instrumentation checks actual Room and DataStore paths/modes and attempts reads through OS shell UID 2000, requiring permission denial and empty stdout. Both cases pass on API37. The separate stderr shell descriptor requires API34+, including hosted API35; older APIs do not claim that probe. Installed PackageManager state is inspected for network, backup, cleartext and component exposure. This tests configured controls; it does not claim protection against a rooted OS or an authorized debugger of the debug preview.

## Findings fixed in this pass

- The prior audit accepted any exported endpoint carrying `DUMP` or `BIND_JOB_SERVICE`. It now allows only the reviewed named ProfileInstallReceiver, requires explicit export state, and rejects exported/grantable providers and launcher data/extra intent surfaces.
- New Android permissions and a weakened internal receiver permission now fail. New legitimate surfaces must change the inventory and adversarial evidence, not borrow a generic permission exception.
- Runtime `Log.wtf`, `Log.println`, `print` and stack-trace output are covered alongside the earlier log checks.
- CI now audits the release merged manifest as well as debug and rejects debug/test-only release flags. Production signing remains outside this preview.
- Variant/qualified backup-resource overrides are rejected so a safer main resource cannot conceal a different packaged backup policy.

Fifteen privacy-audit methods exercise safe and adversarial manifests/logs under normal Python, `-O`, and `PYTHONOPTIMIZE=1`. Existing wrapper-hash, canonical-properties, dependency-response and launcher tests remain enforced. All Actions are pinned; PR workflows use read-only contents access except CodeQL's required security-events write. Workflows check out the exact PR head, validate the wrapper before Gradle, use OSV on resolved runtime dependencies, run Gitleaks and CodeQL, and record APK SHA/signature/source provenance. GitHub native dependency review is unsupported for this repository and is not claimed; OSV is the active dependency check.

## Evidence and residual checks

The implementation evidence and exact tested SHA are recorded in this issue's PR. The built debug binary manifest and both compiled backup resources were inspected with SDK `aapt2`, in addition to the source/merged audit. Local evidence stays in `app/build/reports/foundation-security`; CI publishes test/lint/dependency/device reports and a debug APK with provenance. Independent verification and independent review must pass on the final head before #15 or Phase 0 closes.

No physical phone has been tested. Manufacturer/device-transfer behavior, phone geofence delivery, permission recovery, reboot/force-stop and battery/OEM behavior remain explicit #33–35 gates. Absence of cloud backup means uninstall/device loss can lose local data; no backup or sync feature is implied. These limitations do not waive any later feature or security acceptance criterion.
