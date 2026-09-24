# Build and install the shell

Issue #10 supports Android 8.0 / API 26 and later. The current four destinations are offline shell pages; capture, storage and attendance calculations arrive in their own issue PRs.

## Toolchain

- JDK 17, selected using `JAVA_HOME`.
- Android SDK command-line tools, platform `android-36`, build tools `35.0.0`, and platform tools.
- `ANDROID_HOME` pointing to the SDK, or an untracked `local.properties` containing `sdk.dir`.
- Gradle 8.11.1 via the committed wrapper, with distribution SHA-256 verification; Android Gradle Plugin 8.10.1 and Kotlin 2.1.20 are pinned in the version catalog.
- Python 3 for the manifest/privacy audit.

Accept Android SDK licenses with `sdkmanager --licenses`, then install `sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"`. A first build downloads dependencies. The installed application does not need network access.

From a clean checkout:

```sh
python3 scripts/verify_wrapper.py
python3 -m unittest discover -s scripts -p 'test_*.py' -v
./gradlew :core-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:writeDependencyInventory
python3 scripts/security_check.py
python3 scripts/dependency_audit.py
```

See [CI checks](CI.md) for scanner limits, wrapper integrity and manual dependency upgrades. Successful Android CI runs publish `hamster-cage-preview-<source SHA>` containing the APK, `SHA256SUMS`, source/package/version metadata and signing-certificate digest. Verify the checksum before installing; hosted debug keys may differ between runs.

On Windows PowerShell use `.\gradlew.bat` and `python` with the same arguments. Domain tests can run separately using `./gradlew :core-domain:test` without an emulator or Android framework.

## Install and test

Start an Android emulator or connect an authorized phone with USB debugging:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n dev.hamstercage.preview/dev.hamstercage.MainActivity
./gradlew :app:connectedDebugAndroidTest
```

Open Dashboard, Offices, History, and Settings with device networking disabled. The instrumentation suite exercises all destinations and Activity recreation. These are shell checks; they do not prove geofence or battery behavior.

The APK uses package `dev.hamstercage.preview`, version `0.1.0-shell-debug`, build type **debug**, and the local Android development signing key. It is not a production-signed release. Another machine's debug key cannot update it in place. `assembleRelease` produces an unsigned release artifact until a separate secure signing process is configured. Never commit signing keys.

Before sharing an APK, record its source commit (`git rev-parse HEAD`) and SHA-256 (`sha256sum app/build/outputs/apk/debug/app-debug.apk`, or PowerShell `Get-FileHash -Algorithm SHA256`). Keep those values with its version, package, build type and signing caveat. Phase 5 owns the final Product MVP artifact.

## Boundaries

`core-domain` is a Kotlin/JVM module with no Android, Compose, storage, or network dependencies. `TimeSource` supplies an explicit evaluation instant and derives dates in a supplied timezone.

`app/data` contains platform adapters implementing domain contracts; the shell's `SystemTimeSource` wraps an injectable `java.time.Clock`. Room/DataStore are intentionally added with storage issue #12.

`app/ui` contains Compose navigation and presentation, depending only on the time contract. `MainActivity` composes the platform adapter and UI. Its single exported launcher Activity is the only application entry point. The shell declares no location, network or shared-storage permissions, and no capture receivers, services, or analytics.

The merged manifest also contains AndroidX's nonexported initialization provider, its signature-protected dynamic-receiver permission, and the profile installer receiver protected by the OS `DUMP` permission. These library components are included in the manifest audit; the shell has no user-granted permissions or runtime permission prompts.

`scripts/security_check.py` inspects the merged debug manifest and backup exclusions after assembly, including components contributed by dependencies. Issue #14 extends CI and security tooling; each later feature adds its own meaningful tests.
