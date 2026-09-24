# Build and install the MVP preview

## Build

Use JDK 17, Android SDK platform 36 and build-tools 35.0.0. Point `ANDROID_HOME` or a gitignored `local.properties` at the SDK. The checked-in Gradle 8.11.1 wrapper validates its distribution checksum.

```sh
./gradlew :core-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:writeDependencyInventory
python3 scripts/security_check.py
python3 scripts/dependency_audit.py
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. The final audit uses OSV over HTTPS to check resolved Maven versions; an unavailable audit service is not a pass. The app itself has no INTERNET permission. CI also runs CodeQL and Gitleaks. GitHub dependency review is currently unavailable because repository Dependency Graph is disabled; the executable OSV audit covers resolved runtime libraries instead.

With an Android emulator/device connected, run `./gradlew :app:connectedDebugAndroidTest`. CI uses an API35 Google APIs emulator for Room persistence and Compose form tests. This is not proof of real-phone geofence delivery or battery behavior.

## Install

1. Open the successful **Android build and checks** Actions run linked from the implementation PR.
2. Download `hamster-cage-preview-<commit>` and unzip it.
3. Compare the APK SHA-256 with `SHA256SUMS`; `BUILD.txt` records the source commit.
4. Transfer the APK to your Android phone and open it. Allow installation from that specific file manager/browser when Android asks, then turn that installation permission off again afterward.
5. Add office coordinates locally. Explain/grant precise foreground location first, then background location in the separate Android settings step. Review the app's tracking-health message.

The preview package is `dev.hamstercage.preview`; no Google Play publication or account is required. Office coordinates must never be posted to public GitHub issues/screenshots.

## Preview signing and data

This is a debug-signed development preview, separate from production. A build signed with a different development key cannot update it in place. Do not uninstall a populated preview casually: the MVP intentionally excludes generic cloud/device-transfer backup and currently has no sync/export recovery. Stable private signing and explicit recovery are required before adopting a production daily-use release.

## First-day behavior

The app cannot reconstruct attendance before you configured it. Partial and pre-install history is shown as incomplete. Add any missing manual sessions in History, then explicitly review a completed day. Reviewing today requires active monitoring and confirms the record only through now. WFH stays in the denominator; holidays must be explicitly configured. Grace-adjusted time is an estimate and Android boundary delivery can be late.

## Owner phone acceptance

Test with synthetic configuration first: foreground/background grant, denial/regrant, process restart, reboot, leaving/reentering with app closed, delayed events and correction, settings persistence, and offline history/dashboard. Do not mark real-device capture or battery gates complete from emulator tests alone.
