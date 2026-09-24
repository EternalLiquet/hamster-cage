# Hamster Cage

Hamster Cage is an Android-first, local-first personal RTO attendance tracker.

Product, security, synchronization, UX source material, and visual references are maintained in the private [project-source repository](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). Development follows the phased [roadmap](docs/ROADMAP.md).

## Privacy direction

The phone remains the primary working datastore. A later secure sync layer may replicate encrypted/project data to the owner's homelab only when the device is connected to the owner's Tailscale network. No public Internet-facing attendance API is planned.

## Visual source policy

The visual reference and its attribution README live under `hamster-cage/visual-reference/` in the private project-source repository. They are design inspiration only. The application uses an original dark-mode design system and does not bundle third-party character artwork.
