# Build and install the local MVP preview

The installable preview is `dev.hamstercage.preview`, version `0.1.0-preview-debug` (version code 3), for Android 8.0 / API 26 and later. It is a **debuggable, development-signed APK**, separate from `dev.hamstercage`; it is not a production-signed release. The app permits user-requested office address lookup and map tiles over HTTPS. Attendance facts, calculations, routine capture and history stay local and usable offline; there is no account, sync, analytics, export or automatic backup. Uninstalling or losing a device can permanently lose its local record.

## Download and verify one exact build

1. Open the successful **Android build and checks** Actions run for the independently verified source commit. Download `hamster-cage-preview-<full source SHA>` from its artifacts and unzip it. CI keeps artifacts for 14 days. A later run, even at the same source, may use a different debug signing key.
2. Keep all four files together: `hamster-cage-preview.apk`, `SHA256SUMS`, `BUILD.txt` and `SIGNATURE.txt`. Confirm that `BUILD.txt` names the intended full source SHA, package, version code/name, debug build, APK SHA-256 and public signing-certificate SHA-256. The artifact name and `BUILD.txt` must identify the same commit.
3. In that extracted directory, run `sha256sum --check SHA256SUMS` (or compare PowerShell `Get-FileHash -Algorithm SHA256 .\hamster-cage-preview.apk` with both `SHA256SUMS` and `BUILD.txt`). A mismatch means stop: do not install the APK. Optionally run Android build-tools `apksigner verify --print-certs hamster-cage-preview.apk` and compare the signer certificate SHA-256 digest with `BUILD.txt` and `SIGNATURE.txt`.

The [CI workflow](../.github/workflows/android.yml) checks out the exact PR head or master commit, tests it, builds the debug APK, verifies its signature, and publishes the APK with those metadata files. `scripts/artifact_provenance.py` refuses a dirty checkout, an unexpected variant, mismatched checksum or missing signing digest. The completed #34 evidence identifies the earlier version-code-2 hosted artifact. For [#77 PR #82](https://github.com/EternalLiquet/hamster-cage/pull/82#issuecomment-5819709371), the independently checked hosted version-code-3 APK from source `d91a7fa7db8e50ce7e408aa996a6798dcfca6cf5` has SHA-256 `bb9d0d864f43e496477e3842e8242b12fba042a69f446d7f29c6c9b642a479d1` and development signing-certificate SHA-256 `fc05ae1e28b334d08fdca690cb1e10f440d0d6c887862b7aa36df66ef4eb0f3c`. Its package is `dev.hamstercage.preview`, version code 3, version name `0.1.0-preview-debug`. All five CI checks succeeded at that source; any later head must renew exact-head checks. Never rely on a PR number alone to identify APK bytes.

## Install or update

For a clean install, use a fresh emulator/device or confirm that `dev.hamstercage.preview` has no valuable data. Android's file manager can open the verified APK after you allow installs from that specific app, or use ADB:

```sh
adb install hamster-cage-preview.apk
adb shell am start -W -n dev.hamstercage.preview/dev.hamstercage.MainActivity
```

For an existing preview installation, first preserve its data by leaving it installed. Compare the installed package and signing certificate with the proposed APK. An in-place update requires the same package, the same signing key and a nondecreasing version code; use `adb install -r hamster-cage-preview.apk` only when those conditions hold. Version code 3 can update version code 2 from the **same local debug key** and retain its private data. GitHub-hosted runners or a different development machine can produce a different key, so their APK may fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Stop at that error. Do not uninstall a populated preview just to force an update: there is no supported export/sync recovery path.

If installing through a file manager, turn its “install unknown apps” permission off after the install. Office address search sends submitted text to the device's geocoding implementation, which may use a network service; opening map review requests OpenStreetMap tiles for the viewed area. See the [provider disclosure and offline alternatives](OFFICE_LOCATION_SETUP.md). Search does not need location permission. Grant precise foreground location only if you choose **Use my current location** or automatic office detection; Android asks for background location separately for automatic capture. You can deny location and still browse or edit local attendance or use Advanced manual coordinates. Enter offices on the device; never post real coordinates or attendance in public issue evidence.

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
2. Configure a synthetic office: enter a name, explicitly submit an address, choose a result, review the map pin/radius and Save. Also check **Use my current location**, permission denial and the Advanced manual-coordinate fallback, including the offline/unavailable case. Check the separate background-location step for automatic capture. Registration only means boundary requests were accepted; it does not prove that a transition was delivered.
3. With synthetic facts, check that history explains credited intervals, corrections append, calendar exclusions/WFH and policy settings survive a process restart, and unknown coverage stays explicit. Avoid real coordinates in screenshots or logs.
4. On a same-key update, confirm those synthetic offices/policy facts remain. After a separately confirmed history delete, confirm attendance/calendar facts disappear while offices/base policy remain; full reset intentionally clears all app data.

On 2026-09-24, the version-code-3 **pre-PR phone checkpoint** APK from source commit `86aa7d34fd3b0f4ad177808c25944f84c0dce40e` was installed over version 2 on a Samsung Android API 36 phone using `adb install -r`; install and MainActivity launch both succeeded without uninstalling or clearing data. Its SHA-256 is `1a76e8e4eb4262e5e18ec1fef3d3ce0ca1ae80a74d9ce5e3a9f2264efeb37c59`, and its debug signing-certificate SHA-256 is `2af9f1b4d765f6fc10727071b7570d7b225f47026e514f935322778c8517ead1` (matching the installed version 2 key). The exact bytes and metadata are preserved locally in ignored `.delivery/issue-77-phone-checkpoint/`; later #77 app changes require a new final artifact. This verifies install/launch only, not office-setup behavior on that phone.

The hosted #77 APK above uses a different development key and cannot update that installed phone package in place. The final source needs a newly identified **same-key local APK** for a non-destructive phone update; do not uninstall the existing app to force the hosted build onto it.

Emulator install/update smoke supports packaging and local persistence only. **Physical-phone geofence delivery, UI-closed background behavior, reboot/force-stop recovery, battery/OEM behavior and manufacturer backup/device-transfer behavior remain unverified.** Those are separate open acceptance gates; this debug APK does not establish production release security.
