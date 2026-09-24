# Product MVP Security Pass (#35)

This pass covers the integrated phases 0–5 application and the APK delivered by #34. It adds no collected data, Android permission, component, network endpoint, credential, dependency or persisted schema. Acceptance remains pending the final dependency refresh, actual packaged evidence, independent verification/review and exact-head hosted gates.

## Actual product boundaries

| Boundary | Control and evidence required |
| --- | --- |
| Retained private facts | Room stores offices, immutable observations, append-only corrections/manual sessions and calendar facts. DataStore stores policy, capture health/coverage and the versioned deletion journal. Actual shell-UID access denial has been exercised for database, policy/capture files and the journal; authorized debug access remains possible in the preview. |
| Untrusted input | Typed bounded office/policy/calendar values, immutable atomic replay, unknown-office rejection, bounded callback batches and generation-tagged callback acceptance. Missing/malformed history remains unavailable or unknown, never invented complete attendance. Relevant adversarial cases live in the preceding feature/Security Pass PRs. |
| Privacy deletion | Explicit preview/cancel/confirm, durable fail-closed journal, shared capture/edit boundary, bounded old-fence retirement, failed-delivery coverage and paired fresh Room/coverage presentation. Partial journal metadata cannot authorize recovery deletion. Android full reset requests actual app-data clearing and describes process termination honestly. |
| Permissions/IPC | Fine/coarse/background location and boot recovery only; no INTERNET, shared storage or foreground tracking service. Launcher and protected recovery actions are the reviewed exported app surfaces. Geofence callback is explicit/non-exported; its package-owned mutable PendingIntent is limited to Play Services delivery. Library providers/receivers remain separately audited. |
| Backup/export | Explicit compiled cloud and device-transfer exclusions plus disabled backup. No app export/sync path or server. Manufacturer backup/transfer behavior still needs physical-device checks. |
| Artifact/logs | Actual debug and unsigned release permissions, components, backup XML and archive inventory must be inspected. Runtime logging audit rejects payload/exception logging. Synthetic screenshot/log evidence from #24/#28/#32 and the integrated journey contains no personal data. |
| Signing/update | Debug `.preview` is development-signed and debuggable. Same-key update and clean-launch evidence belongs to #34. A debug certificate, unsigned release inspection or emulator test does not establish production release security. |

## Packaged-data regression gate

`scripts/apk_inventory.py` inspects the actual APK ZIP, not the source tree. It rejects private database/preferences/key/log/reference paths, unexpected assets, ambiguous paths, duplicate entries and archive corruption. Only optional Android baseline-profile assets are pre-approved. Adversarial archive tests include database/WAL, preferences, key and log payloads, arbitrary seed JSON, reference artwork paths and duplicate/traversal entries; assertions remain enforced under Python optimization. This gate supplements the existing manifest/backup, source, secret and signature checks. Pixel content and arbitrary hardcoded data still require source/asset review.

## Evidence and remaining gates

Preliminary actual package inspection passed at source `f28785d11762cde6af8d71978d3c35e7e6a1f6d4`, with preview version code 2: debug archive 244 entries and unsigned release archive 145 entries. Both compiled manifests exclude INTERNET, disable backup/cleartext and expose only the reviewed three exported components. Resolved binary backup XML contains all nine excluded storage domains in legacy backup and both cloud/device-transfer branches. Debug is explicitly debuggable and includes its non-exported test Activity; release contains neither. Three packaged-data adversarial tests passed in normal and optimized Python.

This is preliminary integration evidence, not the final delivered artifact. #31 fixture-lifecycle and #33 fixture-safety rework, final dependency refresh, exact artifact identity and independent/current-head verification remain. Full hashes and component inventory are retained in the [synthetic package inspection receipt](evidence/issue-35/packaged-inspection.json). Renewed dependency/static/secret results must be recorded on the focused PR after #33/#34 integration. Hosted checks include OSV against resolved runtime dependencies, Gitleaks, CodeQL, wrapper integrity, JVM/lint/build and API35 instrumentation/offline journey. GitHub native dependency review is unsupported and is not claimed.

No physical phone has been tested. Actual UI-closed multi-office geofence delivery, permission changes during a real visit, manufacturer reboot/force-stop/background restrictions, battery behavior and backup/device transfer remain unverified. These remain in #18/#19/#20 and the Product MVP acceptance ledger. Do not close this pass or the epic merely because static checks pass; no high-impact correctness/privacy defect may be deferred.
