# Build and security checks (#14)

Every pull request and master push builds the actual source head. Actions are pinned to immutable commits, tokens have read-only permissions except CodeQL's security-result upload, and build artifacts contain the source SHA, package/version, APK checksum and public signing-certificate digest. Debug preview signing is ephemeral on hosted runners; it is not a durable production update identity.

Before Gradle executes, `python3 scripts/verify_wrapper.py` checks the wrapper JAR against the official Gradle 8.11.1 checksum and enforces the reviewed HTTPS distribution URL and distribution checksum. `python3 -m unittest discover -s scripts -p 'test_*.py' -v` proves modified JARs, missing distribution checksums and untrusted hosts fail. Reviewed upgrades must update these pins together after checking the official checksum source.

The Android workflow runs domain/app tests, lint, debug/test APK assembly, a merged-manifest privacy check and a resolved-runtime Maven audit through OSV. An unavailable/incomplete audit fails rather than claiming success. Adversarial audit tests also run with optimized Python so `-O` cannot bypass the gates. It publishes test, lint, dependency and device reports even when later steps fail. The API35 emulator runs instrumentation, then explicitly reinstalls the APK before launch verification and screenshot capture because connected-test cleanup can uninstall it. Provenance recording rejects uncommitted source changes.

Gitleaks scans repository history, and CodeQL analyzes Kotlin/Java using a real Android build. Native GitHub dependency review is not claimed: the authenticated Dependency Graph SBOM endpoint currently returns HTTP 404, and the prior repository checkpoint records that Dependency Graph was disabled. OSV covers resolved runtime dependencies. Scanner results are bounded evidence, not an assurance that every vulnerability is known.

No recurring workflows are installed. Dependency updates are manual, reviewable PRs: change the pinned version catalog or action SHA, update the wrapper pins when applicable, inspect the dependency inventory delta, then pass current-head checks and independent verification/review before merging. Do not disable a failing gate or automatically accept new versions to make a build green.

The build and emulator artifacts use synthetic state. Real attendance, office coordinates, signing private keys and sensitive device logs must never be uploaded. Emulator checks do not certify physical-phone capture or battery behavior.
