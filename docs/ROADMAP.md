# Hamster Cage roadmap

Product MVP ends at Phase 5. Deliver each feature and Security Pass as one focused issue PR, with separate implementation, verification, and review agents before integration. PR #50 is preserved as a draft implementation reference and must not be merged wholesale.

| Phase | Outcome | Feature issues | Security Pass |
| --- | --- | --- | --- |
| 0 | Foundation: shell, design, storage, engine, CI | #10–14 | #15 |
| 1 | Offices and passive capture | #16–19 | #20 |
| 2 | Dashboard, metrics and departure estimates | #21–23 | #24 |
| 3 | History, corrections and explanations | #25–27 | #28 |
| 4 | Policy, calendar and privacy controls | #29–31 | #32 |
| 5 | Integrated Product MVP and installation | #33–34 | #35 |

The complete acceptance criteria remain in the [issue backlog](https://github.com/EternalLiquet/hamster-cage/issues) and [project board](https://github.com/users/EternalLiquet/projects/3). Features are implemented in dependency order; independent ready issues may use separate worktrees.

Issue #10 establishes only the native app shell, four offline navigation destinations, testable time source, and documented module boundaries. The owner-selected hamster-at-computer launcher artwork and shared visual system belong to #11, within Phase 0. Persistence belongs to #12; attendance derivation to #13; expanded CI and security automation to #14. Placeholder pages do not establish feature acceptance.

The Product MVP must configure eligible offices, capture local immutable ENTER/EXIT facts, explain credited intervals, support corrections and calendar policy, and produce a traceable installable APK. Missing history must remain explicit. All feature and security acceptance criteria must be evidenced before their issues close. Phone geofencing, reboot recovery, permissions, and battery behavior need physical-device evidence.

Notifications, widgets, homelab sync, exports, and release reliability enhancements are Phases 6–8 and are outside this implementation scope. Do not create scheduled continuation tasks.

Authoritative product and security sources remain in the [private project-source directory](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). Reference artwork is private planning material and is not bundled in the shell.
