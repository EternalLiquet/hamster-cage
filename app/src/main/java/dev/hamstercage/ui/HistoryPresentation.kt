package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.LocalDate

internal const val HISTORY_PAGE_DAYS = 14

enum class HistoryCoverage { COVERED, BEFORE_TRACKING, UNKNOWN_AFTER_TRACKING }

data class HistoryDay(val date: LocalDate, val summary: PeriodSummary, val observedMinutes: Double,
                      val badges: List<String>, val coverage: HistoryCoverage)

/** A source finding remains actionable even when no reliable tracking start can be established. */
fun historyNeedsReview(days: List<HistoryDay>): List<HistoryDay> = days.filter {
    "REVIEW" in it.badges || it.coverage == HistoryCoverage.UNKNOWN_AFTER_TRACKING
}

/** Only empty pretracking dates are collapsed; retained problem facts never disappear. */
fun historyVisibleDays(days: List<HistoryDay>, browseBeforeTracking: Boolean): List<HistoryDay> =
    if (browseBeforeTracking) days else days.filter {
        it.coverage != HistoryCoverage.BEFORE_TRACKING || "REVIEW" in it.badges
    }

/** Bounded presentation only. Totals and policy-local date clipping belong to the shared engine. */
fun historyDays(input: AttendanceInput, result: AttendanceResult, offsetDays: Int = 0): List<HistoryDay> {
    require(offsetDays >= 0)
    val last = input.now.atZone(input.policy.zoneId).toLocalDate().minusDays(offsetDays.toLong())
    val eventsById = input.events.groupBy { it.id }
    val dates = (0 until HISTORY_PAGE_DAYS).map { last.minusDays(it.toLong()) }
    val observed = AttendanceEngine.observedDailyMinutes(input, dates)
    val coverage = AttendanceEngine.reportingCoverage(input, result, dates.last(), dates.first())
    return dates.map { date ->
        val start = date.atStartOfDay(input.policy.zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
        fun onDay(at: java.time.Instant?) = at != null && at >= start && at < end
        val sessions = result.sessions.filter { session ->
            val effectiveStart = session.start
            (effectiveStart != null && effectiveStart < end && (session.end ?: input.now) > start) ||
                session.sourceEventIds.any { id -> eventsById[id].orEmpty().any { onDay(it.at) } } ||
                onDay(session.start) || onDay(session.end)
        }
        val summary = AttendanceEngine.daily(input, result, date)
        // Rejected source facts may produce no Session at all. Retain their review trail on
        // every supplied boundary/entry date, including every conflicting copy.
        fun retainedSourceOnDay(sessionId: String?) = sessionId != null && (
            input.manualSessions.any { "manual:${it.id}" == sessionId && (onDay(it.start) || onDay(it.end) || onDay(it.createdAt)) } ||
                input.corrections.any { it.sessionId == sessionId && (onDay(it.start) || onDay(it.end) || onDay(it.createdAt)) })
        val review = result.reviews.any { item ->
            item.reason != ReviewReason.DUPLICATE_EVENT && (
                sessions.any { item.sessionId in it.correctionTargetIds } ||
                    item.sourceEventIds.any { id -> eventsById[id].orEmpty().any { onDay(it.at) } } || retainedSourceOnDay(item.sessionId))
        }
        val state = when (date) {
            in coverage.unavailableBeforeTracking -> HistoryCoverage.BEFORE_TRACKING
            in coverage.unknownAfterTracking -> HistoryCoverage.UNKNOWN_AFTER_TRACKING
            else -> HistoryCoverage.COVERED
        }
        val badges = buildList {
            input.policy.excludedDates.find { it.date == date }?.let {
                add(if (it.reason == ExclusionReason.BANK_HOLIDAY) "HOLIDAY" else "EXCLUDED")
            }
            if (date in input.policy.wfhDates) add("WFH")
            if (review) add("REVIEW")
            if (sessions.any { it.manualSessionId != null || it.correctionId != null }) add("MANUAL")
            when (state) {
                HistoryCoverage.BEFORE_TRACKING -> add("BEFORE TRACKING")
                HistoryCoverage.UNKNOWN_AFTER_TRACKING -> add("UNKNOWN COVERAGE")
                HistoryCoverage.COVERED -> Unit
            }
        }
        HistoryDay(date, summary, observed.getValue(date), badges, state)
    }
}

/** Keep at least the latest page, and allow browsing back to every retained source or calendar date. */
fun earliestHistoryDate(input: AttendanceInput): LocalDate {
    val zone = input.policy.zoneId
    val dates = sequence {
        yield(input.now.atZone(zone).toLocalDate().minusDays((HISTORY_PAGE_DAYS - 1).toLong()))
        input.historyStartDate?.let { yield(it) }
        input.events.forEach { yield(it.at.atZone(zone).toLocalDate()) }
        input.manualSessions.forEach {
            yield(it.start.atZone(zone).toLocalDate())
            it.end?.let { end -> yield(end.atZone(zone).toLocalDate()) }
            yield(it.createdAt.atZone(zone).toLocalDate())
        }
        input.corrections.forEach {
            yield(it.start.atZone(zone).toLocalDate())
            it.end?.let { end -> yield(end.atZone(zone).toLocalDate()) }
            yield(it.createdAt.atZone(zone).toLocalDate())
        }
        yieldAll(input.unknownDates)
        input.policy.excludedDates.forEach { yield(it.date) }
        yieldAll(input.policy.wfhDates)
    }
    return dates.min()
}
