package dev.hamstercage.data

import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.Confidence
import dev.hamstercage.domain.Correction
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.EstimateAssumption
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.TargetWindow
import dev.hamstercage.domain.Transition
import dev.hamstercage.ui.correctionPickerMinute
import dev.hamstercage.ui.dashboardPresence
import dev.hamstercage.ui.todayLeaveConfident
import dev.hamstercage.ui.todayLeaveText
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

/** #133 transformed export, replayed through the repository verification state and engine. */
class ReviewProjectionFixtureTest {
    private val fixture = ObfuscatedFixture
    private val snapshot = fixture.snapshot()
    private val input = fixture.input(snapshot)
    private val result = AttendanceEngine.derive(input)
    private val lunchVisit = "session:fact-15"
    private val afternoonVisit = "session:fact-8"

    @Test fun replayMatchesTheExportedBaselineAndExplainsItsLedger() {
        // Why the lunch EXIT is "delayed": no verification sample arrived in its window and the
        // next fact is a return an hour later, so verification expired undecided. That uncertain
        // state is merged into delayedExitIds; delivery took only 73 ms.
        assertEquals(setOf("fact-22", "fact-24", "fact-9"), input.delayedExitIds)
        assertEquals(setOf("fact-16"), input.candidateExitIds)
        assertTrue("fact-1" in input.rejectedExitIds)
        val lunch = result.sessions.single { it.id == lunchVisit }
        assertEquals(setOf(ReviewReason.UNCONFIRMED_BOUNDARY), lunch.reviewReasons)
        assertEquals(setOf(ReviewReason.OPEN_SESSION, ReviewReason.UNCONFIRMED_BOUNDARY),
            result.sessions.single { it.id == afternoonVisit }.reviewReasons)
        // Ledger: raw visits through evaluation 293.70m; the candidate EXIT caps the open visit,
        // leaving 290.46m observed; two 5m entry graces (office-2, once per arrival) give 280.46m.
        // Exit grace never reduces credit and the 62-minute lunch gap is not bridged.
        val raw = result.sessions.filter { it.start != null && it.start!! >= fixture.selectedDay.atStartOfDay(fixture.zone).toInstant() }
            .sumOf { AttendanceEngine.observedMinutes(it, input.now) }
        assertEquals(293.70244148333336, raw, 1e-9)
        val observed = AttendanceEngine.observedDailyMinutes(input, fixture.selectedDay)
        assertEquals(290.45748333333336, observed, 1e-9)
        val credited = AttendanceEngine.daily(input, result, fixture.selectedDay).creditedMinutes
        assertEquals(fixture.exportedCreditedMinutes, credited, 1e-9)
        assertEquals(10.0, observed - credited, 1e-9)
        assertTrue(result.intervals.none { it.reconciledGap })
    }

