# Hamster Cage engineering

Read `docs/ROADMAP.md`, `docs/MVP_STATUS.md` and the issue before editing. Product sources live in private `EternalLiquet/gpt-projects-files/hamster-cage/`; visual references live in its `visual-reference/` directory. Do not bundle that artwork.

The owner authorizes autonomous implementation through Phase 5, branches, pushes, PRs, evidence comments, passing merges and satisfied issue closure. No scheduled tasks or post-MVP enhancements. Never race another operation on a branch. Preserve repository controls and report external blockers.

## Delivery workflow: one issue, one PR

- Every feature and Security Pass issue gets a focused branch and PR. Epics are tracking containers, never implementation PRs.
- PR #50 stays a draft/reference; never merge it wholesale. Extract useful implementation in dependency order from its preserved source. Reconcile anything already merged before starting work.
- Use separate worktrees and disjoint ownership for independent issues. Every PR identifies exactly one issue, its scope, acceptance criteria and verification evidence.
- Three distinct agents perform implementation, independent verification, and independent review/integration. The implementer writes/tests and opens the PR. The verifier independently reads the issue, inspects the code and runs checks, recording PASS/FAIL, exact commit and unverified criteria. The reviewer independently checks correctness, maintainability, security/privacy, scope and verification evidence.
- Failed verification or blocking review returns concrete findings to the implementer. Every new head requires renewed verification and review; old approval does not cover new commits.
- One integration agent serializes merges. Merge only when independent verification passes, review has no unresolved blockers, current-head CI passes and repository requirements are satisfied. Refresh dependent branches and rerun affected checks after prerequisite merges.
- Shared GitHub identity is allowed; never fabricate human independence or bypass self-approval restrictions, required approvals, branch protections or security gates. Record any exact blocker and continue other eligible issues.
- Close a feature/Security Pass only after its PR merges and all acceptance criteria have evidence. Close an epic only after every child and its explicit Security Pass complete.
- The coordinator automatically dispatches implementation → verification → review → merge → next ready issue, including rework. PR creation or stage completion is an intermediate checkpoint, not a stopping point. Continue until the Product MVP is evidenced, all remaining work is blocked, or execution/usage limits prevent continuation.
- Keep issue/PR evidence and `docs/MVP_STATUS.md` current. Include the owner-selected hamster-at-computer launcher artwork in Phase 0; retain private visual references in the private source repository.

## Invariants

- Kotlin/Compose; framework-free `core-domain`; Room source facts and DataStore preferences.
- Fully local operation. No INTERNET permission, public backend, analytics or continuous GPS in MVP.
- Raw events immutable; corrections append; every credited minute explainable. Never fabricate raw events for an observed transition.
- Interval union prevents double credit. Holidays remove expected days; WFH does not. Time stored as Instant; policy timezone determines days. Missing history is explicit, never falsely zero or full coverage.
- Real coordinates, personal attendance, credentials, signing keys and sensitive logs never enter Git or public issues.
- Security passes in every epic; app-private storage, explicit backup exclusions, reviewed IPC/PendingIntent and permission flows.

## Verification and publication

Run `./gradlew :core-domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and `python3 scripts/security_check.py`. Test attendance changes across happy, malformed and boundary cases including DST where relevant. Do not weaken checks to get green. Inspect exact-head CI. Keep APK build type, source SHA, checksum and signing caveat together. Do not claim emulator/device checks ran unless they did.

The local install preview is a debug build with a separate `.preview` package; production signing and real-device background/battery validation remain explicit gates. Public release hardening is post-MVP, but critical correctness/privacy defects block MVP. Each work checkpoint updates `docs/MVP_STATUS.md`. Source policy changes also update the corresponding private project source.
