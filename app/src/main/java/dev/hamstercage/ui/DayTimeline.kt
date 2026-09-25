package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A readable projection of effective sessions. Raw facts and correction audit remain below it. */
internal data class DayTimelineRow(val at: Instant, val title: String, val detail: String, val sessionId: String? = null)

internal fun dayTimeline(input: AttendanceInput, detail: DayExplanation): List<DayTimelineRow> {
    val dayStart = detail.date.atStartOfDay(input.policy.zoneId).toInstant()
    val dayEnd = detail.date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
    val officeNames = input.offices.associate { it.id to dashboardOfficeName(it.name) }
    val events = input.events.associateBy { it.id }
    val sessions = detail.sessions.filter { session ->
        (session.start ?: session.end ?: return@filter false) < dayEnd &&
            (session.end ?: input.now) >= dayStart
    }.sortedWith(compareBy<Session> { it.start ?: it.end }.thenBy { it.id })
    val rows = mutableListOf<DayTimelineRow>()
    var prior: Session? = null
    for (session in sessions) {
        val name = officeNames[session.officeId] ?: "unknown office"
        val start = session.start
        val end = session.end
        val priorEnd = prior?.end
        if (priorEnd != null && start != null && start > priorEnd && priorEnd < dayEnd) {
            val gapStart = maxOf(priorEnd, dayStart)
            val gapEnd = minOf(start, dayEnd)
            if (gapEnd > gapStart) rows += DayTimelineRow(gapStart,
                if (ReviewReason.UNCONFIRMED_GAP in prior.reviewReasons) "Unconfirmed time between checks" else "No active recorded session",
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
            val returning = priorEnd != null && priorEnd <= start && prior?.officeId == session.officeId
            val title = when {
                session.manualSessionId != null -> "Manual session at $name"
                session.correctionId != null && !session.correctionReverted -> "Corrected session at $name"
                startEvent?.transition == Transition.PRESENCE -> "Current presence in office area · $name"
                returning -> "Returned to office area · $name"
                else -> "Arrived in office area · $name"
            }
            val source = when (startEvent?.transition) {
                Transition.PRESENCE -> "Current-location presence was checked here; earlier arrival is unknown."
                Transition.ENTER -> "Office-area ENTER was observed; this is not a building badge time."
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
            rows += DayTimelineRow(end,
                when {
                    uncertain -> "Outside $name at a later check"
                    session.manualSessionId != null -> "Manual session ended at $name"
                    session.correctionId != null && !session.correctionReverted -> "Corrected session ended at $name"
                    else -> "Left office area · $name"
                }, when {
                    uncertain -> "The actual exit time is unknown. The uncertain earlier span earns no credit until reviewed."
                    endEvent?.transition == Transition.EXIT -> "Office-area EXIT was observed; physical building exit may differ."
                    else -> "This boundary is manual or needs review; original observations remain below."
                }, session.id)
        } else if (end == null && detail.date == input.now.atZone(input.policy.zoneId).toLocalDate()) {
            rows += DayTimelineRow(input.now, "Ongoing at $name",
                "Started ${instantText(start ?: dayStart, input.policy.zoneId)}. Live credit is evaluated through the time shown above.", session.id)
        } else if (end != null && end >= dayEnd) {
            rows += DayTimelineRow(dayEnd, "Continues into the next day at $name",
                "The session crosses this policy-local midnight.", session.id)
        }
        prior = session
    }
    return rows.sortedWith(compareBy<DayTimelineRow> { it.at }.thenBy { it.title })
}
