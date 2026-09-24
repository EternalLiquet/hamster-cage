# Hamster Cage

Hamster Cage is an Android-first, local-first personal RTO attendance tracker. The Phase 0 foundation has four offline destinations, a shared accessible design system, and the owner-selected hamster launcher artwork. Attendance features are not implemented in this shell.

Product, security, synchronization, UX source material, and visual references are maintained in the private [project-source repository](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). Development follows the phased [roadmap](docs/ROADMAP.md).

## Privacy direction

The phone remains the primary working datastore. A later secure sync layer may replicate encrypted/project data to the owner's homelab only when the device is connected to the owner's Tailscale network. No public Internet-facing attendance API is planned.

## Build and install

Follow the [toolchain, build, installation and architecture notes](docs/BUILD_AND_INSTALL.md). See [MVP status](docs/MVP_STATUS.md) for verified evidence and remaining work. This shell supports Android 8.0+ and needs no user-granted permissions; it does not collect attendance.

## Visual source policy

The visual reference and its attribution README live under `hamster-cage/visual-reference/` in the private project-source repository. They are design inspiration only and are never bundled. The app uses shared charcoal/navy, amber and peach tokens and the owner-selected hamster-at-computer icon. See the [design system and branding provenance](docs/DESIGN_SYSTEM.md).
