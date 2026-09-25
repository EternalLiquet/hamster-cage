package dev.hamstercage.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BoundaryStabilizationTest {
    private val zone = ZoneId.of("America/New_York")
    private val date = LocalDate.of(2026, 9, 25)
    private val a = Office("a", "Synthetic A", 0.0, 0.0)
    private val b = Office("b", "Synthetic B", 1.0, 1.0)
    private fun at(time: String): Instant = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun event(id: String, type: Transition, time: String, office: String = "a") =
        RawEvent(id, office, type, at(time))
    private fun input(events: List<RawEvent>, now: String = "13:00", offices: List<Office> = listOf(a),
        corrections: List<Correction> = emptyList(), recovery: Set<String> = emptySet()) =
        AttendanceInput(offices, events, corrections, Policy(zoneId = zone, shortGapMinutes = 10), at(now),
            historyStartDate = date, recoveryPresenceIds = recovery)

    @Test fun realStationarySequenceKeepsPromptBouncesWithOriginalOpening() {
        val facts = listOf(
            event("e0939", Transition.ENTER, "09:39"),
            event("e0943", Transition.ENTER, "09:43"),
            event("e1008", Transition.ENTER, "10:08"),
            event("x110129", Transition.EXIT, "11:01:29"),
            event("e110141", Transition.ENTER, "11:01:41"),
            event("x120612", Transition.EXIT, "12:06:12"),
            event("e120627", Transition.ENTER, "12:06:27"),
            event("x120652", Transition.EXIT, "12:06:52"),
            event("e124305", Transition.ENTER, "12:43:05"),
            event("x124530", Transition.EXIT, "12:45:30"),
        )
        val original = facts.toList()
        val data = input(facts)
        val result = AttendanceEngine.derive(data)
        assertEquals(original, facts)
        assertEquals(result, AttendanceEngine.derive(data.copy(events = facts.reversed())))
        assertEquals(2, result.sessions.size)
        assertEquals(at("09:39"), result.sessions[0].start)
        assertEquals(at("12:06:52"), result.sessions[0].end)
        assertEquals(facts.take(8).map { it.id }.toSet(), result.sessions[0].sourceEventIds)
        assertEquals(at("12:43:05"), result.sessions[1].start)
        assertEquals(at("12:45:30"), result.sessions[1].end)
        assertEquals(facts.takeLast(2).map { it.id }.toSet(), result.sessions[1].sourceEventIds)
        assertTrue(result.reviews.none { it.reason == ReviewReason.REPEATED_ENTER })
        assertEquals(listOf(at("09:44") to at("12:06:52")), result.intervals.map { it.start to it.end })
        assertTrue(result.intervals.zipWithNext().all { (left, right) -> left.end <= right.start })
    }

    @Test fun bounceDoesNotRestartGraceAndGenuineLongLeaveRemainsSeparate() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("out-jitter", Transition.EXIT, "11:00:00"),
            event("in-jitter", Transition.ENTER, "11:00:59"),
            event("out-real", Transition.EXIT, "12:00"),
            event("in-return", Transition.ENTER, "12:37"),
            event("out-end", Transition.EXIT, "15:00"))
        val result = AttendanceEngine.derive(input(facts, now = "16:00"))
        assertEquals(2, result.sessions.size)
        assertEquals(at("09:05"), result.intervals.first().start)
        assertEquals(at("12:00"), result.intervals.first().end)
        assertEquals(at("12:42"), result.intervals.last().start)
        assertTrue(result.intervals.none { it.start < at("12:37") && it.end > at("12:00") })
        assertTrue(result.reviews.isEmpty())
    }

    @Test fun briefEntryExitIsOneUncertainUncreditedVisitUntilCorroborated() {
        val facts = listOf(event("in", Transition.ENTER, "09:00:00"),
            event("out", Transition.EXIT, "09:00:40"))
        val result = AttendanceEngine.derive(input(facts))
        assertEquals(1, result.sessions.size)
        assertEquals(setOf(ReviewReason.TRANSIENT_BOUNDARY), result.sessions.single().reviewReasons)
        assertEquals(1, result.reviews.count { it.reason == ReviewReason.TRANSIENT_BOUNDARY })
        assertTrue(result.intervals.isEmpty())
        assertEquals(facts.map { it.id }.toSet(), result.sessions.single().sourceEventIds)
    }

    @Test fun repeatedPresenceCorroboratesButExplicitRecoveryFixSplitsUnknownOldTime() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("periodic", Transition.PRESENCE, "09:30"),
            event("periodic2", Transition.PRESENCE, "10:00"),
            event("again", Transition.ENTER, "10:01"))
        val routine = AttendanceEngine.derive(input(facts, now = "10:30"))
        assertEquals(1, routine.sessions.size)
        assertEquals(at("09:00"), routine.sessions.single().start)
        assertTrue(routine.sessions.single().isOpen)
        assertEquals(at("09:05"), routine.intervals.single().start)
        assertTrue(routine.reviews.none { it.reason == ReviewReason.REPEATED_ENTER })

        val recovered = AttendanceEngine.derive(input(facts, now = "10:30", recovery = setOf("periodic")))
        assertEquals(2, recovered.sessions.size)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in recovered.sessions.first().reviewReasons)
        assertEquals(at("09:30"), recovered.sessions.last().start)
        assertEquals(at("09:35"), recovered.intervals.single().start)
    }

    @Test fun bounceRetainsCorrectionAliasAndMultiOfficeUnion() {
        val facts = listOf(event("a-in", Transition.ENTER, "09:00"),
            event("a-out-jitter", Transition.EXIT, "10:00"),
            event("a-in-jitter", Transition.ENTER, "10:00:10"),
            event("a-out", Transition.EXIT, "12:00"),
            event("b-in", Transition.ENTER, "10:30", "b"),
            event("b-out", Transition.EXIT, "11:30", "b"))
        val correction = Correction("c", "session:a-in-jitter", at("09:15"), at("11:45"), at("12:30"))
        val data = input(facts, offices = listOf(a, b), corrections = listOf(correction))
        val result = AttendanceEngine.derive(data)
        assertEquals(2, result.sessions.size)
        assertEquals("c", result.sessions.single { it.officeId == "a" }.correctionId)
        assertTrue(result.reviews.none { it.reason == ReviewReason.ORPHAN_CORRECTION })
        assertEquals(145.0, result.intervals.sumOf { it.minutes }, 0.0001)
        assertEquals(facts, data.events)
    }

    @Test fun fallBackClockChangeDoesNotTurnTwentyFiveSecondBounceIntoLongDeparture() {
        val start = Instant.parse("2026-11-01T05:30:00Z")
        val out = Instant.parse("2026-11-01T05:59:45Z")
        val back = Instant.parse("2026-11-01T06:00:10Z")
        val end = Instant.parse("2026-11-01T06:30:00Z")
        val facts = listOf(RawEvent("in", "a", Transition.ENTER, start),
            RawEvent("out-jitter", "a", Transition.EXIT, out),
            RawEvent("in-jitter", "a", Transition.ENTER, back),
            RawEvent("out", "a", Transition.EXIT, end))
        val result = AttendanceEngine.derive(AttendanceInput(listOf(a), facts,
            policy = Policy(zoneId = zone), now = end))
        assertEquals(1, result.sessions.size)
        assertEquals(start, result.sessions.single().start)
        assertEquals(end, result.sessions.single().end)
        assertEquals(55.0, result.intervals.single().minutes, 0.0001)
    }

    @Test fun explicitRecoveryPresenceAfterExitDoesNotBridgeOldVisit() {
        val facts = listOf(event("old", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00:00"),
            event("fix", Transition.PRESENCE, "11:00:30"))
        val result = AttendanceEngine.derive(input(facts, now = "11:10", recovery = setOf("fix")))
        assertEquals(2, result.sessions.size)
        assertEquals(at("11:00:00"), result.sessions.first().end)
        assertEquals(at("11:00:30"), result.sessions.last().start)
        assertEquals(at("11:05:30"), result.intervals.last().start)
        assertTrue(result.intervals.none { it.start < at("11:00:30") && it.end > at("11:00:00") })
    }

    @Test fun otherOfficeEvidencePreventsSameMinuteBounceAcrossOffices() {
        val facts = listOf(event("a-in", Transition.ENTER, "09:00"),
            event("a-out", Transition.EXIT, "11:00:00"),
            event("b-in", Transition.ENTER, "11:00:10", "b"),
            event("b-out", Transition.EXIT, "11:00:20", "b"),
            event("a-back", Transition.ENTER, "11:00:30"))
        val result = AttendanceEngine.derive(input(facts, now = "11:10", offices = listOf(a, b)))
        assertEquals(2, result.sessions.count { it.officeId == "a" })
        assertEquals(at("11:00:00"), result.sessions.first { it.id == "session:a-in" }.end)
        assertEquals(at("11:00:30"), result.sessions.first { it.id == "session:a-back" }.start)
        assertTrue(result.intervals.none { it.start < at("11:00:30") && it.end > at("11:00:00") })
    }
}
