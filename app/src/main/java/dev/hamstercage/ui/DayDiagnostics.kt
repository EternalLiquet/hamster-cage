package dev.hamstercage.ui

import dev.hamstercage.capture.BoundaryCheckCodec
import dev.hamstercage.capture.BoundaryCheckEntry
import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RegistrationAttempt
import dev.hamstercage.data.RegistrationCycle
import dev.hamstercage.data.RegistrationDiagnostics
import dev.hamstercage.data.sanitizedErrorCode
import dev.hamstercage.data.decisivePresenceSources
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.LocalDate

/** A read-only, redacted copy of source facts for one policy-local day. Never persist this in Room. */
data class DayDiagnostics(val json: String, val observationCount: Int, val contextCount: Int,
    val firstIncludedDay: LocalDate, val lastIncludedDay: LocalDate,
    val registrationCount: Int = 0, val registrationContextCount: Int = 0,
    val registrationCycleCount: Int = 0, val registrationCycleContextCount: Int = 0,
    val boundaryCheckCount: Int = 0)

/** How far before the selected day an office's most recent registration attempt may be included. */
internal const val REGISTRATION_LOOKBACK_DAYS = 7L

/**
 * [registrations] is null when registration diagnostics could not be read; the export then says
 * so instead of implying that no registration happened. [boundaryChecks] is null in the same way
 * when the boundary check log could not be read; only attempts for included facts are exported.
 */
