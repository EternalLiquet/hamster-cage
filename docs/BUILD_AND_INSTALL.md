# Build and install the local MVP preview

The installable preview is `dev.hamstercage.preview`, version `0.1.0-preview-debug` (version code 2), for Android 8.0 / API 26 and later. It is a **debuggable, development-signed APK**, separate from `dev.hamstercage`; it is not a production-signed release. The app itself has no INTERNET permission, account, sync, analytics, export or automatic backup. Uninstalling or losing a device can permanently lose its local record.

## Download and verify one exact build

1. Open the successful **Android build and checks** Actions run for the independently verified source commit. Download `hamster-cage-preview-<full source SHA>` from its artifacts and unzip it. CI keeps artifacts for 14 days. A later run, even at the same source, may use a different debug signing key.
2. Keep all four files together: `hamster-cage-preview.apk`, `SHA256SUMS`, `BUILD.txt` and `SIGNATURE.txt`. Confirm that `BUILD.txt` names the intended full source SHA, package, version code/name, debug build, APK SHA-256 and public signing-certificate SHA-256. The artifact name and `BUILD.txt` must identify the same commit.
3. In that extracted directory, run `sha256sum --check SHA256SUMS` (or compare PowerShell `Get-FileHash -Algorithm SHA256 .\hamster-cage-preview.apk` with both `SHA256SUMS` and `BUILD.txt`). A mismatch means stop: do not install the APK. Optionally run Android build-tools `apksigner verify --print-certs hamster-cage-preview.apk` and compare the signer certificate SHA-256 digest with `BUILD.txt` and `SIGNATURE.txt`.

The [CI workflow](../.github/workflows/android.yml) checks out the exact PR head or master commit, tests it, builds the debug APK, verifies its signature, and publishes the APK with those metadata files. `scripts/artifact_provenance.py` refuses a dirty checkout, an unexpected variant, mismatched checksum or missing signing digest. This preview artifact is not pinned as a permanent public release; use the run and source SHA cited in the completed #34 evidence before installing. Never rely on a PR number alone to identify APK bytes.

## Install or update

For a clean install, use a fresh emulator/device or confirm that `dev.hamstercage.preview` has no valuable data. Android's file manager can open the verified APK after you allow installs from that specific app, or use ADB:

```sh
adb install hamster-cage-preview.apk
adb shell am start -W -n dev.hamstercage.preview/dev.hamstercage.MainActivity
```

For an existing preview installation, first preserve its data by leaving it installed. Compare the installed package and signing certificate with the proposed APK. An in-place update requires the same package, the same signing key and a nondecreasing version code; use `adb install -r hamster-cage-preview.apk` only when those conditions hold. Version code 2 can update version code 1 from the **same local debug key** and retain its private data. GitHub-hosted runners or a different development machine can produce a different key, so their APK may fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Stop at that error. Do not uninstall a populated preview just to force an update: there is no supported export/sync recovery path.

If installing through a file manager, turn its “install unknown apps” permission off after the install. Grant precise foreground location when you choose automatic office detection; Android asks for background location separately. You can deny either and still browse or edit local attendance. Enter offices on the device; never post real coordinates or attendance in public issue evidence.

## Rebuild locally

Use Python 3.11 or later, JDK 17, Android SDK platform 36, build-tools 35.0.0, platform-tools and the committed Gradle 8.11.1 wrapper. Set `JAVA_HOME` and `ANDROID_HOME` (or untracked `local.properties` with `sdk.dir`). From a clean checkout:

```sh
python3 scripts/verify_wrapper.py
python3 -m unittest discover -s scripts -p 'test_*.py' -v
./gradlew :core-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:writeDependencyInventory
python3 scripts/security_check.py
python3 scripts/dependency_audit.py
```

The local APK is `app/build/outputs/apk/debug/app-debug.apk`. On PowerShell use `.\gradlew.bat` and `python`. The wrapper validates its distribution checksum. The dependency audit queries OSV; an unavailable audit service is not a pass. CI also runs CodeQL, Gitleaks and API 35 instrumentation. GitHub dependency review remains unavailable while the repository Dependency Graph is disabled. Local and hosted debug signing identities can differ; always record the **actual** APK SHA-256, source SHA, version, package, build type and certificate digest together. Production signing is not configured, and `assembleRelease` is not an installable signed product release.

## First-morning smoke

1. Confirm the installed package/version, open Dashboard, Offices, History and Settings offline, and confirm there is no existing attendance after a clean install. Missing pre-install history must show as unknown, never a complete zero.
2. Configure a synthetic office first. Check denial, precise foreground grant and the separate background-location step. Registration only means boundary requests were accepted; it does not prove that a transition was delivered.
3. With synthetic facts, check that history explains credited intervals, corrections append, calendar exclusions/WFH and policy settings survive a process restart, and unknown coverage stays explicit. Avoid real coordinates in screenshots or logs.
4. On a same-key update, confirm those synthetic offices/policy facts remain. After a separately confirmed history delete, confirm attendance/calendar facts disappear while offices/base policy remain; full reset intentionally clears all app data.

An emulator install/update smoke supports packaging and local persistence only. **No physical-phone geofence delivery, UI-closed background behavior, reboot/force-stop recovery, battery/OEM behavior or manufacturer backup/device-transfer behavior has been verified.** Those are separate open acceptance gates; this debug APK does not establish production release security.
