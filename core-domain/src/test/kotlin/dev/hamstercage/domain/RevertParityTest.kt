package dev.hamstercage.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/**
 * #136: a correction followed by its revert marker must derive exactly what the untouched
 * observations derive at the same instant, apart from the retained audit metadata.
 * All data is synthetic.
 */
class RevertParityTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 25)
    private val a = Office("a", "Synthetic office A", 0.0, 0.0)
    private val b = Office("b", "Synthetic office B", 0.0, 0.0)
    private fun at(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun event(id: String, transition: Transition, time: String, officeId: String = "a") =
        RawEvent(id, officeId, transition, at(time))
    private fun input(now: String, vararg events: RawEvent) = AttendanceInput(listOf(a, b), events.toList(),
        policy = Policy(targetMinutesPerDay = 120), now = at(now), historyStartDate = day.minusDays(30))

    private fun derive(input: AttendanceInput) = AttendanceEngine.derive(input)
    private fun credit(input: AttendanceInput) = AttendanceEngine.daily(input, derive(input), day).creditedMinutes
    private fun today(input: AttendanceInput) = AttendanceEngine.departure(input, derive(input), TargetWindow.TODAY)

    /** Edit [sessionId] (an open override from its start), then append a revert marker. */
    private fun editThenRevert(sessionId: String, start: String, sequence: Long = 1, prefix: String = "") = listOf(
        Correction("${prefix}edit", sessionId, at(start), null, at("08:00"), appendSequence = sequence),
        Correction("${prefix}undo", sessionId, at(start), null, at("08:01"), revertToOriginal = true, appendSequence = sequence + 1))

    /** Edit and revert every visit the untouched reconstruction produced. */
    private fun revertEveryVisit(baseline: AttendanceInput): AttendanceInput {
        val corrections = derive(baseline).sessions.withIndex().flatMap { (index, session) ->
            val start = (session.start ?: session.end!!).atZone(zone).toLocalTime().toString()
            editThenRevert(session.id, start, sequence = 2L * index + 1, prefix = "${session.id}-")
        }
        return baseline.copy(corrections = corrections)
    }

    private fun assertParity(baseline: AttendanceInput, reverted: AttendanceInput) {
        val expected = derive(baseline)
        val actual = derive(reverted)
        // The audit trail stays visible on the restored session.
        assertTrue(actual.sessions.any { it.correctionReverted && it.correctionId != null })
        assertEquals(expected.intervals, actual.intervals)
        assertEquals(expected.reviews, actual.reviews)
        assertEquals(expected.sessions, actual.sessions.map { it.copy(correctionId = null, correctionReverted = false) })
        assertEquals(AttendanceEngine.daily(baseline, expected, day), AttendanceEngine.daily(reverted, actual, day))
        for (window in TargetWindow.entries)
            assertEquals(window.name, AttendanceEngine.departure(baseline, expected, window),
                AttendanceEngine.departure(reverted, actual, window))
        // Replay of the same facts at the same instant is deterministic.
        assertEquals(actual, derive(reverted))
    }

    /** The issue's synthetic example: rejected A EXIT, then presence at B. */
    private fun rejectedExitThenOtherOffice() = input("12:00",
        event("a-in", Transition.ENTER, "09:00"), event("a-out", Transition.EXIT, "10:00"),
        event("b-in", Transition.ENTER, "10:30", "b"), event("b-out", Transition.EXIT, "11:00", "b"))
        .copy(rejectedExitIds = setOf("a-out"))

    @Test fun rejectedExitFollowedByAnotherOfficeKeepsItsCapAndReviewAfterRevert() {
        val baseline = rejectedExitThenOtherOffice()
        // A has no inside observation after its ENTER, so its credit stops before entry grace ends.
        // Only B's 10:35-11:00 counts, and the contradiction needs review.
        assertEquals(25.0, credit(baseline), 0.0)
        assertEquals(DepartureStatus.NEEDS_REVIEW, today(baseline).status)
        assertTrue(ReviewReason.UNCONFIRMED_BOUNDARY in today(baseline).reviewReasons)

        val reverted = baseline.copy(corrections = editThenRevert("session:a-in", "09:00"))
        assertEquals(25.0, credit(reverted), 0.0)
        val estimate = today(reverted)
        assertNotEquals(DepartureStatus.TARGET_SATISFIED, estimate.status)
        assertEquals(DepartureStatus.NEEDS_REVIEW, estimate.status)
        assertTrue(ReviewReason.UNCONFIRMED_BOUNDARY in estimate.reviewReasons)
        assertParity(baseline, reverted)
        assertParity(baseline, revertEveryVisit(baseline))
    }

    @Test fun pendingCandidateExitKeepsItsCreditCapAfterRevert() {
        val baseline = input("11:03", event("in", Transition.ENTER, "09:00"), event("maybe", Transition.EXIT, "11:00"))
            .copy(candidateExitIds = setOf("maybe"), unconfirmedExitIds = setOf("maybe"))
        assertTrue(derive(baseline).sessions.single().isOpen)
        // Credit stops at the candidate while it is checked: 09:05-11:00.
        assertEquals(115.0, credit(baseline), 0.0)
        assertEquals(DepartureStatus.ESTIMATED, today(baseline).status)
        assertEquals(setOf(EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED), today(baseline).assumptions)

        val reverted = baseline.copy(corrections = editThenRevert("session:in", "09:00"))
        assertEquals(115.0, credit(reverted), 0.0)
        assertParity(baseline, reverted)
        // Past the target, the capped total still cannot claim the goal while the check is pending.
        val later = baseline.copy(now = at("11:10"), policy = Policy(targetMinutesPerDay = 116))
        assertParity(later, later.copy(corrections = editThenRevert("session:in", "09:00")))
        assertEquals(115.0, credit(later.copy(corrections = editThenRevert("session:in", "09:00"))), 0.0)
    }

    @Test fun unconfirmedGapKeepsOnlyItsObservedPrefixAfterRevert() {
        val baseline = input("13:00", event("in", Transition.ENTER, "09:00"), event("seen", Transition.PRESENCE, "10:00"),
            event("out", Transition.ABSENCE, "11:00"), event("back", Transition.ENTER, "12:00"))
        val gapVisit = derive(baseline).sessions.first { ReviewReason.UNCONFIRMED_GAP in it.reviewReasons }
        assertTrue(derive(baseline).intervals.any { gapVisit.id in it.sessionIds })
        assertEquals(DepartureStatus.NEEDS_REVIEW, today(baseline).status)

        val reverted = baseline.copy(corrections = editThenRevert(gapVisit.id, "09:00"))
        assertEquals(credit(baseline), credit(reverted), 0.0)
        assertParity(baseline, reverted)
        assertParity(baseline, revertEveryVisit(baseline))
    }

    @Test fun repeatedEditsAndRevertsRestoreTheBaselineEachTime() {
        val baseline = rejectedExitThenOtherOffice()
        val twice = editThenRevert("session:a-in", "09:00", sequence = 1, prefix = "first-") +
            editThenRevert("session:a-in", "09:30", sequence = 3, prefix = "second-")
        val reverted = baseline.copy(corrections = twice)
        assertEquals("second-undo", derive(reverted).sessions.single { it.id == "session:a-in" }.correctionId)
        assertParity(baseline, reverted)
        // Input order does not matter; durable append order decides.
        assertParity(baseline, baseline.copy(corrections = twice.reversed()))
    }

    @Test fun genuinelyActiveCorrectionsStillOverrideTheObservationCaps() {
        val baseline = rejectedExitThenOtherOffice()
        // A later edit after a revert is active again: the user's stated open visit is MANUAL
        // and the observation caps do not apply to it.
        val reEdited = baseline.copy(corrections = editThenRevert("session:a-in", "09:00") +
            Correction("again", "session:a-in", at("09:00"), null, at("08:02"), appendSequence = 3))
        val session = derive(reEdited).sessions.single { it.id == "session:a-in" }
        assertEquals(Confidence.MANUAL, session.confidence)
        assertEquals("again", session.correctionId)
        assertFalse(session.correctionReverted)
        assertEquals(175.0, credit(reEdited), 0.0)
        // It behaves exactly like the same edit applied once.
        val once = baseline.copy(corrections = listOf(Correction("again", "session:a-in", at("09:00"), null, at("08:02"))))
        assertEquals(derive(once).intervals, derive(reEdited).intervals)
        assertEquals(today(once), today(reEdited))

        // An active edit of a visit with a pending candidate likewise keeps its stated bounds.
        val pending = input("11:03", event("in", Transition.ENTER, "09:00"), event("maybe", Transition.EXIT, "11:00"))
            .copy(candidateExitIds = setOf("maybe"))
        val confirmed = pending.copy(corrections = listOf(Correction("stay", "session:in", at("09:00"), null, at("11:01"))))
        assertEquals(118.0, credit(confirmed), 0.0)
        assertEquals(Confidence.MANUAL, derive(confirmed).sessions.single().confidence)
    }

    @Test fun qualifiedClosedLunchEstimateSurvivesCorrectionAndRevert() {
        // #134: an unconfirmed lunch EXIT qualifies Today's estimate instead of blocking it.
        val baseline = input("15:00", event("in", Transition.ENTER, "09:00"), event("lunch", Transition.EXIT, "12:00"),
            event("back", Transition.ENTER, "13:00"))
            .copy(policy = Policy(), unconfirmedExitIds = setOf("lunch"), delayedExitIds = setOf("lunch"))
        assertEquals(DepartureStatus.ESTIMATED, today(baseline).status)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), today(baseline).assumptions)
        assertParity(baseline, baseline.copy(corrections = editThenRevert("session:in", "09:00")))
        assertParity(baseline, revertEveryVisit(baseline))
        // With a pending candidate on the return visit, both qualifiers survive a revert of either visit.
        val pending = baseline.copy(now = at("14:53"), events = baseline.events + event("maybe", Transition.EXIT, "14:50"),
            candidateExitIds = setOf("maybe"), unconfirmedExitIds = setOf("lunch", "maybe"))
        assertEquals(setOf(EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED, EstimateAssumption.EARLIER_EXIT_UNCONFIRMED),
            today(pending).assumptions)
        assertParity(pending, pending.copy(corrections = editThenRevert("session:back", "13:00")))
        assertParity(pending, revertEveryVisit(pending))
    }

    @Test fun entryGraceStaysOncePerGenuineArrivalAfterRevert() {
        // Duplicate ENTERs mid-visit do not restart the walk-in delay; a revert must not either.
        val baseline = input("12:00", event("in", Transition.ENTER, "09:00"), event("dup", Transition.ENTER, "09:00"),
            event("again", Transition.ENTER, "10:00"), event("maybe", Transition.EXIT, "11:30"))
            .copy(candidateExitIds = setOf("maybe"))
        assertEquals(145.0, credit(baseline), 0.0)
        assertParity(baseline, revertEveryVisit(baseline))
        // A genuine second arrival at the other office gets its own grace, before and after revert.
        val twoVisits = input("12:00", event("in", Transition.ENTER, "09:00"), event("out", Transition.EXIT, "10:00"),
            event("b-in", Transition.ENTER, "10:30", "b"))
        assertEquals(55.0 + 85.0, credit(twoVisits), 0.0)
        assertParity(twoVisits, revertEveryVisit(twoVisits))
        // Exit grace remains projection-only: only the projected leave time moves.
        val projecting = twoVisits.copy(policy = Policy(targetMinutesPerDay = 200))
        val estimate = today(projecting.copy(corrections = editThenRevert("session:b-in", "10:30")))
        assertEquals(credit(projecting), credit(projecting.copy(corrections = editThenRevert("session:b-in", "10:30"))), 0.0)
        assertEquals(estimate.creditedTargetAt!!.minusSeconds(b.exitGraceMinutes * 60L), estimate.estimatedExitAt)
        assertParity(projecting, projecting.copy(corrections = editThenRevert("session:b-in", "10:30")))
    }
}
