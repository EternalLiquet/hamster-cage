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
            event("out-minutes", Transition.EXIT, "11:04"),
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
        assertTrue(result.reviews.all { it.reason in setOf(ReviewReason.OPEN_SESSION,
            ReviewReason.DUPLICATE_EVENT) })
        assertEquals(170.0, result.intervals.sumOf { it.minutes }, 0.0001)
        // Eight minutes after the last outside observation, an unobserved return could
        // have earned credit under five-minute grace: the EXIT stays a reviewable orphan.
        val beyond = AttendanceEngine.derive(input(facts.map {
            if (it.id == "out-minutes") it.copy(at = at("11:08")) else it }, now = "13:00"))
        assertEquals(3, beyond.sessions.size)
        assertTrue(beyond.reviews.any { it.reason == ReviewReason.MISSING_ENTER &&
            "out-minutes" in it.sourceEventIds })
        assertEquals(170.0, beyond.intervals.sumOf { it.minutes }, 0.0001)
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

    // A later same-office EXIT is redundant only when an unobserved return between the
    // last outside observation and that EXIT could not have earned credit: it would
    // have to fit inside the office's arrival grace or the 60-second transient window.

    @Test fun hoursLaterExitAfterMissedReturnKeepsMissingArrivalReview() {
        val facts = listOf(event("in", Transition.ENTER, "09:43"),
            event("out", Transition.EXIT, "11:41"),
            event("late-out", Transition.EXIT, "17:00"))
        for (unconfirmed in listOf(emptySet(), setOf("late-out"))) {
            val result = AttendanceEngine.derive(input(facts, now = "17:05")
                .copy(unconfirmedExitIds = unconfirmed))
            val morning = result.sessions.single { it.start != null }
            assertEquals(at("11:41"), morning.end)
            assertEquals(setOf("in", "out"), morning.sourceEventIds)
            assertTrue(ReviewReason.UNCONFIRMED_BOUNDARY !in morning.reviewReasons)
            val orphan = result.sessions.single { it.start == null }
            assertEquals(at("17:00"), orphan.end)
            assertTrue(ReviewReason.MISSING_ENTER in orphan.reviewReasons)
            assertTrue(result.reviews.any { it.reason == ReviewReason.MISSING_ENTER &&
                "late-out" in it.sourceEventIds })
            assertEquals(113.0, result.intervals.sumOf { it.minutes }, 0.0001)
        }
        // A later decisive outside check settles the departure, not the missing arrival.
        val settled = AttendanceEngine.derive(input(facts + event("outside", Transition.ABSENCE, "17:02"),
            now = "17:05"))
        assertTrue(settled.reviews.any { it.reason == ReviewReason.MISSING_ENTER &&
            "late-out" in it.sourceEventIds })
    }

    @Test fun nextDayExitDoesNotAttachToPriorDayVisit() {
        val nextDay = day.plusDays(1)
        fun next(time: String) = nextDay.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "17:00"),
            RawEvent("morning-out", "a", Transition.EXIT, next("09:18")))
        val data = AttendanceInput(listOf(office), facts, policy = Policy(zoneId = zone, shortGapMinutes = 0),
            now = next("09:20"), historyStartDate = day, unconfirmedExitIds = setOf("morning-out"))
        val result = AttendanceEngine.derive(data)
        val prior = result.sessions.single { it.start != null }
        assertEquals(setOf("in", "out"), prior.sourceEventIds)
        assertEquals(Confidence.HIGH, prior.confidence)
        assertTrue(prior.reviewReasons.isEmpty())
        val orphan = result.sessions.single { it.start == null }
        assertEquals(next("09:18"), orphan.end)
        assertTrue(ReviewReason.MISSING_ENTER in orphan.reviewReasons)
        assertTrue(result.reviews.none { it.sessionId == prior.id })
    }

    @Test fun redundancyWindowFollowsArrivalGraceAndTransientFloor() {
        fun derive(grace: Int, second: String) = AttendanceEngine.derive(AttendanceInput(
            listOf(office.copy(entryGraceMinutes = grace)),
            listOf(event("in", Transition.ENTER, "09:00"), event("out", Transition.EXIT, "11:00"),
                event("again", Transition.EXIT, second)),
            policy = Policy(zoneId = zone, shortGapMinutes = 0), now = at("13:00"), historyStartDate = day))
        fun redundant(result: AttendanceResult) = result.sessions.size == 1 &&
            "again" in result.sessions.single().sourceEventIds &&
            result.reviews.none { it.reason == ReviewReason.MISSING_ENTER }
        // A missed return inside the window would earn zero credit, so nothing is hidden.
        assertTrue(redundant(derive(5, "11:05")))
        assertTrue(redundant(derive(15, "11:12")))
        assertTrue(redundant(derive(0, "11:00:59")))
        assertTrue(redundant(derive(0, "11:01")))
        // Beyond it, a missed return could have earned credit; keep it reviewable.
        assertFalse(redundant(derive(5, "11:05:01")))
        assertFalse(redundant(derive(15, "11:15:01")))
        assertFalse(redundant(derive(0, "11:01:01")))
    }

    @Test fun windowIsMeasuredFromLatestOutsideObservation() {
        // Each redundant EXIT or outside check is itself outside evidence at its own
        // observation time; delivery order does not matter because inputs are sorted.
        val chained = AttendanceEngine.derive(input(listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00"), event("out-2", Transition.EXIT, "11:04"),
            event("check", Transition.ABSENCE, "11:08"), event("out-3", Transition.EXIT, "11:12")),
            now = "12:00"))
        assertEquals(1, chained.sessions.size)
        assertTrue(chained.reviews.none { it.reason == ReviewReason.MISSING_ENTER })
        // An inside observation ends the outside run: a later EXIT belongs to that visit.
        val returned = AttendanceEngine.derive(input(listOf(event("in", Transition.ENTER, "09:00"),
            event("out", Transition.EXIT, "11:00"), event("back", Transition.ENTER, "11:02"),
            event("out-2", Transition.EXIT, "11:04")), now = "12:00"))
        assertEquals(2, returned.sessions.size)
        assertEquals(at("11:04"), returned.sessions.last().end)
    }
}
