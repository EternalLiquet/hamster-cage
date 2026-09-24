# Hamster Cage

Hamster Cage is an Android-first, local-first personal RTO attendance tracker. This branch delivers the issue #10 app shell: four offline destinations and a testable foundation. Attendance features are not implemented in this shell.

Product, security, synchronization, UX source material, and visual references are maintained in the private [project-source repository](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). Development follows the phased [roadmap](docs/ROADMAP.md).

## Privacy direction

The phone remains the primary working datastore. A later secure sync layer may replicate encrypted/project data to the owner's homelab only when the device is connected to the owner's Tailscale network. No public Internet-facing attendance API is planned.

## Build and install

Follow the [toolchain, build, installation and architecture notes](docs/BUILD_AND_INSTALL.md). See [MVP status](docs/MVP_STATUS.md) for verified evidence and remaining work. This shell supports Android 8.0+ and needs no user-granted permissions; it does not collect attendance.

## Visual source policy

The visual reference and its attribution README live under `hamster-cage/visual-reference/` in the private project-source repository. They are design inspiration only. This shell uses the standard Material dark theme and an original geometric launcher placeholder. Issue #11 delivers the shared design system and owner-selected hamster-at-computer icon within Phase 0.
