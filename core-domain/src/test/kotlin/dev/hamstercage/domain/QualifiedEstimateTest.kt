package dev.hamstercage.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** #133: routine boundary doubt qualifies Today's estimate instead of demanding an unchanged review save. */
class QualifiedEstimateTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 25)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private val other = Office("b", "Second synthetic office", 0.0, 0.0, entryGraceMinutes = 25, exitGraceMinutes = 15)
    private fun at(time: String, date: LocalDate = day) = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun event(id: String, transition: Transition, time: String, officeId: String = "a", date: LocalDate = day) =
        RawEvent(id, officeId, transition, at(time, date))
    private fun enter(id: String, time: String, officeId: String = "a", date: LocalDate = day) =
        event(id, Transition.ENTER, time, officeId, date)
    private fun exit(id: String, time: String, officeId: String = "a", date: LocalDate = day) =
        event(id, Transition.EXIT, time, officeId, date)

    /** Lunch EXIT whose verification window expired undecided: the repository marks it uncertain. */
    private fun lunch(now: String = "15:00", events: List<RawEvent> = emptyList()) = AttendanceInput(
        listOf(office, other), listOf(enter("in", "09:00"), exit("lunch", "12:00"), enter("back", "13:00")) + events,
        now = at(now), historyStartDate = day.minusDays(30),
        unconfirmedExitIds = setOf("lunch"), delayedExitIds = setOf("lunch"))
    private fun derive(input: AttendanceInput) = AttendanceEngine.derive(input)
    private fun today(input: AttendanceInput) = AttendanceEngine.departure(input, derive(input), TargetWindow.TODAY)
    private fun credit(input: AttendanceInput, date: LocalDate = day) = AttendanceEngine.daily(input, derive(input), date).creditedMinutes

    @Test fun preciseUnconfirmedLunchExitQualifiesTheEstimateWithoutConfirmingIt() {
        val input = lunch()
        val result = derive(input)
        val lunchVisit = result.sessions.single { it.id == "session:in" }
        // The boundary stays unconfirmed and reviewable; nothing is marked manual or corrected.
        assertEquals(Confidence.LOW, lunchVisit.confidence)
        assertTrue(ReviewReason.UNCONFIRMED_BOUNDARY in lunchVisit.reviewReasons)
        assertTrue(result.reviews.any { it.sessionId == lunchVisit.id && it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertTrue(result.sessions.none { it.confidence == Confidence.MANUAL || it.correctionId != null })
        assertTrue(input.corrections.isEmpty())
        // Entry grace applies once per genuine arrival: 09:05-12:00 plus 13:05-15:00.
        assertEquals(175.0 + 115.0, credit(input), 0.0)
        val estimate = today(input)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), estimate.assumptions)
        assertEquals(setOf("session:in"), estimate.nonBlockingSessionIds)
        assertEquals(at("16:10"), estimate.creditedTargetAt)
        assertEquals(at("16:05"), estimate.estimatedExitAt)
    }

    @Test fun receiptTimedUnconfirmedExitStillNeedsReview() {
        // Its stored time may be later than the real departure, so credit could be overstated.
        val estimate = today(lunch().copy(receiptTimedEventIds = setOf("lunch")))
        assertEquals(DepartureStatus.NEEDS_REVIEW, estimate.status)
        assertNull(estimate.estimatedExitAt)
        assertTrue(estimate.assumptions.isEmpty())
    }

    @Test fun pendingCandidateExitProjectsOnlyOnTheStatedAssumption() {
        val input = lunch("14:53", listOf(exit("maybe", "14:50"))).let {
            it.copy(candidateExitIds = setOf("maybe"), unconfirmedExitIds = it.unconfirmedExitIds + "maybe")
        }
        val result = derive(input)
        assertTrue(result.sessions.single { it.id == "session:back" }.isOpen)
        // Credit stops at the candidate while it is checked: 175 + 13:05-14:50.
        assertEquals(175.0 + 105.0, credit(input), 0.0)
        val estimate = today(input)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        assertEquals(setOf(EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED, EstimateAssumption.EARLIER_EXIT_UNCONFIRMED),
            estimate.assumptions)
        assertEquals(at("16:13"), estimate.creditedTargetAt)
        assertEquals(at("16:08"), estimate.estimatedExitAt)
        // Another office's arrival contradicts the visit; that is not a routine pending check.
        val elsewhere = input.copy(events = input.events + enter("far", "14:52", "b"))
        assertNotEquals(DepartureStatus.ESTIMATED, today(elsewhere).status)
    }

    @Test fun candidateOutcomesResolveWithoutManualCorrection() {
        val candidate = lunch("14:53", listOf(exit("maybe", "14:50")))
        // Quick return: the rejected candidate neither ends the visit nor restarts entry grace.
        val bounced = candidate.copy(events = candidate.events + enter("bounce", "14:50:30"), rejectedExitIds = setOf("maybe"))
        assertEquals(175.0 + 108.0, credit(bounced), 0.0)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), today(bounced).assumptions)
        assertEquals(DepartureStatus.ESTIMATED, today(bounced).status)
        // Sustained outside evidence settles it as an ordinary confirmed departure.
        val left = candidate.copy(events = candidate.events + event("outside", Transition.ABSENCE, "14:52"))
        val leftResult = derive(left)
        assertEquals(at("14:50"), leftResult.sessions.single { it.id == "session:back" }.end)
        assertEquals(175.0 + 105.0, credit(left), 0.0)
        assertEquals(DepartureStatus.NOT_IN_OFFICE, today(left).status)
        for (input in listOf(bounced, left)) assertTrue(derive(input).sessions.none { it.correctionId != null })
    }

    @Test fun unconfirmedEndOfDayExitDoesNotForceReviewAfterLeaving() {
        val gone = lunch("17:00", listOf(exit("home", "16:00"))).copy(unconfirmedExitIds = setOf("lunch", "home"))
        assertEquals(175.0 + 175.0, credit(gone), 0.0)
        assertEquals(DepartureStatus.NOT_IN_OFFICE, today(gone).status)
        val met = gone.copy(policy = Policy(targetMinutesPerDay = 300))
        assertEquals(DepartureStatus.TARGET_SATISFIED, today(met).status)
    }

    @Test fun entryGraceRunsOncePerGenuineArrivalAndPerOffice() {
        val base = lunch()
        // Duplicate or repeated ENTER observations during a visit do not restart grace.
        val repeated = base.copy(events = base.events + enter("again", "10:00") + enter("copy", "13:00"))
        assertEquals(credit(base), credit(repeated), 0.0)
        assertEquals(DepartureStatus.ESTIMATED, today(repeated).status)
        // The return at an office with a longer walk uses that office's own grace.
        val elsewhere = AttendanceInput(listOf(office, other), listOf(enter("in", "09:00"), exit("lunch", "12:00"),
            enter("back", "13:00", "b")), now = at("15:00"), historyStartDate = day.minusDays(30),
            unconfirmedExitIds = setOf("lunch"), delayedExitIds = setOf("lunch"))
        assertEquals(175.0 + 95.0, credit(elsewhere), 0.0)
        val estimate = today(elsewhere)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        // Exit grace only moves the projected EXIT, by that office's 15 minutes.
        assertEquals(estimate.creditedTargetAt!!.minusSeconds(15 * 60), estimate.estimatedExitAt)
        assertEquals(credit(elsewhere), credit(elsewhere), 0.0)
    }

    @Test fun replayAtTheSameInstantIsDeterministic() {
        val input = lunch("14:53", listOf(exit("maybe", "14:50"))).copy(candidateExitIds = setOf("maybe"))
        assertEquals(derive(input), derive(input))
        assertEquals(today(input), today(input))
    }

    @Test fun genuineMissingEvidenceStillNeedsReview() {
        // An EXIT with no arrival and no later visit: never invent the arrival.
        val orphan = AttendanceInput(listOf(office), listOf(exit("orphan", "11:00")), now = at("11:30"),
            historyStartDate = day.minusDays(30), unconfirmedExitIds = setOf("orphan"))
        val blocked = today(orphan)
        assertEquals(DepartureStatus.NEEDS_REVIEW, blocked.status)
        assertTrue(ReviewReason.MISSING_ENTER in blocked.reviewReasons)
        // An unobserved gap inside today's attendance stays unknown and blocking.
        val gap = AttendanceInput(listOf(office), listOf(enter("in", "09:00"), event("out", Transition.ABSENCE, "11:00"),
            enter("back", "12:00")), now = at("13:00"), historyStartDate = day.minusDays(30))
        assertEquals(DepartureStatus.NEEDS_REVIEW, today(gap).status)
    }

    @Test fun overnightRecoveryNoiseAndUnknownCoverageDoNotBlockToday() {
        val noisy = lunch("14:53", listOf(exit("night-a", "02:28"), exit("night-b", "02:28", "b"), exit("maybe", "14:50")))
            .let { it.copy(candidateExitIds = setOf("maybe"),
                unconfirmedExitIds = it.unconfirmedExitIds + setOf("night-a", "maybe"), unknownDates = setOf(day)) }
        val result = derive(noisy)
        assertEquals(2, result.sessions.count { ReviewReason.MISSING_ENTER in it.reviewReasons })
        assertFalse(AttendanceEngine.daily(noisy, result, day).hasCompleteHistory)
        assertEquals(DepartureStatus.ESTIMATED, today(noisy).status)
    }

    @Test fun legitimateCrossMidnightVisitCountsForTodayAndQualifies() {
        val yesterday = day.minusDays(1)
        val input = AttendanceInput(listOf(office), listOf(enter("late", "22:00", date = yesterday), exit("night", "01:00"),
            enter("in", "09:00")), now = at("12:00"), historyStartDate = day.minusDays(30),
            unconfirmedExitIds = setOf("night"), delayedExitIds = setOf("night"))
        assertEquals(60.0 + 175.0, credit(input), 0.0)
        val estimate = today(input)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), estimate.assumptions)
        // A still-open overnight visit projects from today's share of its credit.
        val open = AttendanceInput(listOf(office), listOf(enter("late", "22:00", date = yesterday)), now = at("01:00"),
            historyStartDate = day.minusDays(30))
        assertEquals(60.0, credit(open), 0.0)
        assertEquals(DepartureStatus.ESTIMATED, today(open).status)
        assertTrue(today(open).assumptions.isEmpty())
    }

    @Test fun missingEarlierHistoryKeepsTodayButNotARollingBalance() {
        val input = lunch().copy(historyStartDate = null)
        assertEquals(DepartureStatus.ESTIMATED, today(input).status)
        assertEquals(DepartureStatus.INCOMPLETE_HISTORY,
            AttendanceEngine.departure(input, derive(input), TargetWindow.ROLLING_30).status)
        assertFalse(AttendanceEngine.summary(input, derive(input), TargetWindow.ROLLING_30).hasCompleteHistory)
    }

    @Test fun deliberateConfirmationStaysAnExplicitAuditedCorrection() {
        val input = lunch()
        val confirmed = input.copy(corrections = listOf(Correction("confirm", "session:in", at("09:00"), at("12:00"), at("14:00"))))
        val session = derive(confirmed).sessions.single { it.id == "session:in" }
        assertEquals(Confidence.MANUAL, session.confidence)
        assertEquals("confirm", session.correctionId)
        assertTrue(today(confirmed).assumptions.isEmpty())
        // Confirmation is optional: it changes the qualifier, not today's credit or leave time.
        assertEquals(credit(input), credit(confirmed), 0.0)
        assertEquals(today(input).estimatedExitAt, today(confirmed).estimatedExitAt)
    }

    @Test fun revertingAnUnchangedCorrectionRestoresTheQualifiedEstimate() {
        val input = lunch()
        val original = today(input)
        assertEquals(DepartureStatus.ESTIMATED, original.status)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), original.assumptions)
        val saved = Correction("noop", "session:in", at("09:00"), at("12:00"), at("14:00"), appendSequence = 1)
        val corrected = input.copy(corrections = listOf(saved))
        // A genuine active correction stays MANUAL and needs no qualifier.
        assertEquals(Confidence.MANUAL, derive(corrected).sessions.single { it.id == "session:in" }.confidence)
        assertTrue(today(corrected).assumptions.isEmpty())
        val reverted = input.copy(corrections = listOf(saved, Correction("undo", "session:in", at("09:00"), at("12:00"),
            at("14:30"), revertToOriginal = true, appendSequence = 2)))
        val restored = derive(reverted).sessions.single { it.id == "session:in" }
        // The audit marker stays; reconstruction, credit and projection match the original.
        assertEquals("undo", restored.correctionId)
        assertTrue(restored.correctionReverted)
        assertEquals(credit(input), credit(reverted), 0.0)
        assertEquals(original, today(reverted))
    }

    @Test fun revertedVisitWithAPendingCandidateKeepsItsCapAndQualification() {
        // #136: a revert restores the candidate credit cap, so the restored visit projects
        // from the same capped credit, with the same qualifier, as the untouched one.
        val pending = lunch("14:53", listOf(exit("maybe", "14:50"))).copy(candidateExitIds = setOf("maybe"))
        assertEquals(DepartureStatus.ESTIMATED, today(pending).status)
        val reverted = pending.copy(corrections = listOf(
            Correction("edit", "session:back", at("13:00"), null, at("14:00"), appendSequence = 1),
            Correction("undo", "session:back", at("13:00"), null, at("14:10"), revertToOriginal = true, appendSequence = 2)))
        assertEquals(credit(pending), credit(reverted), 0.0)
        assertEquals(today(pending), today(reverted))
        assertTrue(EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED in today(reverted).assumptions)
    }

    @Test fun reviewDateScopeSeparatesEarlierDaysFromToday() {
        val yesterday = day.minusDays(1)
        val input = lunch().copy(events = lunch().events + exit("old", "12:00", date = yesterday))
        val result = derive(input)
        val old = result.reviews.single { it.sessionId == "session:old" && it.reason == ReviewReason.MISSING_ENTER }
        val lunchReview = result.reviews.single { it.sessionId == "session:in" }
        assertFalse(AttendanceEngine.reviewAffectsDate(input, result, old, day))
        assertTrue(AttendanceEngine.reviewAffectsDate(input, result, old, yesterday))
        assertTrue(AttendanceEngine.reviewAffectsDate(input, result, lunchReview, day))
        // Undatable reviews count everywhere rather than being dropped.
        assertTrue(AttendanceEngine.reviewAffectsDate(input, result, ReviewItem(ReviewReason.INVALID_EVENT, setOf("missing")), day))
        // Week-to-date still sees the earlier day's review.
        assertEquals(DepartureStatus.NEEDS_REVIEW, AttendanceEngine.departure(input, result, TargetWindow.WEEK_TO_DATE).status)
    }
}
