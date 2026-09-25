package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A readable projection of effective sessions. Raw facts and correction audit remain below it. */
internal data class DayTimelineRow(val at: Instant, val title: String, val detail: String, val sessionId: String? = null)

/** Plain summary for the scannable chronology; exact transition names stay in evidence below. */
internal fun timelineReviewCue(reason: ReviewReason): String = when (reason) {
    ReviewReason.DUPLICATE_EVENT -> "The same observation was received more than once; it adds no extra time."
    ReviewReason.REPEATED_ENTER -> "More than one office-area arrival was recorded before a departure. Review which arrival began the visit."
    ReviewReason.TRANSIENT_BOUNDARY -> "A brief office-area entry and exit may be boundary jitter. Review if this was a real visit; its original observations remain available."
    ReviewReason.MISSING_ENTER -> "An observed departure has no recorded arrival, so its start time is unknown."
    ReviewReason.OPEN_SESSION -> "No departure has been observed yet."
    ReviewReason.STALE_OPEN_SESSION -> "This visit has been open unusually long and needs its end checked before credit can be trusted."
    ReviewReason.UNCONFIRMED_GAP -> "A later current-location check cannot establish when the earlier visit ended. The uncertain earlier span earns no credit."
    ReviewReason.INVALID_CORRECTION -> "A saved change to this visit could not be applied; review the retained record."
    ReviewReason.ORPHAN_CORRECTION -> "A saved change no longer matches a visit and needs review."
    ReviewReason.UNKNOWN_OFFICE -> "The observation names an office that is no longer in the saved setup."
    ReviewReason.FUTURE_EVENT -> "An observation is later than the device's current time; check the clock."
    ReviewReason.CONFLICTING_EVENT_ID -> "Two different observations share a record identifier and cannot establish this boundary."
    ReviewReason.INVALID_EVENT -> "An observation could not be used to establish this visit."
    ReviewReason.ZERO_LENGTH_SESSION -> "The recorded bounds leave no positive visit time."
    ReviewReason.INVALID_MANUAL_SESSION -> "A manually entered visit has invalid bounds and cannot be credited."
    ReviewReason.CONFLICTING_MANUAL_SESSION_ID -> "Two manual visits share a record identifier and need review."
}

