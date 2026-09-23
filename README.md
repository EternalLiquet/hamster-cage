# Hamster Cage

Hamster Cage is an Android-first, local-first personal RTO attendance tracker.

The project is currently in planning/bootstrap. Product, security, synchronization, and UX source material is maintained separately in the private project-source repository, while visual references that GPT Work and coding agents may need are stored under `docs/project-sources/` here.

## Privacy direction

The phone remains the primary working datastore. A later secure sync layer may replicate encrypted/project data to the owner's homelab only when the device is connected to the owner's Tailscale network. No public Internet-facing attendance API is planned.

## Visual source policy

Reference artwork under `docs/project-sources/` is design inspiration only and is not automatically licensed for bundling into application builds. The application should derive an original dark-mode design system from the references rather than ship third-party character artwork as product UI.
