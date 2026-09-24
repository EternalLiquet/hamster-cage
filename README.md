# Hamster Cage

Hamster Cage is a local Android attendance preview for tracking time at configured offices. Its four offline destinations cover office setup and staged location permission, a live dashboard with week and rolling targets, daily history with append-only corrections, and policy, calendar and privacy controls. Attendance is reconstructed from local source facts with explicit unknown coverage; it does not assume presence across missing events. The Product MVP is still in progress, and the [status ledger](docs/MVP_STATUS.md) records accepted features and remaining gates.

Product, security, synchronization, UX source material, and visual references are maintained in the private [project-source repository](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). Development follows the phased [roadmap](docs/ROADMAP.md).

## Privacy

The phone holds the working record in app-private storage. The preview has no INTERNET permission, account, backend, analytics, export or sync, and generic cloud/device-transfer backup is excluded. Settings distinguishes confirmed attendance/calendar deletion from a full Android app-data reset. Deleting or uninstalling the app can permanently lose local history. Optional private sync belongs to later work outside this MVP.

## Build and install

Follow the [preview build and installation guide](docs/BUILD_AND_INSTALL.md). The debug `.preview` package supports Android 8.0 / API 26 and later. Automatic office capture requests precise foreground location first and background location in a separate Android step; local history and manual correction remain available when location is denied. Check the [MVP status](docs/MVP_STATUS.md) before treating an APK as accepted. Physical-phone UI-closed geofence delivery, recovery, battery behavior and production signing have not been verified.

## Visual source policy

The visual reference and its attribution README live under `hamster-cage/visual-reference/` in the private project-source repository. They are design inspiration only and are never bundled. The app uses shared charcoal/navy, amber and peach tokens and the owner-selected hamster-at-computer icon. See the [design system and branding provenance](docs/DESIGN_SYSTEM.md).
