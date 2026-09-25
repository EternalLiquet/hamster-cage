package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DayTimelineTest {
    private val office = Office("west", "Westerville Office", 0.0, 0.0)
    private val policy = Policy(zoneId = ZoneId.of("UTC"))
    private fun event(id: String, transition: Transition, at: String) = RawEvent(id, office.id, transition, Instant.parse(at))
    private fun rows(input: AttendanceInput, day: String = "2026-09-23"): List<DayTimelineRow> {
        val date = LocalDate.parse(day)
        return dayTimeline(input, explainDay(input, AttendanceEngine.derive(input), date))
    }

    @Test fun enteredLeftReturnedGapAndOngoingAreChronological() {
        val input = AttendanceInput(listOf(office), listOf(
            event("in1", Transition.ENTER, "2026-09-23T09:47:00Z"),
            event("out1", Transition.EXIT, "2026-09-23T11:00:00Z"),
            event("in2", Transition.ENTER, "2026-09-23T12:14:00Z")),
            policy = policy, now = Instant.parse("2026-09-23T14:00:00Z"))
        val timeline = rows(input)
        assertEquals(timeline.map { it.at }.sorted(), timeline.map { it.at })
        assertTrue(timeline.any { it.title == "Arrived in office area · Westerville Office" })
        assertTrue(timeline.any { it.title == "Left office area · Westerville Office" })
        assertTrue(timeline.any { it.title == "No active recorded session" })
        assertTrue(timeline.any { it.title == "Returned to office area · Westerville Office" })
        assertTrue(timeline.any { it.title == "Ongoing at Westerville Office" })
        assertEquals(2, timeline.count { it.title.startsWith("Arrived") || it.title.startsWith("Returned") })
    }

    @Test fun outsideCheckDoesNotInventExitOrCreditUnknownSpan() {
        val input = AttendanceInput(listOf(office), listOf(
            event("in", Transition.ENTER, "2026-09-23T09:00:00Z"),
            event("outside", Transition.ABSENCE, "2026-09-23T10:00:00Z")),
            policy = policy, now = Instant.parse("2026-09-23T11:00:00Z"))
        val result = AttendanceEngine.derive(input)
        val timeline = dayTimeline(input, explainDay(input, result, LocalDate.parse("2026-09-23")))
        assertTrue(timeline.any { it.title == "Outside Westerville Office at a later check" &&
            it.detail.contains("actual exit time is unknown") })
        assertFalse(timeline.any { it.title.startsWith("Left") })
        assertEquals(0.0, AttendanceEngine.daily(input, result, LocalDate.parse("2026-09-23")).creditedMinutes, 0.0)
    }

    @Test fun crossMidnightShowsContinuationWithoutSecondArrival() {
        val input = AttendanceInput(listOf(office), listOf(
            event("in", Transition.ENTER, "2026-09-23T23:30:00Z"),
            event("out", Transition.EXIT, "2026-09-24T00:30:00Z")),
            policy = policy, now = Instant.parse("2026-09-24T01:00:00Z"))
        assertTrue(rows(input, "2026-09-23").any { it.title.startsWith("Continues into the next day") })
        val next = rows(input, "2026-09-24")
        assertTrue(next.any { it.title.startsWith("Continued at") })
        assertFalse(next.any { it.title.startsWith("Arrived") })
        assertTrue(next.any { it.title.startsWith("Left") })
    }

    @Test fun emptyAndOrphanExitStayHonest() {
        val empty = AttendanceInput(listOf(office), emptyList(), policy = policy,
            now = Instant.parse("2026-09-23T12:00:00Z"))
        assertTrue(rows(empty).isEmpty())
        val orphan = empty.copy(events = listOf(event("out", Transition.EXIT, "2026-09-23T11:00:00Z")))
        assertTrue(rows(orphan).any { it.title == "Arrival time unknown at Westerville Office" })
    }

    @Test fun currentPresenceAndCorrectedMultiOfficeSessionKeepTheirProvenance() {
        val second = Office("east", "East office", 0.0, 0.0)
        val first = event("in", Transition.ENTER, "2026-09-23T09:00:00Z")
        val out = event("out", Transition.EXIT, "2026-09-23T10:00:00Z")
        val presence = RawEvent("presence", second.id, Transition.PRESENCE, Instant.parse("2026-09-23T11:00:00Z"))
        val base = AttendanceInput(listOf(office, second), listOf(first, out, presence), policy = policy,
            now = Instant.parse("2026-09-23T12:00:00Z"))
        val firstSession = AttendanceEngine.derive(base).sessions.first { it.officeId == office.id }
        val corrected = base.copy(corrections = listOf(Correction("correction", firstSession.id,
            Instant.parse("2026-09-23T09:10:00Z"), out.at, base.now)))
        val timeline = rows(corrected)
        assertTrue(timeline.any { it.title == "Corrected session at Westerville Office" && it.sessionId == firstSession.id })
        assertTrue(timeline.any { it.title == "Current presence in office area · East office" &&
            it.detail.contains("earlier arrival is unknown") })
        assertFalse(timeline.any { it.title == "Arrived in office area · East office" })
    }
}