    @Test fun supportedEstimateAppearsWithoutAnyCorrectionOrReviewSave() {
        val estimate = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        assertEquals(setOf(EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED, EstimateAssumption.EARLIER_EXIT_UNCONFIRMED),
            estimate.assumptions)
        // The overnight orphan EXITs and both uncertain boundaries no longer gate Today.
        assertEquals(setOf(lunchVisit, afternoonVisit, "session:fact-25", "session:fact-7"), estimate.nonBlockingSessionIds)
        val remainingMillis = kotlin.math.ceil(estimate.remainingMinutes * 60000.0).toLong()
        assertEquals(input.now.plusMillis(remainingMillis), estimate.creditedTargetAt)
        assertEquals(estimate.creditedTargetAt!!.minus(Duration.ofMinutes(5)), estimate.estimatedExitAt)
        // Nothing is silently confirmed: still LOW, still listed for review, no correction written.
        assertEquals(5, input.corrections.size)
        assertTrue(result.sessions.filter { it.id in setOf(lunchVisit, afternoonVisit) }
            .all { it.confidence == Confidence.LOW && it.correctionId == null })
        assertTrue(result.reviews.any { it.sessionId == lunchVisit && it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        // Historical corrected visits are unrelated to Today and unchanged.
        assertEquals(2, result.sessions.count { it.confidence == Confidence.MANUAL })
        assertEquals(AttendanceEngine.derive(input), result)
        assertEquals(estimate, AttendanceEngine.departure(input, AttendanceEngine.derive(input), TargetWindow.TODAY))
    }

    @Test fun unchangedReviewSaveWasACorrectionNotAConfirmation() {
        // What the editor previews and saves when the user changes nothing.
        val session = result.sessions.single { it.id == lunchVisit }
        fun minute(value: java.time.Instant) = correctionPickerMinute(value, fixture.zone).atZone(fixture.zone).toInstant()
        val edit = AttendanceEdit.Correct(input, Correction("noop", session.id, minute(session.start!!), minute(session.end!!),
            input.now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), appendSequence = 6))
        val proposed = edit.proposedInput()
        // Preview or cancel is read-only: the baseline is untouched.
        assertEquals(5, input.corrections.size)
        assertEquals(result, AttendanceEngine.derive(input))
        // Saving appends a correction that makes the visit MANUAL, drops its review and
        // nudges credit through whole-minute truncation (+56.962s start, -33.963s end).
        val saved = AttendanceEngine.derive(proposed)
        val corrected = saved.sessions.single { it.id == lunchVisit }
        assertEquals(Confidence.MANUAL, corrected.confidence)
        assertTrue(corrected.reviewReasons.isEmpty())
        assertEquals(22.999 / 60.0, AttendanceEngine.daily(proposed, saved, fixture.selectedDay).creditedMinutes -
            AttendanceEngine.daily(input, result, fixture.selectedDay).creditedMinutes, 1e-9)
        // The estimate no longer depends on it: only the earlier-exit qualifier goes away.
        val after = AttendanceEngine.departure(proposed, saved, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, after.status)
        assertEquals(setOf(EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED), after.assumptions)
    }

    @Test fun laterTimeAndEvidenceResolveThePendingExitWithoutManualSave() {
        // Past the candidate's five-minute verification window, it becomes an unconfirmed departure.
        val later = input.now.plus(Duration.ofMinutes(10))
        val expired = fixture.input(snapshot, later)
        val expiredResult = AttendanceEngine.derive(expired)
        assertTrue(expired.candidateExitIds.isEmpty())
        val afternoon = expiredResult.sessions.single { it.id == afternoonVisit }
        assertEquals(java.time.Instant.parse("2025-10-10T20:13:42.376Z"), afternoon.end)
        assertEquals(fixture.exportedCreditedMinutes,
            AttendanceEngine.daily(expired, expiredResult, fixture.selectedDay).creditedMinutes, 1e-9)
        assertEquals(DepartureStatus.NOT_IN_OFFICE, AttendanceEngine.departure(expired, expiredResult, TargetWindow.TODAY).status)
        // Consistent status: the unconfirmed departure is likely, neither "Needs review" nor "In office".
        assertEquals("Probably outside office", dashboardPresence(expired, expiredResult, true).label)
        assertFalse(dashboardPresence(expired, expiredResult, true).needsReview)
        // A genuine return later: new arrival grace applies once more and the estimate resumes.
        val returnAt = java.time.Instant.parse("2025-10-10T20:40:00Z")
        val returned = snapshot.copy(eventEvidence = snapshot.eventEvidence + RecordedEvent(
            RawEvent("synthetic-return", "office-2", Transition.ENTER, returnAt), returnAt, returnAt, accuracyMeters = 10f))
        val now = returnAt.plus(Duration.ofMinutes(20))
        val back = fixture.input(returned, now)
        val backResult = AttendanceEngine.derive(back)
        assertEquals(fixture.exportedCreditedMinutes + 15.0,
            AttendanceEngine.daily(back, backResult, fixture.selectedDay).creditedMinutes, 1e-9)
        val estimate = AttendanceEngine.departure(back, backResult, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        assertEquals(setOf(EstimateAssumption.EARLIER_EXIT_UNCONFIRMED), estimate.assumptions)
        assertEquals(5, back.corrections.size)
    }

    @Test fun dashboardShowsTheQualifiedEstimateAndKeepsUnrelatedHistoryReviewable() {
        val presence = dashboardPresence(input, result, trackingReady = true)
        assertEquals("Checking whether you've left", presence.label)
        val estimate = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
        assertEquals("If you're still here, leave at about 5:32 PM, or sooner if you didn't really leave earlier.",
            todayLeaveText(estimate, true, input.now, fixture.zone, todayLeaveConfident(input, result, true)))
        // Prior-day orphan EXITs stay reachable as other records, not a review of today.
        assertFalse(presence.needsReview)
        assertTrue(presence.otherReview)
        val todayOnly = snapshot.copy(eventEvidence = snapshot.eventEvidence.filterNot { it.event.id in setOf("fact-26", "fact-29") })
        val todayInput = fixture.input(todayOnly)
        assertFalse(dashboardPresence(todayInput, AttendanceEngine.derive(todayInput), true).otherReview)
        // The week through today is not offered as reliable (unknown coverage is reported before its reviews).
        assertEquals(DepartureStatus.INCOMPLETE_HISTORY, AttendanceEngine.departure(input, result, TargetWindow.WEEK_TO_DATE).status)
    }

    @Test fun correctingThenRevertingEachUncorrectedVisitRestoresTheFixtureBaseline() {
        // #136: the pending candidate on the open visit and the unconfirmed lunch EXIT keep their
        // caps and qualifiers after a correction and its revert; only audit metadata differs.
        val targets = result.sessions.filter { it.correctionId == null && it.manualSessionId == null }
        assertTrue(targets.any { it.id == lunchVisit } && targets.any { it.id == afternoonVisit })
        val stamp = input.now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val next = input.corrections.maxOf { it.appendSequence }
        val appended = targets.withIndex().flatMap { (index, session) ->
            val start = session.start ?: session.end!!
            listOf(Correction("parity-edit-$index", session.id, start, null, stamp, appendSequence = next + 2L * index + 1),
                Correction("parity-undo-$index", session.id, start, null, stamp, revertToOriginal = true,
                    appendSequence = next + 2L * index + 2))
        }
        val reverted = input.copy(corrections = input.corrections + appended)
        val restored = AttendanceEngine.derive(reverted)
        assertEquals(targets.map { it.id }.toSet(), restored.sessions.filter { it.correctionReverted }.map { it.id }.toSet())
        assertEquals(result.intervals, restored.intervals)
        assertEquals(result.reviews, restored.reviews)
        assertEquals(result.sessions, restored.sessions.map {
            if (it.correctionReverted) it.copy(correctionId = null, correctionReverted = false) else it })
        assertEquals(AttendanceEngine.daily(input, result, fixture.selectedDay),
            AttendanceEngine.daily(reverted, restored, fixture.selectedDay))
        for (window in TargetWindow.entries)
            assertEquals(window.name, AttendanceEngine.departure(input, result, window), AttendanceEngine.departure(reverted, restored, window))
        assertEquals(dashboardPresence(input, result, true), dashboardPresence(reverted, restored, true))
        assertEquals(todayLeaveConfident(input, result, true), todayLeaveConfident(reverted, restored, true))
    }
}