internal fun dayTimeline(input: AttendanceInput, detail: DayExplanation): List<DayTimelineRow> {
    val dayStart = detail.date.atStartOfDay(input.policy.zoneId).toInstant()
    val dayEnd = detail.date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
    val officeNames = input.offices.associate { it.id to dashboardOfficeName(it.name) }
    val events = input.events.associateBy { it.id }
    val sessions = detail.sessions.filter { session ->
        val start = session.start
        val end = session.end
        if (start == null) end != null && end >= dayStart && end < dayEnd
        else start < dayEnd && (end ?: input.now) > dayStart
    }.sortedWith(compareBy<Session> { it.start ?: it.end }.thenBy { it.id })
    val rows = mutableListOf<DayTimelineRow>()
    var latestCoveredEnd: Instant? = null
    var latestCoverageUncertain = false
    val completedByOffice = mutableMapOf<String, Instant>()
    for (session in sessions) {
        val name = officeNames[session.officeId] ?: "unknown office"
        val start = session.start
        val end = session.end
        val coveredUntil = latestCoveredEnd
        if (coveredUntil != null && start != null && start > coveredUntil && coveredUntil < dayEnd) {
            val gapStart = maxOf(coveredUntil, dayStart)
            val gapEnd = minOf(start, dayEnd)
            if (gapEnd > gapStart) rows += DayTimelineRow(gapStart,
                if (latestCoverageUncertain) "Unconfirmed time between checks" else "No active recorded session",
                "Until ${instantText(gapEnd, input.policy.zoneId)} · ${minutesText(Duration.between(gapStart, gapEnd).toMinutes().toDouble())}. Short-gap credit, if applicable, is shown in credited intervals.")
        }
        val startEvent = session.sourceEventIds.mapNotNull(events::get).firstOrNull {
            it.at == start && it.transition in setOf(Transition.ENTER, Transition.PRESENCE)
        }
        if (start == null) {
            if (end != null && end >= dayStart && end < dayEnd)
                rows += DayTimelineRow(end, "Arrival time unknown at $name",
                    "An office-area end was observed without an opening boundary; no arrival was invented.", session.id)
        } else if (start >= dayStart && start < dayEnd) {
            val returning = completedByOffice[session.officeId]?.let { it <= start } == true
            val title = when {
                session.manualSessionId != null -> "Manual session at $name"
                session.correctionId != null && !session.correctionReverted -> "Corrected session at $name"
                startEvent?.transition == Transition.PRESENCE -> "Current presence in office area · $name"
                returning -> "Returned to office area · $name"
                else -> "Arrived in office area · $name"
            }
            val source = when (startEvent?.transition) {
                Transition.PRESENCE -> "Current-location presence was checked here; earlier arrival is unknown."
                Transition.ENTER -> "A crossing into the configured office area was recorded; this is not a building badge time."
                else -> if (session.manualSessionId != null || session.correctionId != null) "Entered or corrected by you; original observations remain below."
                    else "Opening boundary needs review."
            }
            rows += DayTimelineRow(start, title, source, session.id)
        } else if (start < dayStart) {
            rows += DayTimelineRow(dayStart, "Continued at $name from an earlier day",
                "This session began ${start.atZone(input.policy.zoneId).toLocalDate()}.", session.id)
        }
        if (end != null && end >= dayStart && end < dayEnd) {
            val endEvent = session.sourceEventIds.mapNotNull(events::get).firstOrNull {
                it.at == end && it.transition in setOf(Transition.EXIT, Transition.ABSENCE)
            }
            val uncertain = ReviewReason.UNCONFIRMED_GAP in session.reviewReasons
            val transient = ReviewReason.TRANSIENT_BOUNDARY in session.reviewReasons
            // The engine intentionally omits a PRESENCE split fact from the old
            // session's source IDs, because it opens the new visit. Match the
            // office and exact check time to describe the old boundary honestly.
            val presenceSplit = uncertain && endEvent?.transition != Transition.ABSENCE &&
                input.events.any { it.officeId == session.officeId && it.at == end && it.transition == Transition.PRESENCE }
            rows += DayTimelineRow(end,
                when {
                    uncertain && endEvent?.transition == Transition.ABSENCE -> "Outside $name at a later check"
                    presenceSplit -> "Current presence checked again at $name"
                    uncertain -> "Earlier visit ended at a later check · $name"
                    transient -> "Office boundary uncertain · $name"
                    session.manualSessionId != null -> "Manual session ended at $name"
                    session.correctionId != null && !session.correctionReverted -> "Corrected session ended at $name"
                    else -> "Left office area · $name"
                }, when {
                    uncertain && endEvent?.transition == Transition.ABSENCE ->
                        "The check confirms outside now, but the actual exit time is unknown. The earlier span earns no credit until reviewed."
                    presenceSplit -> "Current presence is confirmed at this check, not continuous presence before it. The earlier uncertain span earns no credit until reviewed."
                    uncertain -> "The earlier visit's end time is unknown. The uncertain span earns no credit until reviewed."
                    transient -> "A brief office-area entry and exit may be boundary jitter. This visit earns no credit unless corrected."
                    endEvent?.transition == Transition.EXIT -> "A crossing out of the configured office area was recorded; physical building exit may differ."
                    else -> "This boundary is manual or needs review; original observations remain below."
                }, session.id)
        } else if (end == null && detail.date == input.now.atZone(input.policy.zoneId).toLocalDate()) {
            rows += DayTimelineRow(input.now, "Ongoing at $name",
                "Started ${instantText(start ?: dayStart, input.policy.zoneId)}. Live credit is evaluated through the time shown above.", session.id)
        } else if (end == dayEnd) {
            rows += DayTimelineRow(dayEnd, "Session ended at policy midnight · $name",
                "This boundary ends the prior day's visit; it does not create a next-day session.", session.id)
        } else if (end != null && end > dayEnd) {
            rows += DayTimelineRow(dayEnd, "Continues into the next day at $name",
                "The session crosses this policy-local midnight.", session.id)
        }
        if (start != null) {
            val candidateEnd = end ?: input.now
            val currentCoveredEnd = latestCoveredEnd
            if (currentCoveredEnd == null || candidateEnd > currentCoveredEnd) {
                latestCoveredEnd = candidateEnd
                latestCoverageUncertain = ReviewReason.UNCONFIRMED_GAP in session.reviewReasons
            }
            if (end != null) completedByOffice[session.officeId] =
                maxOf(completedByOffice[session.officeId] ?: end, end)
        }
    }
    return rows.sortedWith(compareBy<DayTimelineRow> { it.at }.thenBy { it.title })
}