internal fun dayDiagnostics(snapshot: AppSnapshot, date: LocalDate, now: Instant,
    coverage: CoverageLedger?, capture: CaptureStatus, appVersion: String,
    registrations: RegistrationDiagnostics? = null,
    boundaryChecks: List<BoundaryCheckEntry>? = null): DayDiagnostics {
    val input = snapshot.input(now).copy(historyStartDate = coverage?.historyStartDate,
        unknownDates = coverage?.unreviewedUnknownDates.orEmpty())
    val detail = explainDay(input, AttendanceEngine.derive(input), date)
    val zone = input.policy.zoneId
    val start = date.atStartOfDay(zone).toInstant()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant()
    val previousDay = date.minusDays(1).atStartOfDay(zone).toInstant()
    val relatedOfficeIds = detail.rawEvents.map { it.officeId }.toSet() +
        detail.manualSessions.map { it.officeId } + detail.sessions.map { it.officeId }
    val previous = snapshot.eventEvidence.filter { it.event.officeId in relatedOfficeIds &&
        it.event.at >= previousDay && it.event.at < start }
        .groupBy { it.event.officeId }.values.mapNotNull { candidates -> candidates.maxWithOrNull(
            compareBy<dev.hamstercage.data.RecordedEvent> { it.event.at }.thenBy { it.event.id }) }
    // Only a late EXIT can need after-midnight corroboration. Limit disclosure to the first
    // same-office return and first different-office presence within two hours of midnight.
    val lateExit = detail.rawEvents.filter { it.transition == Transition.EXIT &&
        it.at >= end.minusSeconds(2 * 60 * 60) && it.at < end }.maxWithOrNull(
        compareBy<dev.hamstercage.domain.RawEvent> { it.at }.thenBy { it.id })
    val next = if (lateExit == null) emptyList() else snapshot.eventEvidence.filter {
        val sameOffice = it.event.officeId == lateExit.officeId
        val relevant = if (sameOffice)
            (it.event.transition == Transition.ENTER && it.source == "PLAY_SERVICES_GEOFENCE") ||
                (it.event.transition == Transition.PRESENCE && it.source in decisivePresenceSources)
        else it.event.transition == Transition.PRESENCE && it.source in decisivePresenceSources
        it.event.at >= end && it.event.at < end.plusSeconds(2 * 60 * 60) &&
            it.event.at <= now && relevant
    }.groupBy { it.event.officeId == lateExit.officeId }.values.mapNotNull { candidates ->
        candidates.minWithOrNull(compareBy<dev.hamstercage.data.RecordedEvent> { it.event.at }
            .thenBy { it.event.id }) }
    val includedFacts = detail.rawEvents.toSet() + previous.map { it.event } + next.map { it.event }
    val evidence = snapshot.eventEvidence.filter { it.event in includedFacts }
        .sortedWith(compareBy<dev.hamstercage.data.RecordedEvent> { it.event.at }.thenBy { it.event.id })
    // Include the latest preceding attempt per office even when there were no callbacks.
    // Intervening cycles can invalidate an add without creating another attempt (REMOVE or
    // failure during removal), so preserve that bounded lifecycle context as well.
    val attemptsInWindow = registrations?.attempts.orEmpty().filter { it.startedAt >= start && it.startedAt < end }
    val dayOfficeIds = (evidence.map { it.event.officeId } + detail.manualSessions.map { it.officeId } +
        detail.sessions.map { it.officeId }).toSet()
    val lookbackStart = date.minusDays(REGISTRATION_LOOKBACK_DAYS).atStartOfDay(zone).toInstant()
    val precedingAttempts = registrations?.attempts.orEmpty()
        .filter { it.startedAt >= lookbackStart && it.startedAt < start }
        .groupBy { it.officeId }.values.mapNotNull { attempts ->
            attempts.maxWithOrNull(compareBy<RegistrationAttempt> { it.startedAt }.thenBy { it.id })
        }
    val includedAttempts = (attemptsInWindow + precedingAttempts).distinctBy { it.id }
        .sortedWith(compareBy<RegistrationAttempt> { it.startedAt }.thenBy { it.id })
    val cycleById = registrations?.cycles.orEmpty().associateBy { it.id }
    val latestPrecedingCycle = registrations?.cycles.orEmpty()
        .filter { it.startedAt >= lookbackStart && it.startedAt < start }
        .maxWithOrNull(compareBy<RegistrationCycle> { it.startedAt }.thenBy { it.id })
    val cycleContextStart = (precedingAttempts.map { cycleById[it.cycleId]?.startedAt ?: it.startedAt } +
        listOfNotNull(latestPrecedingCycle?.startedAt)).minOrNull()?.coerceAtLeast(lookbackStart) ?: start
    val includedCycles = (registrations?.cycles.orEmpty().filter {
        it.startedAt >= cycleContextStart && it.startedAt < end } +
        includedAttempts.mapNotNull { cycleById[it.cycleId] }).distinctBy { it.id }
        .sortedWith(compareBy<RegistrationCycle> { it.startedAt }.thenBy { it.id })
    val officeIds = (dayOfficeIds + includedAttempts.map { it.officeId }).distinct().sorted()
    val officeAlias = officeIds.mapIndexed { index, id -> id to "office-${index + 1}" }.toMap()
    val factIds = evidence.map { it.event.id }.distinct().sorted()
    val factAlias = factIds.mapIndexed { index, id -> id to "fact-${index + 1}" }.toMap()
    // A check belongs to one included observation; checks for other facts would disclose timing
    // outside the export, so they are left out.
    val includedChecks = boundaryChecks.orEmpty().filter { it.candidateEventId in factAlias }
        .sortedWith(compareBy<BoundaryCheckEntry> { it.startedAt }.thenBy { factAlias[it.candidateEventId] }
            .thenBy { it.attempt })
    val sessionIds = detail.sessions.map { it.id }.distinct().sorted()
    val sessionAlias = sessionIds.mapIndexed { index, id -> id to "session-${index + 1}" }.toMap()
    val targetIds = detail.corrections.map { it.sessionId }.distinct().sorted()
    val targetAlias = targetIds.mapIndexed { index, id -> id to "target-${index + 1}" }.toMap()
    val editIds = detail.corrections.map { it.id }.distinct().sorted()
    val editAlias = editIds.mapIndexed { index, id -> id to "edit-${index + 1}" }.toMap()
    val cycleAlias = includedCycles.mapIndexed { index, cycle -> cycle.id to "cycle-${index + 1}" }.toMap()
    val attemptAlias = includedAttempts.mapIndexed { index, attempt -> attempt.id to "reg-${index + 1}" }.toMap()
    val manualIds = detail.manualSessions.map { it.id }.distinct().sorted()
    val manualAlias = manualIds.mapIndexed { index, id -> id to "manual-${index + 1}" }.toMap()
    val manualNoteVariants = detail.manualSessions.groupBy { it.id }.mapValues { (_, items) ->
        items.map { it.note }.distinct() }
    val correctionNoteVariants = detail.corrections.groupBy { it.id }.mapValues { (_, items) ->
        items.map { it.note }.distinct() }
    // These classifications come from the full stored history, including evidence beyond the
    // export bounds, so they are recorded with each fact rather than left to a bounded replay.
    val engineRoles = linkedMapOf("adaptivePresence" to input.adaptivePresenceIds,
        "candidateExit" to input.candidateExitIds, "delayedExit" to input.delayedExitIds,
        "provisionalAbsence" to input.provisionalAbsenceIds, "provisionalPresence" to input.provisionalPresenceIds,
        "receiptTimed" to input.receiptTimedEventIds, "recoveryPresence" to input.recoveryPresenceIds,
        "rejectedExit" to input.rejectedExitIds, "unconfirmedExit" to input.unconfirmedExitIds,
        "unsafeRecoveryPresence" to input.unsafeRecoveryPresenceIds)
    fun rolesOf(id: String) = engineRoles.filterValues { id in it }.keys.toList()
    fun localDay(at: Instant) = at.atZone(zone).toLocalDate()
    val includedDays = evidence.flatMap { listOfNotNull(it.event.at, it.receivedAt, it.observedLocationAt) }.map(::localDay) +
        detail.manualSessions.flatMap { listOfNotNull(it.start, it.end, it.createdAt) }.map(::localDay) +
        detail.corrections.flatMap { listOfNotNull(it.start, it.end, it.createdAt) }.map(::localDay) +
        detail.sessions.flatMap { listOfNotNull(it.start, it.end) }.map(::localDay) +
        includedAttempts.flatMap { listOfNotNull(it.startedAt, it.completedAt) }.map(::localDay) +
        includedCycles.flatMap { listOfNotNull(it.startedAt, it.completedAt) }
            .filter { it >= lookbackStart }.map(::localDay) +
        includedChecks.flatMap { listOf(it.startedAt, it.finishedAt) }.map(::localDay) + date
    val firstIncludedDay = includedDays.min()
    val lastIncludedDay = includedDays.max()
    // Capture status describes the device now, not the selected day. A time outside the span
    // shown in the preview is reduced to its side of that span so the file discloses no
    // undisclosed attendance timing (for example, this morning's in-office check).
    val spanStart = firstIncludedDay.atStartOfDay(zone).toInstant()
    val spanEnd = lastIncludedDay.plusDays(1).atStartOfDay(zone).toInstant()
    fun withinSpan(at: Instant?): Any? = when {
        at == null -> null
        at < spanStart -> "beforeSpan"
        at >= spanEnd -> "afterSpan"
        else -> at.toString()
    }
    fun withinSpan(day: LocalDate?): Any? = when {
        day == null -> null
        day < firstIncludedDay -> "beforeSpan"
        day > lastIncludedDay -> "afterSpan"
        else -> day.toString()
    }
    val checkInSpan = capture.lastVerifiedAt?.let { it >= spanStart && it < spanEnd } == true
    val policy = input.policy
    fun cycleTime(at: Instant?): String? = when {
        at == null -> null
        at < lookbackStart -> "beforeLookback"
        else -> at.toString()
    }
    val payload = linkedMapOf<String, Any?>(
        "schemaVersion" to 2,
        "purpose" to "Private, user-initiated attendance engine diagnosis; not a backup",
        "appVersion" to appVersion,
        "selectedLocalDay" to date.toString(),
        "policyZone" to zone.id,
        "windowStartInclusive" to start.toString(),
        "windowEndExclusive" to end.toString(),
        "contextRule" to "Selected day, linked session boundary facts, at most one preceding fact per related office from the previous local day; for the latest EXIT in the final two hours, the earliest same-office return and earliest cross-office presence within two hours after local midnight. Registration attempts started on the selected day, their cycles, and for every office with retained attempts its latest attempt within $REGISTRATION_LOOKBACK_DAYS days before the selected day, including offices without callbacks. Includes subsequent cycles through the selected day and the latest preceding cycle within that lookback even if it has no attempts; parent cycle times before the lookback are coarsened. Capture times outside the evidence date span are written as beforeSpan or afterSpan.",
        "evaluatedAt" to now.toString(),
        "offices" to officeIds.map { id -> snapshot.offices.find { it.id == id }?.let { office ->
            linkedMapOf<String, Any?>("alias" to officeAlias[id], "radiusMeters" to office.radiusMeters,
                "enabled" to office.enabled, "countsTowardAttendance" to office.countsTowardAttendance,
                "entryGraceMinutes" to office.entryGraceMinutes, "exitGraceMinutes" to office.exitGraceMinutes)
        } ?: linkedMapOf("alias" to officeAlias[id], "missingFromCurrentOffices" to true) },
        "policy" to linkedMapOf("targetMinutesPerDay" to policy.targetMinutesPerDay,
            "expectedWeekdays" to policy.expectedWeekdays.map { it.name }.sorted(),
            "shortGapMinutes" to policy.shortGapMinutes, "maxOpenSessionHours" to policy.maxOpenSessionHours,
            "exclusion" to policy.excludedDates.filter { it.date == date }.map { it.reason.name },
            "wfh" to (date in policy.wfhDates)),
        "capture" to linkedMapOf("registration" to capture.registration.name,
            "monitoringEnabled" to capture.monitoringEnabled, "deliveryFailure" to capture.deliveryFailure,
            "lastVerifiedAt" to withinSpan(capture.lastVerifiedAt),
            "lastCheckResult" to capture.lastCheckResult?.takeIf { checkInSpan },
            "historyStartDate" to withinSpan(coverage?.historyStartDate),
            "selectedDayUnknown" to (date in coverage?.unreviewedUnknownDates.orEmpty()),
            "lastHealthyAt" to withinSpan(coverage?.lastHealthyAt),
            "outageStartedAt" to withinSpan(coverage?.outageStartedAt),
            "recoveryBoundaryAt" to withinSpan(coverage?.recoveryBoundaryAt),
            "lastOutageStartedAt" to withinSpan(coverage?.lastOutageStartedAt),
            "lastOutageHealthySince" to withinSpan(coverage?.lastOutageHealthySince),
            "openOutageHealthySince" to withinSpan(coverage?.outageHealthySince),
            "lastObservationAt" to withinSpan(coverage?.lastObservationAt)),
        "observations" to evidence.map { item -> linkedMapOf<String, Any?>(
            "fact" to factAlias[item.event.id], "office" to officeAlias[item.event.officeId],
            "transition" to item.event.transition.name, "eventAt" to item.event.at.toString(),
            "receivedAt" to item.receivedAt.toString(), "observedLocationAt" to item.observedLocationAt?.toString(),
            "source" to item.source, "accuracyMeters" to item.accuracyMeters?.takeIf { it.isFinite() },
            "fixAgeMs" to item.fix?.ageMs, "provider" to item.fix?.provider, "isMock" to item.fix?.mock,
            "context" to (item.event.at < start || item.event.at >= end),
            "engineRoles" to rolesOf(item.event.id)) },
        "registrationDiagnostics" to registrations?.let { retained -> linkedMapOf<String, Any?>(
            "retentionDays" to RegistrationDiagnostics.RETENTION_DAYS,
            "precedingLookbackDays" to REGISTRATION_LOOKBACK_DAYS,
            "attemptCount" to attemptsInWindow.size,
            "attemptContextCount" to precedingAttempts.size,
            "cycleCount" to includedCycles.count { it.startedAt >= start && it.startedAt < end },
            "cycleContextCount" to includedCycles.count { it.startedAt < start || it.startedAt >= end },
            // Absence of attempts before this point does not mean none happened.
            "oldestRetainedAttemptAt" to withinSpan(retained.attempts.minOfOrNull { it.startedAt }),
            "fixMetadataSources" to listOf("PLAY_SERVICES_GEOFENCE"),
            "note" to "Diagnostic only; registrations never affect attendance. Times are wall-clock and comparable with receivedAt; timing proximity between a registration and an observation does not establish cause.") },
        "registrationCycles" to includedCycles.map { cycle -> linkedMapOf<String, Any?>(
            "cycle" to cycleAlias[cycle.id], "kind" to cycle.kind.name, "reason" to cycle.reason.name,
            "generation" to cycle.generation, "plannedCount" to cycle.plannedCount,
            "startedAt" to cycleTime(cycle.startedAt), "completedAt" to cycleTime(cycle.completedAt),
            "outcome" to cycle.outcome.name, "removal" to cycle.removal?.name, "cleanup" to cycle.cleanup?.name,
            "errorCode" to sanitizedErrorCode(cycle.errorCode),
            "context" to (cycle.startedAt < start || cycle.startedAt >= end)) },
        "registrations" to includedAttempts.map { attempt -> linkedMapOf<String, Any?>(
            "registration" to attemptAlias[attempt.id], "cycle" to cycleAlias[attempt.cycleId],
            "office" to officeAlias[attempt.officeId], "officeVersion" to attempt.officeVersion,
            "initialTrigger" to attempt.initialTrigger,
            "startedAt" to attempt.startedAt.toString(), "completedAt" to attempt.completedAt?.toString(),
            "status" to attempt.status.name, "errorCode" to sanitizedErrorCode(attempt.errorCode),
            "context" to (attempt.startedAt < start || attempt.startedAt >= end)) },
        "boundaryCheckDiagnostics" to boundaryChecks?.let { retained -> linkedMapOf<String, Any?>(
            "retentionDays" to BoundaryCheckCodec.RETENTION.toDays(),
            "maxRetained" to BoundaryCheckCodec.MAX_ENTRIES,
            "attemptCount" to includedChecks.size,
            "oldestRetainedCheckAt" to withinSpan(retained.minOfOrNull { it.startedAt }),
            "note" to "Diagnostic only; checks never affect attendance except through the observations they record. One row per scheduled attempt that started. A missing attempt number means that attempt never started: it was delayed, replaced by a newer delivery, or cancelled.") },
        "boundaryChecks" to includedChecks.map { check -> linkedMapOf<String, Any?>(
            "fact" to factAlias[check.candidateEventId], "attempt" to check.attempt,
            "startedAt" to check.startedAt.toString(), "finishedAt" to check.finishedAt.toString(),
            "outcome" to check.outcome.name, "accuracyMeters" to check.accuracyMeters?.takeIf { it.isFinite() },
            "fixAgeMs" to check.fixAgeMs) },
        "manualSessions" to detail.manualSessions.map { item -> linkedMapOf<String, Any?>(
            "manual" to manualAlias[item.id], "office" to officeAlias[item.officeId],
            "start" to item.start.toString(), "end" to item.end?.toString(),
            "createdAt" to item.createdAt.toString(),
            "redactedNoteVariant" to (manualNoteVariants[item.id]!!.indexOf(item.note) + 1)) },
        "corrections" to detail.corrections.map { item -> linkedMapOf<String, Any?>(
            "edit" to editAlias[item.id], "target" to targetAlias[item.sessionId],
            "start" to item.start.toString(), "end" to item.end?.toString(),
            "createdAt" to item.createdAt.toString(), "revertToOriginal" to item.revertToOriginal,
            "appendSequence" to item.appendSequence,
            "redactedNoteVariant" to (correctionNoteVariants[item.id]!!.indexOf(item.note) + 1)) },
        "sessions" to detail.sessions.map { item -> linkedMapOf<String, Any?>(
            "session" to sessionAlias[item.id], "office" to officeAlias[item.officeId],
            "manual" to item.manualSessionId?.let { manualAlias[it] },
            "start" to item.start?.toString(), "end" to item.end?.toString(),
            "correctionTargets" to item.correctionTargetIds.mapNotNull { targetAlias[it] }.sorted(),
            "facts" to item.sourceEventIds.mapNotNull { factAlias[it] }.sorted(),
            "redundantFacts" to item.redundantEventIds.mapNotNull { factAlias[it] }.sorted(),
            "confidence" to item.confidence.name, "reviewReasons" to item.reviewReasons.map { it.name }.sorted()) },
        "creditedMinutes" to detail.summary.creditedMinutes,
        "requiredMinutes" to detail.summary.requiredMinutes,
        "historyComplete" to detail.summary.hasCompleteHistory,
    )
    return DayDiagnostics(jsonValue(payload), evidence.count { it.event.at >= start && it.event.at < end },
        evidence.count { it.event.at < start || it.event.at >= end }, firstIncludedDay, lastIncludedDay,
        attemptsInWindow.size, includedAttempts.size - attemptsInWindow.size,
        includedCycles.count { it.startedAt >= start && it.startedAt < end },
        includedCycles.count { it.startedAt < start || it.startedAt >= end }, includedChecks.size)
}

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Int, is Long -> value.toString()
    is Float -> if (value.isFinite()) value.toString() else "null"
    is Double -> if (value.isFinite()) value.toString() else "null"
    is String -> buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32 || c == '\u2028' || c == '\u2029') append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { jsonValue(it.key.toString()) + ":" + jsonValue(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { jsonValue(it) }
    else -> error("Unsupported diagnostic field")
}
