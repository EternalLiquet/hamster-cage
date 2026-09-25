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

    @Test fun overlappingOfficesUseFurthestEndForGapAndEarlierSameOfficeForReturn() {
        val second = Office("east", "East office", 0.0, 0.0)
        fun time(hour: Int) = Instant.parse("2026-09-23T${hour.toString().padStart(2, '0')}:00:00Z")
        val sessions = listOf(
            Session("a1", office.id, time(9), time(12), emptySet(), Confidence.HIGH),
            Session("b", second.id, time(10), time(11), emptySet(), Confidence.HIGH),
            Session("a2", office.id, time(13), time(14), emptySet(), Confidence.HIGH))
        val input = AttendanceInput(listOf(office, second), emptyList(), policy = policy, now = time(15))
        val detail = explainDay(input, AttendanceResult(sessions, emptyList(), emptyList()), LocalDate.parse("2026-09-23"))
        val timeline = dayTimeline(input, detail)
        val gaps = timeline.filter { it.title == "No active recorded session" }
        assertEquals(1, gaps.size)
        assertEquals(time(12), gaps.single().at)
        assertTrue(gaps.single().detail.contains("Until 1:00 PM"))
        assertTrue(timeline.any { it.title == "Returned to office area · Westerville Office" && it.at == time(13) })

        val open = sessions.first().copy(end = null)
        val withOpen = explainDay(input, AttendanceResult(listOf(open, sessions[1], sessions[2]), emptyList(), emptyList()),
            LocalDate.parse("2026-09-23"))
        assertFalse(dayTimeline(input, withOpen).any { it.title == "No active recorded session" })
    }

    @Test fun orphanExitDoesNotMakeFirstLaterEntryAReturn() {
        val input = AttendanceInput(listOf(office), emptyList(), policy = policy,
            now = Instant.parse("2026-09-23T13:00:00Z"))
        val orphan = Session("orphan", office.id, null, Instant.parse("2026-09-23T10:00:00Z"),
            emptySet(), Confidence.LOW, setOf(ReviewReason.MISSING_ENTER))
        val first = Session("first", office.id, Instant.parse("2026-09-23T11:00:00Z"),
            Instant.parse("2026-09-23T12:00:00Z"), emptySet(), Confidence.HIGH)
        val detail = explainDay(input, AttendanceResult(listOf(orphan, first), emptyList(), emptyList()),
            LocalDate.parse("2026-09-23"))
        assertTrue(dayTimeline(input, detail).any { it.title == "Arrived in office area · Westerville Office" })
        assertFalse(dayTimeline(input, detail).any { it.title == "Returned to office area · Westerville Office" })
    }

    @Test fun exitExactlyAtPolicyMidnightDoesNotCreateNextDaySession() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-09-23")
        val entered = date.atTime(23, 30).atZone(zone).toInstant()
        val exited = date.plusDays(1).atStartOfDay(zone).toInstant()
        val input = AttendanceInput(listOf(office), listOf(
            RawEvent("in", office.id, Transition.ENTER, entered),
            RawEvent("out", office.id, Transition.EXIT, exited)),
            policy = Policy(zoneId = zone), now = exited.plusSeconds(3600))
        val prior = rows(input, date.toString())
        assertTrue(prior.any { it.title.startsWith("Session ended at policy midnight") })
        assertFalse(prior.any { it.title.startsWith("Continues into") })
        assertTrue(rows(input, date.plusDays(1).toString()).isEmpty())
    }
}
