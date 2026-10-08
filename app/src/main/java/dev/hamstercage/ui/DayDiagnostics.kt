package dev.hamstercage.ui

import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.decisivePresenceSources
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.LocalDate

/** A read-only, redacted copy of source facts for one policy-local day. Never persist this in Room. */
data class DayDiagnostics(val json: String, val observationCount: Int, val contextCount: Int,
    val firstIncludedDay: LocalDate, val lastIncludedDay: LocalDate)

internal fun dayDiagnostics(snapshot: AppSnapshot, date: LocalDate, now: Instant,
    coverage: CoverageLedger?, capture: CaptureStatus, appVersion: String): DayDiagnostics {
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
    val officeIds = (evidence.map { it.event.officeId } + detail.manualSessions.map { it.officeId } +
        detail.sessions.map { it.officeId }).distinct().sorted()
    val officeAlias = officeIds.mapIndexed { index, id -> id to "office-${index + 1}" }.toMap()
    val factIds = evidence.map { it.event.id }.distinct().sorted()
    val factAlias = factIds.mapIndexed { index, id -> id to "fact-${index + 1}" }.toMap()
    val sessionIds = detail.sessions.map { it.id }.distinct().sorted()
    val sessionAlias = sessionIds.mapIndexed { index, id -> id to "session-${index + 1}" }.toMap()
    val targetIds = detail.corrections.map { it.sessionId }.distinct().sorted()
    val targetAlias = targetIds.mapIndexed { index, id -> id to "target-${index + 1}" }.toMap()
    val editIds = detail.corrections.map { it.id }.distinct().sorted()
    val editAlias = editIds.mapIndexed { index, id -> id to "edit-${index + 1}" }.toMap()
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
        detail.sessions.flatMap { listOfNotNull(it.start, it.end) }.map(::localDay) + date
    val policy = input.policy
    val payload = linkedMapOf<String, Any?>(
        "schemaVersion" to 1,
        "purpose" to "Private, user-initiated attendance engine diagnosis; not a backup",
        "appVersion" to appVersion,
        "selectedLocalDay" to date.toString(),
        "policyZone" to zone.id,
        "windowStartInclusive" to start.toString(),
        "windowEndExclusive" to end.toString(),
        "contextRule" to "Selected day, linked session boundary facts, at most one preceding fact per related office from the previous local day; for a late EXIT, the earliest same-office return and earliest cross-office presence within two hours after local midnight",
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
            "lastVerifiedAt" to capture.lastVerifiedAt?.toString(), "lastCheckResult" to capture.lastCheckResult,
            "historyStartDate" to coverage?.historyStartDate?.toString(),
            "selectedDayUnknown" to (date in coverage?.unreviewedUnknownDates.orEmpty()),
            "lastHealthyAt" to coverage?.lastHealthyAt?.toString(),
            "outageStartedAt" to coverage?.outageStartedAt?.toString(),
            "recoveryBoundaryAt" to coverage?.recoveryBoundaryAt?.toString(),
            "lastOutageStartedAt" to coverage?.lastOutageStartedAt?.toString(),
            "lastOutageHealthySince" to coverage?.lastOutageHealthySince?.toString(),
            "openOutageHealthySince" to coverage?.outageHealthySince?.toString(),
            "lastObservationAt" to coverage?.lastObservationAt?.toString()),
        "observations" to evidence.map { item -> linkedMapOf<String, Any?>(
            "fact" to factAlias[item.event.id], "office" to officeAlias[item.event.officeId],
            "transition" to item.event.transition.name, "eventAt" to item.event.at.toString(),
            "receivedAt" to item.receivedAt.toString(), "observedLocationAt" to item.observedLocationAt?.toString(),
            "source" to item.source, "accuracyMeters" to item.accuracyMeters?.takeIf { it.isFinite() },
            "context" to (item.event.at < start || item.event.at >= end),
            "engineRoles" to rolesOf(item.event.id)) },
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
        evidence.count { it.event.at < start || it.event.at >= end }, includedDays.min(), includedDays.max())
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
