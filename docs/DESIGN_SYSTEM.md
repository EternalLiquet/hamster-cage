# Shared visual and launcher foundation

Issue #11 centralizes color, spacing, type and shape tokens in `CageStyle` / `HamsterTheme`. Core pages consume the shared page heading, panel and notice components. Reusable metric rows, status tags, buttons and error text are ready for later feature pages; numerical examples exist only in explicitly synthetic Compose previews.

The palette uses charcoal/navy surfaces, amber primary actions, peach secondary color and restrained pink accents. Text roles, amber/peach action text and error text are checked against a 4.5:1 contrast floor. Status has readable labels; selection has a visible indicator or checkmark and accessibility selection state. Cards can grow with content. Text at 150% or more switches navigation to two columns and metric rows to a vertical arrangement. Buttons have at least 48dp touch targets. No custom animation bypasses system motion settings.

## Selected launcher artwork

The owner selected the original female golden hamster with peach bow, typing paws, laptop and amber ring. The original stays in the private project source: [pinned selected PNG](https://github.com/EternalLiquet/gpt-projects-files/blob/5d597258a8f98eee2d26f147c97f7447a2bfe133/hamster-cage/branding/hamster-cage-icon.png), SHA-256 `6c1904727b647cf36c08eda6d799e4529e925b3720c961df821c50ab108675f6`. This is the owner's generated branding, separate from the third-party aesthetic reference that is never bundled.

Only derived runtime resources are committed. Build/runtime never access the private source or download images. The foreground uses the complete selected artwork centered at 64dp in a 108dp layer, inside the [Android adaptive icon safe zone](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). The background is solid charcoal. Both `android:icon` and `android:roundIcon` point to adaptive resources; density-specific 48/72/96/144/192px PNGs provide legacy fallbacks. API 33 resources add a monochrome layer derived from the original light forms and dark negative space, preserving a recognizable hamster/laptop/ring motif.

Asset hashes and source provenance are recorded in `branding-resources.json`. To regenerate resources locally, supply the selected original to `python scripts/generate_launcher_assets.py /local/path/hamster-cage-icon.png` using Python 3 with Pillow. The script checks the source checksum before writing. It also produces `.delivery/icon-mask-fixtures.png` with circular/squircle, light/dark themed and 24/36/48px samples. These are simulated resource review fixtures, not device screenshots.

## Verification

Unit checks cover text/action contrast. Instrumentation checks exercise 200% navigation readability and 48dp controls, manifest-selected adaptive/themed resources, and alpha bounds within the safe zone. All five instrumentation tests passed on the Pixel emulator, Android 17/API 37, both at its native screen size and at 360dp with 200% system text. The large-text test caught a clipped navigation label; a two-column layout with wrapping labels and a separate selection mark fixes it.

[Evidence screenshots](evidence/issue-11/README.md) show 100%/200% application text, the installed full-color launcher, and themed Circle and rounded Square masks. The separate fixture sheet covers circle/squircle, light/dark monochrome and 24/36/48px sizes. OEM masks can vary; these checks establish the tested emulator and packaged safe bounds, not every physical launcher. Physical-phone validation remains outstanding.
