# Local policy editing (#29)

Settings edits daily target, expected weekdays, policy timezone, short-gap reconciliation and the open-session review limit. Defaults remain 360 minutes, Monday–Friday, America/New_York, 10 minutes and 16 hours. Validation follows the reference preview: target 1–1440 whole minutes, at least one weekday, a valid timezone, gap 0–120 minutes and open-session limit 1–24 hours. The repository rejects empty weekdays on both writes and reads. An invalid legacy or versioned policy becomes unavailable without replacing its saved bytes.

The form explains that saving recalculates past/current totals and departure guidance while preserving raw observations, corrections, holidays and WFH labels. DataStore stores only policy settings. Device timezone travel does not replace the explicit saved policy zone. A compare-and-save check inside the atomic DataStore edit rejects stale forms instead of overwriting a newer policy.

Draft fields and their original comparison snapshot survive state restoration. Saving is transient so a lost coroutine cannot permanently lock the form. Persistence errors show a sanitized retry message and preserve the draft; the UI reports success only after the write completes. No network, analytics, exported component or new permission is introduced.

Focused checks cover defaults, malformed/boundary/empty-weekday input, device timezone changes, DataStore reopen and stale writes, DST recalculation from unchanged raw facts, explanation/save behavior, failed writes and restored in-flight forms. Exact results and head SHAs are recorded in the PR. Public fixtures are synthetic; no physical-phone acceptance is implied.
