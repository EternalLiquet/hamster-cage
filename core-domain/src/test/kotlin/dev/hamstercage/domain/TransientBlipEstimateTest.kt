package dev.hamstercage.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** A closed, uncredited boundary bounce before the current visit qualifies Today's estimate. */
class TransientBlipEstimateTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 25)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private val other = Office("b", "Second synthetic office", 1.0, 1.0)
    private fun at(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun event(id: String, transition: Transition, time: String, officeId: String = "a") =
        RawEvent(id, officeId, transition, at(time))

    /** Leaving for lunch with a 30-second bounce at the boundary, then returning. */
    private val day1 = listOf(event("in", Transition.ENTER, "09:00"), event("lunch", Transition.EXIT, "12:00"),
        event("bounceIn", Transition.ENTER, "12:01:30"), event("bounceOut", Transition.EXIT, "12:02:00"),
        event("back", Transition.ENTER, "13:00"))
    private fun input(events: List<RawEvent> = day1, now: String = "15:00", unconfirmed: Set<String> = emptySet()) =
        AttendanceInput(listOf(office, other), events, now = at(now), historyStartDate = day.minusDays(30),
            unconfirmedExitIds = unconfirmed)
    private fun estimate(input: AttendanceInput, target: TargetWindow = TargetWindow.TODAY) =
        AttendanceEngine.departure(input, AttendanceEngine.derive(input), target)
    private fun credit(input: AttendanceInput) =
        AttendanceEngine.daily(input, AttendanceEngine.derive(input), day).creditedMinutes

    @Test fun lunchBounceNoLongerHidesTheLeaveTime() {
        val input = input()
        val result = AttendanceEngine.derive(input)
        val bounce = result.sessions.single { it.start == at("12:01:30") }
        assertTrue(ReviewReason.TRANSIENT_BOUNDARY in bounce.reviewReasons)
        assertTrue(result.intervals.none { bounce.id in it.sessionIds })
        // The bounce stays reviewable and adds no credit: 09:05-12:00 plus 13:05-15:00.
        assertEquals(175.0 + 115.0, credit(input), 0.0)
        val today = estimate(input)
        assertEquals(DepartureStatus.ESTIMATED, today.status)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), today.assumptions)
        assertTrue(bounce.id in today.nonBlockingSessionIds)
        assertEquals(at("16:05"), today.estimatedExitAt)
    }

    @Test fun bounceAlongsideAnUnconfirmedLunchExitStillQualifies() {
        // The engine keeps the bounce TRANSIENT_BOUNDARY only; the lunch visit carries the unconfirmed EXIT.
        val today = estimate(input(unconfirmed = setOf("bounceOut", "lunch")))
        assertEquals(DepartureStatus.ESTIMATED, today.status)
        assertEquals(at("16:05"), today.estimatedExitAt)
    }

    @Test fun bounceAsTheLatestBoundaryStillNeedsReview() {
        // Nothing has superseded it, so whether the user is inside now is still in doubt.
        assertEquals(DepartureStatus.NEEDS_REVIEW, estimate(input(day1.dropLast(1))).status)
    }

    @Test fun afterTheDaysLastVisitEndsTheEarlierBounceStaysQualified() {
        val input = input(day1 + event("home", Transition.EXIT, "17:00"), now = "17:30")
        val bounce = AttendanceEngine.derive(input).sessions.single { it.start == at("12:01:30") }
        val today = estimate(input)
        assertEquals(DepartureStatus.TARGET_SATISFIED, today.status)
        assertTrue(bounce.id in today.nonBlockingSessionIds)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), today.assumptions)
    }

    @Test fun onlyAManualVisitAfterTheBounceDoesNotSupersedeIt() {
        val events = day1.dropLast(1)
        val manual = ManualSession("m", office.id, at("13:00"), at("14:00"), at("14:30"))
        val today = estimate(input(events, now = "14:30").copy(manualSessions = listOf(manual)))
        assertEquals(DepartureStatus.NEEDS_REVIEW, today.status)
        assertTrue(ReviewReason.TRANSIENT_BOUNDARY in today.reviewReasons)
    }

    @Test fun bounceAtAnotherOfficeDuringTheCurrentVisitStillNeedsReview() {
        val events = listOf(event("in", Transition.ENTER, "09:00"),
            event("bIn", Transition.ENTER, "10:00:00", "b"), event("bOut", Transition.EXIT, "10:00:30", "b"))
        val today = estimate(input(events))
        assertEquals(DepartureStatus.NEEDS_REVIEW, today.status)
        assertTrue(ReviewReason.TRANSIENT_BOUNDARY in today.reviewReasons)
    }

    @Test fun bounceWithAnotherProblemStillNeedsReview() {
        val orphan = event("orphan", Transition.EXIT, "14:00", "b")
        val today = estimate(input(day1 + orphan))
        assertEquals(DepartureStatus.NEEDS_REVIEW, today.status)
    }

    @Test fun correctedBounceIsJudgedByItsCorrectedBounds() {
        val input = input()
        val bounce = AttendanceEngine.derive(input).sessions.single { it.start == at("12:01:30") }
        val edit = Correction("c", bounce.id, at("12:01"), at("12:30"), at("14:00"))
        val corrected = input.copy(corrections = listOf(edit))
        val today = estimate(corrected)
        // The edit is an explicit MANUAL change: it earns credit and is not a set-aside bounce.
        assertFalse(bounce.id in today.nonBlockingSessionIds)
        assertTrue(credit(corrected) > credit(input))
        assertEquals(DepartureStatus.ESTIMATED, today.status)
    }

    @Test fun rollingTargetsKeepTheirReview() {
        assertEquals(DepartureStatus.NEEDS_REVIEW, estimate(input(), TargetWindow.WEEK_TO_DATE).status)
    }
}
