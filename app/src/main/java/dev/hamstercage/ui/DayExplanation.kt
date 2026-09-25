package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate

data class DayExplanation(
    val date: LocalDate, val summary: PeriodSummary, val denominator: String,
    val rawEvents: List<RawEvent>, val originalSessions: List<Session>, val sessions: List<Session>,
    val manualSessions: List<ManualSession>, val corrections: List<Correction>,
    val intervals: List<CreditedInterval>, val reviews: List<ReviewItem>,
)

/** Projection of shared engine results; never recalculates credit independently or edits evidence. */
fun explainDay(input: AttendanceInput, result: AttendanceResult, date: LocalDate): DayExplanation {
    val start = date.atStartOfDay(input.policy.zoneId).toInstant()
    val end = date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
    fun onDay(at: Instant?) = at != null && at >= start && at < end
    fun touches(from: Instant?, until: Instant?) = onDay(from) || onDay(until) ||
        (from != null && from < end && (until ?: input.now) > start)
    val intervals = result.intervals.mapNotNull {
        val clippedStart = maxOf(it.start, start)
        val clippedEnd = minOf(it.end, end)
        if (clippedEnd > clippedStart) it.copy(start = clippedStart, end = clippedEnd) else null
    }
    val contributingSessions = intervals.flatMap { it.sessionIds }.toSet()
    val originals = AttendanceEngine.derive(input.copy(corrections = emptyList())).sessions
    val eventsOnDay = input.events.filter { onDay(it.at) }.map { it.id }.toSet()
    val manualOnDay = input.manualSessions.filter { touches(it.start, it.end) || onDay(it.createdAt) }.map { it.id }.toSet()
    val correctionsOnDay = input.corrections.filter { touches(it.start, it.end) || onDay(it.createdAt) }
    val related = (result.sessions + originals).filter { session ->
        session.id in contributingSessions || touches(session.start, session.end) || session.sourceEventIds.any { it in eventsOnDay } ||
            session.manualSessionId in manualOnDay || correctionsOnDay.any { it.sessionId in session.correctionTargetIds }
    }.flatMap { it.correctionTargetIds }.toSet()
    val sessions = result.sessions.filter { it.correctionTargetIds.any { id -> id in related } }
    val raw = input.events.filter { onDay(it.at) || sessions.any { session -> it.id in session.sourceEventIds } }
        .sortedWith(compareBy<RawEvent> { it.at }.thenBy { it.id }) // Keep every conflicting/duplicate copy.
    val manual = input.manualSessions.filter { it.id in manualOnDay || sessions.any { session -> session.manualSessionId == it.id } }
    val corrections = input.corrections.filter { it in correctionsOnDay || it.sessionId in related }
        .sortedWith(compareBy<Correction> { it.createdAt }.thenBy { it.id })
    val retainedTargets = related + manual.map { "manual:${it.id}" } + corrections.map { it.sessionId }
    val rawIds = raw.map { it.id }.toSet()
    val excluded = input.policy.excludedDates.filter { it.date == date }
    val denominator = when {
        excluded.isNotEmpty() -> "Excluded: ${excluded.map { it.reason.name.replace('_', ' ') }.distinct().joinToString()}. No required minutes; recorded office credit is retained."
        date.dayOfWeek !in input.policy.expectedWeekdays -> "This weekday is not expected. No required minutes; recorded office credit is retained."
        else -> "Expected weekday: 1 day × ${input.policy.targetMinutesPerDay} minutes."
    } + if (date in input.policy.wfhDates) " WFH is a label and does not reduce the requirement." else ""
    return DayExplanation(date, AttendanceEngine.daily(input, result, date), denominator, raw,
        originals.filter { it.correctionTargetIds.any { id -> id in related } }, sessions, manual, corrections, intervals,
        result.reviews.filter { it.sessionId in retainedTargets || it.sourceEventIds.any { id -> id in rawIds } })
}

fun reviewExplanation(reason: ReviewReason): String = when (reason) {
    ReviewReason.DUPLICATE_EVENT -> "Matching observations were reconciled; their source records remain and add no duplicate credit."
    ReviewReason.REPEATED_ENTER -> "Another ENTER arrived before an EXIT. The engine retained the earliest open boundary; review the session."
    ReviewReason.MISSING_ENTER -> "An EXIT has no observed ENTER. No start or attendance was invented."
    ReviewReason.OPEN_SESSION -> "No EXIT has been observed. Eligible live credit stops at the evaluation time."
    ReviewReason.STALE_OPEN_SESSION -> "The open session exceeded the configured review limit and is excluded from credit until resolved."
    ReviewReason.UNCONFIRMED_GAP -> "A later current-location check established the current state without an observed EXIT. The exit time is unknown; the earlier segment is uncredited until its uncertain bounds are reviewed."
    ReviewReason.INVALID_CORRECTION -> "An invalid or conflicting correction was ignored; the latest valid correction or original bounds remain."
    ReviewReason.ORPHAN_CORRECTION -> "This retained correction cannot currently be linked to a session."
    ReviewReason.UNKNOWN_OFFICE -> "The source refers to an unknown office and cannot establish credited attendance."
    ReviewReason.FUTURE_EVENT -> "The observation is after the evaluation time and is excluded from reconstruction."
    ReviewReason.CONFLICTING_EVENT_ID -> "Different observations share one ID. All copies are retained for review and excluded from reconstruction."
    ReviewReason.INVALID_EVENT -> "The malformed observation is retained for review and excluded from reconstruction."
    ReviewReason.ZERO_LENGTH_SESSION -> "The observed boundaries form no positive interval; no minutes were invented."
    ReviewReason.INVALID_MANUAL_SESSION -> "The manual interval is invalid and is excluded from reconstruction."
    ReviewReason.CONFLICTING_MANUAL_SESSION_ID -> "Conflicting manual entries share one ID and are excluded from reconstruction."
}

/** Display-only sanitation; stored notes and names are unchanged. No markup is interpreted. */
internal fun evidenceText(text: String) = text.filter { it == '\n' || (!it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()) }.take(2000)

/** A reversible visible escape for unusual characters keeps distinct source IDs distinct.
 * Long/corrupt IDs retain a full SHA-256 suffix; reserved display markers are always escaped. */
internal fun evidenceId(id: String): String {
    val escaped = buildString {
        id.codePoints().forEach { point ->
            if (point in 48..57 || point in 65..90 || point in 97..122 || point in listOf(45, 46, 58, 95)) appendCodePoint(point)
            else append("\\u{").append(point.toString(16).uppercase()).append('}')
        }
    }.ifEmpty { "\\u{}" }
    if (escaped.length <= 200) return escaped
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return escaped.take(110) + "…[sha256:$digest]"
}
