# Hamster Cage roadmap

Product MVP ends at Phase 5. Deliver each feature and Security Pass as one focused issue PR, with separate implementation, verification, and review agents before integration. PR #50 is preserved as a draft implementation reference and must not be merged wholesale.

| Phase | Outcome | Feature issues | Security Pass |
| --- | --- | --- | --- |
| 0 | Foundation: shell, design, storage, engine, CI | #10–14 | #15 |
| 1 | Offices, user-driven location setup and passive capture | #16–19, #77 | #20 |
| 2 | Dashboard, metrics and departure estimates | #21–23 | #24 |
| 3 | History, corrections and explanations | #25–27 | #28 |
| 4 | Policy, calendar and privacy controls | #29–31 | #32 |
| 5 | Integrated Product MVP and installation | #33–34 | #35 |

The complete acceptance criteria remain in the [issue backlog](https://github.com/EternalLiquet/hamster-cage/issues) and [project board](https://github.com/users/EternalLiquet/projects/3). Features are implemented in dependency order; independent ready issues may use separate worktrees.

Phase 0 is complete: #10–15 established the native shell, selected hamster-at-computer launcher artwork, accessible design, Room/DataStore persistence, framework-free attendance reconstruction, reproducible CI and a foundation Security Pass. Dashboard, History and Settings page MVPs have closed their feature and Security Pass issues. [Office setup #77](https://github.com/EternalLiquet/hamster-cage/issues/77) now requires user-driven address search or one-shot current location, map/radius confirmation and an advanced manual fallback. Its online lookup/map exception is limited to office setup; saved offices, attendance facts, routine capture, calculations and history remain local and work offline. Offices/capture physical and UI-closed delivery, plus the integrated installable Product MVP, retain explicit open criteria; see [current evidence](MVP_STATUS.md). A scoped merge or preview build does not itself close those criteria.

[Issue #80](https://github.com/EternalLiquet/hamster-cage/issues/80) tracks a newly identified Phase 2 daily departure gap: Today must project from current-day credit and an eligible open session even while older week and rolling coverage are unknown. Its focused acceptance remains open; #78's corrected uncredited arrival grace is merged, while #79's unavailable pre-install history remains a separate concern.

The Product MVP must configure eligible offices, capture local immutable ENTER/EXIT facts, explain credited intervals, support corrections and calendar policy, and produce a traceable installable APK. Missing history must remain explicit. All feature and security acceptance criteria must be evidenced before their issues close. Phone geofencing, reboot recovery, permissions, and battery behavior need physical-device evidence.

Notifications, widgets, homelab sync, exports, and release reliability enhancements are Phases 6–8 and are outside this implementation scope. Do not create scheduled continuation tasks.

Authoritative product and security sources remain in the [private project-source directory](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). Private visual-reference artwork is planning material and is not bundled in the app; the selected launcher asset has its own public provenance and acceptance evidence.
