# Hamster Cage roadmap

[Project board](https://github.com/users/EternalLiquet/projects/3) · [Issues](https://github.com/EternalLiquet/hamster-cage/issues) · [Delivery milestones](https://github.com/EternalLiquet/hamster-cage/milestones)

Phases are delivery checkpoints. Epics group coherent pages or capabilities. Feature issues deliver complete vertical slices, and each epic has a separate Security Pass. Milestones mark acceptance of a phase; they do not replace epic issues.

**Product MVP ends at Phase 5.** Phases 6–8 continue the product after the local app is usable. Page work can proceed concurrently once its actual shared contracts are stable; phase numbering does not require idle agents or a rigid waterfall.

## Delivery map

| Phase | Epic / outcome | Features | Security pass | Milestone |
| --- | --- | ---: | --- | --- |
| 0 | [Bootstrap, shared foundations and security baseline](https://github.com/EternalLiquet/hamster-cage/issues/1) | 5 | [#15](https://github.com/EternalLiquet/hamster-cage/issues/15) | [Checkpoint 0](https://github.com/EternalLiquet/hamster-cage/milestone/1) |
| 1 | [Offices and passive capture MVP](https://github.com/EternalLiquet/hamster-cage/issues/2) | 4 | [#20](https://github.com/EternalLiquet/hamster-cage/issues/20) | [Checkpoint 1](https://github.com/EternalLiquet/hamster-cage/milestone/2) |
| 2 | [Dashboard and attendance intelligence MVP](https://github.com/EternalLiquet/hamster-cage/issues/3) | 3 | [#24](https://github.com/EternalLiquet/hamster-cage/issues/24) | [Checkpoint 2](https://github.com/EternalLiquet/hamster-cage/milestone/3) |
| 3 | [History and corrections MVP](https://github.com/EternalLiquet/hamster-cage/issues/4) | 3 | [#28](https://github.com/EternalLiquet/hamster-cage/issues/28) | [Checkpoint 3](https://github.com/EternalLiquet/hamster-cage/milestone/4) |
| 4 | [Settings, calendar and privacy MVP](https://github.com/EternalLiquet/hamster-cage/issues/5) | 3 | [#32](https://github.com/EternalLiquet/hamster-cage/issues/32) | [Checkpoint 4](https://github.com/EternalLiquet/hamster-cage/milestone/5) |
| 5 | [Product MVP integration and installation gate](https://github.com/EternalLiquet/hamster-cage/issues/6) | 2 | [#35](https://github.com/EternalLiquet/hamster-cage/issues/35) | [Checkpoint 5](https://github.com/EternalLiquet/hamster-cage/milestone/6) |
| 6 | [Notifications, widget and usability enhancements](https://github.com/EternalLiquet/hamster-cage/issues/7) | 3 | [#39](https://github.com/EternalLiquet/hamster-cage/issues/39) | [Checkpoint 6](https://github.com/EternalLiquet/hamster-cage/milestone/7) |
| 7 | [Optional secure private homelab sync](https://github.com/EternalLiquet/hamster-cage/issues/8) | 5 | [#45](https://github.com/EternalLiquet/hamster-cage/issues/45) | [Checkpoint 7](https://github.com/EternalLiquet/hamster-cage/milestone/8) |
| 8 | [Release reliability and privacy enhancements](https://github.com/EternalLiquet/hamster-cage/issues/9) | 3 | [#49](https://github.com/EternalLiquet/hamster-cage/issues/49) | [Checkpoint 8](https://github.com/EternalLiquet/hamster-cage/milestone/9) |

Created backlog: **9 epics, 31 feature issues and 9 explicit Security Pass issues**. Each feature records user value, scope/non-goals, objective acceptance criteria, UX/data impact, tests, privacy and actual prerequisite issue links.

## Product MVP acceptance

An APK is a concrete delivery artifact, but compilation alone does not prove the Product MVP. [Integration #33](https://github.com/EternalLiquet/hamster-cage/issues/33), [artifact delivery #34](https://github.com/EternalLiquet/hamster-cage/issues/34) and [security gate #35](https://github.com/EternalLiquet/hamster-cage/issues/35) require evidence.

- Configure two independent eligible offices, radius, separate entry/exit grace and staged location permissions.
- Persist validated background ENTER/EXIT facts; represent permission loss, registration failures and ambiguous gaps honestly.
- Show today, week, inclusive rolling 30/90-day metrics and named departure targets using one deterministic engine.
- Browse daily explanations and correct missed/incorrect sessions without overwriting raw observations.
- Configure policy timezone, expected weekdays, target, exclusions and WFH; provide deliberate privacy/data controls.
- Keep capture, calculations, history and settings usable offline; no homelab or account is required.
- Produce a traceable installable APK with checksum/commit/version, installation notes and an honest debug/release/signing description.
- Pass affected engine, storage, integration, security and packaged-manifest checks. Record physical-device-only validation separately when no physical device is available.

Notifications, widgets, sync, export and multi-day battery/OEM validation are post-MVP enhancements. Do not silently remove geofencing, corrections, calendar rules or security from the Product MVP just to make a build pass. If a gate remains unverified, label the build a preview/candidate and name the gap.

## Execution order and concurrency

1. Stabilize bootstrap #10, design #11, storage #12, engine #13 and CI #14. Security #15 begins alongside them.
2. With those contracts stable, split work by owned files: Offices/capture #16–20; Dashboard/metrics #21–24; History/corrections #25–28; Settings/calendar/privacy #29–32.
3. Integrate page branches, resolve shared-contract changes centrally, run #33 and #35, then deliver #34. A passed Security Pass is necessary for each completed epic.
4. Continue independent glance enhancements #36–39 and private sync #40–45 after the Product MVP. Release reliability #46–49 follows real usage; it must assess sync only when sync is included in that release.

Each feature issue lists concrete prerequisite issues. Epic dependency graph: 0 precedes 1/2/3/4; all four page MVPs precede 5; 5 precedes 6/7/8. Native parent/blocker metadata is additional to these explicit readable links; see the metadata status below.

## Agent operating rules

- Assign file ownership and use branches/worktrees where appropriate. Keep one integrator responsible for shared schema/contracts and build configuration.
- Work from the actual repository state; inspect existing changes and avoid duplicate work. Do not close an issue merely because some code resembles its scope.
- Put test commands/results and tested commit in the issue/PR evidence. Keep runtime/device limitations explicit.
- Use synthetic data in the public repository, issues, screenshots, test fixtures and CI artifacts.
- Preserve raw facts. Changes in grace/calendar/policy recompute derived results; they do not rewrite observations.
- Default to Monday–Friday, 360 minutes per expected day, holiday exclusions and WFH remaining in the denominator. Employer policy is configurable and not represented as independently verified.
- Open sessions accrue only through now. Exit grace can inform a departure estimate without adding future credit to current aggregates.
- Keep INTERNET absent until an implemented feature requires networking. Sync is optional, tailnet-private HTTPS with normal TLS validation and separate revocable per-device authentication.
- No public attendance API, router forwarding, Tailscale Funnel, analytics or continuous GPS service. No secrets in source; no custom cryptography.

## GitHub metadata

Project: **Hamster Cage Roadmap**, owner EternalLiquet, project #3. Auto-import for open issues in this repository was enabled during creation.

Nine phase milestone checkpoints are assigned to all epics/features. Labels distinguish `type:epic`, `type:feature`, `security`, capability areas and `phase:0`–`phase:8`. `P0` identifies the Product MVP path; post-MVP issues use `P1`. `P2` is available for later optional ideas.

Native sub-issue relationships, blocking links and additional custom Project fields could not be completed: the GitHub browser connection became unresponsive after project/milestone/label creation. The epic child checklists and linked dependency sections are the current relationship record. They are not native sub-issues or blocking relationships. The Project exists, but auto-import completion and extra fields have not been verified.

## Source authority and open decisions

Product rules and design/security sources remain in the [private project-source directory](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage). The [visual reference and attribution](https://github.com/EternalLiquet/gpt-projects-files/tree/master/hamster-cage/visual-reference) are private planning material, not application assets.

The current owner instruction refines the original roadmap into page MVP checkpoints plus a Product MVP gate and authorizes implementation. It supersedes earlier planning-only wording and the former phase numbering.

Decisions intentionally deferred to their implementing features: additional application-level local DB encryption based on threat model; homelab at-rest/recovery key model; service language; explicit mutable-record conflict policy; final release signing/update process. None justifies blocking local core development or weakening security. Exact employer windows, holiday calendar and WFH rules remain user-configurable assumptions.
