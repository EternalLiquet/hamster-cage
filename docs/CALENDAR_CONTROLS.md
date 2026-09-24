# Exclusions and WFH controls (#30)

Settings has a bounded, ten-date list and local forms for exclusions and WFH labels. Bank holiday, company closure, PTO and other excused reasons remove an expected day's requirement; WFH alone does not. Both retain recorded office credit. No employer feed or calendar-account permission is introduced.

The ISO date input rejects malformed dates and accepts future policy dates. Saving the same exclusion date replaces its reason/note deterministically. WFH is independent: editing/removing either record preserves the other. The explanation appears before the controls; exclusion notes stay in app-private Room storage. Removing an exclusion immediately restores the engine's usual requirement for that day. The annual WFH count uses the policy year.

Writes use the existing local repository operations. Success appears only after commit; failures preserve the draft and show a sanitized retry message. Draft fields survive restoration and a cancelled save does not restore a permanently busy form. No schema, raw observation or correction is changed.

Focused checks cover strict date/note validation; add/edit/remove and failure/restoration UI; duplicate dates, overlapping exclusion/WFH, future dates, Room/DataStore reopen and engine recomputation with unchanged attendance facts. Exact-head CI and independent acceptance evidence are recorded in the PR; physical-phone tests are not implied.
