# Issue #11 design evidence

Captured on the local Pixel 10 Pro XL emulator running Android 17/API 37 (system image 37.1), 2026-09-24 UTC. Screens show the synthetic shell only. No attendance records, real coordinates or user content are involved.

| Evidence | Configuration |
| --- | --- |
| [Standard text](dashboard-360dp-100pct.png) | 1080×1920, density 480, 360dp width, system font scale 1.0 |
| [Large text](dashboard-360dp-200pct.png) | Same display, system font scale 2.0 |
| [Large text scrolled](dashboard-360dp-200pct-scrolled.png) | Same large-text page scrolled to its final content; all four destinations remain available |
| [Selected full-color launcher](launcher-standard.png) | Installed `dev.hamstercage.preview`, Pixel launcher Default style / Circle shape |
| [Themed circular launcher](launcher-themed-circle.png) | Pixel launcher Minimal style / Circle shape, using the packaged monochrome layer |
| [Themed rounded-square launcher](launcher-themed-square.png) | Pixel launcher Minimal style / Square shape, its rounded-square mask |
| [Simulated mask and small-size sheet](icon-mask-fixtures.png) | Packaged layers rendered with circle/squircle masks, light/dark monochrome, 24/36/48px samples; **not a device screenshot** |

The two launcher styles preserve the hamster, laptop/chart and enclosing ring. Full color also preserves the peach bow and typing paws. The adaptive layers place the complete artwork at 64dp within a 108dp layer, leaving safe mask padding. The small-size fixture makes the remaining loss of fine detail visible instead of claiming full-resolution detail at every size.

Five instrumentation tests passed at both native emulator display settings and the 360dp/200% configuration. These check navigation/recreation, large labels and touch targets, manifest-selected installed icon resources, monochrome availability and alpha bounds. Contrast unit checks pass at 4.5:1 or better for defined text/action combinations. The issue PR links the tested commit and records its APK checksum/build/signing identity.

Display size, font scale and launcher Default/Circle style were restored after capture. Physical-phone and other OEM launcher behavior have not been tested. The broader background/battery checks belong to the later Product MVP integration gates.
