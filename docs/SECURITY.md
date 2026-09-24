# MVP security baseline

The phone is the sole operational datastore. The MVP declares no INTERNET permission, has no backend, analytics, ads, sync, shared-storage export or continuous location service. Google Play services geofencing is a separate OS/service trust boundary: platform location quality and network-assisted fixes remain device dependent even though this app does not upload attendance.

Room and DataStore use app-private storage. Generic cloud backup and device transfer are explicitly excluded, including device-protected domains. Android sandbox/device encryption protects ordinary at-rest access; root compromise and an unlocked device are outside that protection. Additional database encryption needs a recovery design before adoption. Losing/uninstalling/clearing this preview loses its data: sync/backup is not yet implemented.

Only the launcher activity is intentionally exposed by app code. Transition/recovery receivers are nonexported. Geofence delivery uses an explicit mutable PendingIntent because Play services fills transition extras; this is a documented exception to immutable-by-default. WorkManager's merged manifest surfaces are audited for signature-level protection. No exact coordinates, event history or credentials are logged.

Foreground precise location and background access are requested in separate stages. Denial leaves manual/history features available. Coordinates are user-entered locally; shipped fixtures are synthetic. No map/search API or API key is needed. Raw evidence is retained; corrections append. Typed confirmation is enforced in the repository for destructive attendance deletion. No destructive migration fallback is permitted.

CI validates the wrapper, runs tests and Android lint, audits the merged manifest and backup policy, and configures CodeQL and Gitleaks. An executable OSV gate checks actual resolved Maven runtime versions and fails on reported vulnerabilities or incomplete/unavailable results. Dependabot updates are configured weekly; GitHub's native dependency review is unavailable until Dependency Graph is enabled. Passing a static check is evidence for its narrow boundary, not a claim that the whole app is invulnerable. Real-device permission/force-stop/reboot/latency and battery checks remain required.

The installable MVP preview uses a separate `.preview` package and development signing. Do not use its signing key for production. Builds created by a different development key may require uninstalling, which deletes local history; stable private signing and recovery are tracked before daily-use release. No signing key is committed or uploaded with artifacts.

Future sync is tailnet-only HTTPS with certificate validation, revocable device authentication, Keystore-backed credentials, idempotent facts, conflict/tombstone semantics and tested recovery. It is excluded from the MVP attack surface.
