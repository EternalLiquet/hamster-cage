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
    // A blank correction end (or a superseded or revert entry) reaches only as far as the
    // effective visit it targets, so only a visit that is still open reaches later days.
    fun correctionEnd(correction: Correction) = correction.end ?: result.sessions.firstOrNull {
        correction.sessionId in it.correctionTargetIds }?.end
    val correctionsOnDay = input.corrections.filter { touches(it.start, correctionEnd(it)) || onDay(it.createdAt) }
    val related = (result.sessions + originals).filter { session ->
        session.id in contributingSessions || touches(session.start, session.end) ||
            (session.sourceEventIds + session.redundantEventIds).any { it in eventsOnDay } ||
            session.manualSessionId in manualOnDay || correctionsOnDay.any { it.sessionId in session.correctionTargetIds }
    }.flatMap { it.correctionTargetIds }.toSet()
    val sessions = result.sessions.filter { it.correctionTargetIds.any { id -> id in related } }
    val raw = input.events.filter { onDay(it.at) || sessions.any { session ->
        it.id in session.sourceEventIds || it.id in session.redundantEventIds } }
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
    ReviewReason.DUPLICATE_EVENT -> "Matching records were combined. The copies are kept and add no duplicate credit."
    ReviewReason.REPEATED_ENTER -> "A second arrival was recorded before any exit. The earliest arrival is used; check this visit."
    ReviewReason.TRANSIENT_BOUNDARY -> "An office-area exit came within one minute of a new arrival. This brief visit is uncertain and earns no credit unless you correct its times. The original records are kept."
    ReviewReason.MISSING_ENTER -> "An exit was recorded with no arrival or confirmed presence before it. This exit alone does not show a visit, so no start or attendance was added."
    ReviewReason.OPEN_SESSION -> "No exit has been recorded yet. Credit runs only up to now."
    ReviewReason.STALE_OPEN_SESSION -> "This visit has been open longer than your review limit. It earns no credit until you resolve it."
    ReviewReason.UNCONFIRMED_GAP -> "The visit's end is uncertain. Credit stops at the last confirmed inside check, if any. Later time is uncredited until reviewed."
    ReviewReason.UNCONFIRMED_BOUNDARY -> "An office-area exit has not been confirmed by a location check, so it is still uncertain."
    ReviewReason.INVALID_CORRECTION -> "A correction was invalid or conflicted with another and was ignored. The latest valid correction, or the recorded times, still apply."
    ReviewReason.ORPHAN_CORRECTION -> "This saved correction cannot be linked to a visit right now."
    ReviewReason.UNKNOWN_OFFICE -> "This record points to an office that is not saved, so it cannot earn credit."
    ReviewReason.FUTURE_EVENT -> "This record is dated after now and is not used."
    ReviewReason.CONFLICTING_EVENT_ID -> "Different records share one ID. All copies are kept for review and none are used."
    ReviewReason.INVALID_EVENT -> "This record could not be read. It is kept for review and not used."
    ReviewReason.ZERO_LENGTH_SESSION -> "The recorded start and end leave no time between them; no minutes were added."
    ReviewReason.INVALID_MANUAL_SESSION -> "This manual entry is invalid and is not used."
    ReviewReason.CONFLICTING_MANUAL_SESSION_ID -> "Manual entries that share one ID conflict, so they are not used."
    ReviewReason.REPEATED_EXIT -> "Another exit from the same office was recorded soon after this visit ended. It is kept as a record and adds no arrival or departure. A return that short could not earn credit, but you may have come back right away and stayed until the later exit, so credit ends at the first exit and the short gap after it is not bridged. Correct the visit if you returned."
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
