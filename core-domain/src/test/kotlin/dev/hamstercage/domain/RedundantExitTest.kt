package dev.hamstercage.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RedundantExitTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 5)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun at(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun event(id: String, transition: Transition, time: String, officeId: String = "a") =
        RawEvent(id, officeId, transition, at(time))
    private fun input(events: List<RawEvent>, now: String = "16:07", offices: List<Office> = listOf(office)) =
        AttendanceInput(offices, events, policy = Policy(zoneId = zone, shortGapMinutes = 0),
            now = at(now), historyStartDate = day)

    @Test fun observedWorkdayKeepsTwoVisitsAndTwoArrivalGraces() {
        val facts = listOf(event("in1", Transition.ENTER, "09:43"),
            event("out1", Transition.EXIT, "11:41"),
            event("out2", Transition.EXIT, "11:42"),
            event("in2", Transition.ENTER, "12:46"))
        val data = input(facts)
        val result = AttendanceEngine.derive(data)
        assertEquals(facts, data.events)
        assertEquals(result, AttendanceEngine.derive(data.copy(events = facts.reversed())))
        assertEquals(2, result.sessions.size)
        assertEquals(at("11:41"), result.sessions.first().end)
        assertEquals(setOf("in1", "out1", "out2"), result.sessions.first().sourceEventIds)
        assertEquals(at("12:46"), result.sessions.last().start)
        assertTrue(result.sessions.last().isOpen)
        assertTrue(result.reviews.none { it.reason == ReviewReason.MISSING_ENTER ||
            it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertEquals(319.0, result.sessions.sumOf { AttendanceEngine.observedMinutes(it, data.now) }, 0.0001)
        assertEquals(309.0, result.intervals.sumOf { it.minutes }, 0.0001)
    }

    @Test fun latestUnconfirmedDuplicateKeepsOneUnresolvedBoundary() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00"),
            event("duplicate", Transition.EXIT, "11:01"))
        val pending = AttendanceEngine.derive(input(facts, now = "11:05")
            .copy(unconfirmedExitIds = setOf("duplicate")))
        assertEquals(1, pending.sessions.size)
        assertEquals(at("11:00"), pending.sessions.single().end)
        assertEquals(setOf("in", "out", "duplicate"), pending.sessions.single().sourceEventIds)
        assertEquals(1, pending.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertTrue(pending.reviews.none { it.reason == ReviewReason.MISSING_ENTER })
        val settled = AttendanceEngine.derive(input(facts + listOf(
            event("outside", Transition.ABSENCE, "11:03"),
            event("later-exit", Transition.EXIT, "11:04")), now = "11:05")
            .copy(unconfirmedExitIds = setOf("later-exit")))
        assertTrue(settled.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun sameTimestampExitReplayStillNeedsCorroboration() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00"),
            event("duplicate", Transition.EXIT, "11:00"))
        val result = AttendanceEngine.derive(input(facts, now = "11:05")
            .copy(unconfirmedExitIds = setOf("duplicate")))
        assertEquals(1, result.sessions.size)
        assertEquals(1, result.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertTrue(result.reviews.none { it.reason == ReviewReason.MISSING_ENTER })
    }

    @Test fun exitAfterDecisiveOutsideCheckDoesNotCreateAnOrphan() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("outside", Transition.ABSENCE, "11:00"),
            event("redundant", Transition.EXIT, "11:01"))
        val result = AttendanceEngine.derive(input(facts, now = "11:05")
            .copy(unconfirmedExitIds = setOf("redundant")))
        assertEquals(1, result.sessions.size)
        assertEquals(at("11:00"), result.sessions.single().end)
        assertEquals(setOf("in", "outside", "redundant"), result.sessions.single().sourceEventIds)
        assertTrue(result.reviews.none { it.reason == ReviewReason.MISSING_ENTER ||
            it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun decisiveOtherOfficeFixKeepsPriorExitSettled() {
        val b = office.copy(id = "b", name = "Synthetic second office", latitude = 1.0)
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00"),
            event("other-fix", Transition.PRESENCE, "11:03", "b"),
            event("redundant", Transition.EXIT, "11:04"))
        val data = input(facts, now = "11:05", offices = listOf(office, b))
            .copy(unconfirmedExitIds = setOf("redundant"), recoveryPresenceIds = setOf("other-fix"))
        val result = AttendanceEngine.derive(data)
        assertEquals(1, result.sessions.count { it.officeId == "a" })
        assertTrue(result.reviews.none { it.reason == ReviewReason.MISSING_ENTER ||
            it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        val uncorroborated = AttendanceEngine.derive(data.copy(recoveryPresenceIds = emptySet()))
        assertEquals(1, uncorroborated.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun repeatedExitsRemainCorroboratingAcrossSpacingAndSourceReplays() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00"),
            event("out-seconds", Transition.EXIT, "11:00:10"),
            event("out-minutes", Transition.EXIT, "11:08"),
            event("back", Transition.ENTER, "12:00"),
            event("inside", Transition.PRESENCE, "12:03"),
            event("again", Transition.ENTER, "12:04"))
        val data = input(facts + facts[2] + facts[3], now = "13:00")
        val result = AttendanceEngine.derive(data)
        assertEquals(result, AttendanceEngine.derive(data.copy(events = data.events.reversed())))
        assertEquals(2, result.sessions.size)
        assertEquals(at("11:00"), result.sessions.first().end)
        assertEquals(facts.take(4).map { it.id }.toSet(), result.sessions.first().sourceEventIds)
        assertEquals(at("12:00"), result.sessions.last().start)
        assertTrue(result.reviews.isEmpty())
        assertEquals(170.0, result.intervals.sumOf { it.minutes }, 0.0001)
    }

    @Test fun duplicateExitClusterAndCrossOfficeOrphanRemainDistinct() {
        val b = office.copy(id = "b", name = "Synthetic second office", latitude = 1.0)
        val facts = listOf(event("in", Transition.ENTER, "10:32"),
            event("out", Transition.EXIT, "10:38"),
            event("duplicate", Transition.EXIT, "10:38:01"),
            event("other", Transition.EXIT, "10:38:02", "b"),
            event("back", Transition.ENTER, "10:41"))
        val result = AttendanceEngine.derive(input(facts, now = "11:00", offices = listOf(office, b)))
        assertEquals(2, result.sessions.count { it.officeId == "a" })
        assertEquals(at("10:38"), result.sessions.first { it.officeId == "a" }.end)
        assertEquals(1, result.sessions.count { it.officeId == "b" && it.start == null })
        assertTrue(result.reviews.any { it.reason == ReviewReason.MISSING_ENTER })
        assertTrue(result.reviews.none { it.sourceEventIds.contains("duplicate") &&
            it.reason == ReviewReason.MISSING_ENTER })
    }
}
