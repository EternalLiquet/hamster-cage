package dev.hamstercage.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AttendanceEngineTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 23)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun at(time: String, date: LocalDate = day): Instant = date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
    private fun enter(id: String, time: String, officeId: String = "a", date: LocalDate = day) = RawEvent(id, officeId, Transition.ENTER, at(time, date))
    private fun exit(id: String, time: String, officeId: String = "a", date: LocalDate = day) = RawEvent(id, officeId, Transition.EXIT, at(time, date))
    private fun input(events: List<RawEvent>, now: Instant = at("20:00"), policy: Policy = Policy(), offices: List<Office> = listOf(office), corrections: List<Correction> = emptyList()) =
        AttendanceInput(offices, events, corrections, policy, now, day.minusDays(100))
    private fun minutes(input: AttendanceInput) = AttendanceEngine.daily(input, AttendanceEngine.derive(input), input.now.atZone(input.policy.zoneId).toLocalDate()).creditedMinutes
    private fun assertMinutes(expected: Double, input: AttendanceInput) = assertEquals(expected, minutes(input), 0.00001)

    @Test fun cleanSingleSessionSeparatesRawFromGrace() {
        val data = input(listOf(enter("e", "09:00"), exit("x", "15:00")))
        val result = AttendanceEngine.derive(data)
        assertMinutes(370.0, data)
        assertEquals(360.0, AttendanceEngine.observedMinutes(result.sessions.single(), data.now), 0.0)
        assertEquals(Confidence.HIGH, result.sessions.single().confidence)
    }
    @Test fun splitLunchMatchesSourceExample380Minutes() {
        assertMinutes(380.0, input(listOf(enter("1", "09:15"), exit("2", "11:45"), enter("3", "13:30"), exit("4", "17:00"))))
    }
    @Test fun customGracePerOffice() {
        assertMinutes(385.0, input(listOf(enter("1", "09:00"), exit("2", "15:00")), offices = listOf(office.copy(entryGraceMinutes = 10, exitGraceMinutes = 15))))
    }
    @Test fun graceOverlapUnionDoesNotDoubleCount() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "14:00"), enter("3", "14:04"), exit("4", "17:00")))
        assertMinutes(490.0, data)
        assertEquals(1, AttendanceEngine.derive(data).intervals.size)
    }
    @Test fun shortGapAtThresholdIsReconciledWithProvenance() {
        val events = listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "12:20"), exit("4", "15:00"))
        val result = AttendanceEngine.derive(input(events))
        assertEquals(370.0, result.intervals.single().minutes, 0.0)
        assertTrue(result.intervals.single().reconciledGap)
        assertEquals(setOf("session:1", "session:3"), result.intervals.single().sessionIds)
        assertMinutes(360.0, input(events, policy = Policy(shortGapMinutes = 9)))
    }
    @Test fun repeatedEnterPreservesEarliestAndFlagsLowConfidence() {
        val data = input(listOf(enter("1", "09:00"), enter("2", "10:00"), exit("3", "15:00")))
        assertMinutes(370.0, data)
        val session = AttendanceEngine.derive(data).sessions.single()
        assertEquals(Confidence.LOW, session.confidence)
        assertTrue(ReviewReason.REPEATED_ENTER in session.reviewReasons)
        assertEquals(setOf("1", "2", "3"), session.sourceEventIds)
    }
    @Test fun repeatedExitDoesNotInventAnotherSessionStart() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "15:00"), exit("3", "15:05")))
        assertMinutes(370.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.any { it.reason == ReviewReason.MISSING_ENTER })
    }
    @Test fun exitWithoutEnterOrInstallInsideOfficeIsUnknownNotInventedHistory() {
        val data = input(listOf(exit("1", "15:00")))
        assertMinutes(0.0, data)
        val session = AttendanceEngine.derive(data).sessions.single()
        assertNull(session.start)
        assertEquals(Confidence.LOW, session.confidence)
    }
    @Test fun openSessionGetsEntryGraceButNoFutureExitGrace() {
        val data = input(listOf(enter("1", "09:00")), now = at("10:00"))
        assertMinutes(65.0, data)
        assertEquals(at("10:00"), AttendanceEngine.derive(data).intervals.single().end)
        assertEquals(1, data.events.size)
    }
    @Test fun recentClosedSessionFutureExitGraceIsClippedToNow() {
        assertMinutes(66.0, input(listOf(enter("1", "09:00"), exit("2", "10:00")), now = at("10:01")))
    }
    @Test fun staleOpenSessionDoesNotAccrueAnUnboundedOvernightShift() {
        val data = input(listOf(enter("1", "09:00", date = day.minusDays(1))))
        assertMinutes(0.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.any { it.reason == ReviewReason.STALE_OPEN_SESSION })
    }
    @Test fun twoEligibleOfficesPoolTheirMinutes() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "13:00", "b"), exit("4", "16:00", "b")), offices = listOf(office, office.copy(id = "b")))
        assertMinutes(380.0, data)
    }
    @Test fun overlappingOfficesAndGraceAreGloballyUnioned() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "11:00", "b"), exit("4", "15:00", "b")), offices = listOf(office, office.copy(id = "b")))
        assertMinutes(370.0, data)
    }
    @Test fun shortTransferGapBetweenDifferentOfficesIsNotCredited() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "12:15", "b"), exit("4", "15:00", "b")), offices = listOf(office, office.copy(id = "b")))
        assertMinutes(365.0, data)
    }
    @Test fun disabledAndIneligibleOfficeRemainEvidenceButDoNotCredit() {
        val events = listOf(enter("1", "09:00"), exit("2", "15:00"))
        assertMinutes(0.0, input(events, offices = listOf(office.copy(enabled = false))))
        assertMinutes(0.0, input(events, offices = listOf(office.copy(countsTowardAttendance = false))))
        assertEquals(1, AttendanceEngine.derive(input(events, offices = listOf(office.copy(enabled = false)))).sessions.size)
    }
    @Test fun midnightIntervalsSplitAtPolicyLocalBoundary() {
        val data = input(listOf(enter("1", "23:00", date = day.minusDays(1)), exit("2", "01:00")))
        val result = AttendanceEngine.derive(data)
        assertEquals(65.0, AttendanceEngine.daily(data, result, day.minusDays(1)).creditedMinutes, 0.0)
        assertEquals(65.0, AttendanceEngine.daily(data, result, day).creditedMinutes, 0.0)
    }
    @Test fun springForwardUsesRealElapsedTime() {
        val date = LocalDate.of(2026, 3, 8)
        assertMinutes(70.0, input(listOf(enter("1", "01:30", date = date), exit("2", "03:30", date = date)), now = at("12:00", date)))
    }
    @Test fun fallBackUsesRealElapsedTime() {
        val date = LocalDate.of(2026, 11, 1)
        assertMinutes(190.0, input(listOf(enter("1", "00:30", date = date), exit("2", "02:30", date = date)), now = at("12:00", date)))
    }
    @Test fun holidayRemovesRequirementButPreservesRealAttendance() {
        val monday = day.minusDays(2)
        val data = input(listOf(enter("1", "09:00", date = monday), exit("2", "15:00", date = monday)), policy = Policy(excludedDates = listOf(ExcludedDate(monday, ExclusionReason.BANK_HOLIDAY))))
        val result = AttendanceEngine.derive(data)
        assertEquals(1440, AttendanceEngine.summary(data, result, TargetWindow.FULL_WEEK).requiredMinutes)
        assertEquals(0, AttendanceEngine.daily(data, result, monday).requiredMinutes)
        assertEquals(370.0, AttendanceEngine.daily(data, result, monday).creditedMinutes, 0.0)
    }
    @Test fun wfhRemainsInDenominatorWithZeroOfficeMinutes() {
        val data = input(emptyList(), policy = Policy(wfhDates = setOf(day)))
        val summary = AttendanceEngine.summary(data, AttendanceEngine.derive(data), TargetWindow.TODAY)
        assertEquals(1, summary.expectedWorkdays)
        assertEquals(360, summary.requiredMinutes)
        assertEquals(0.0, summary.creditedMinutes, 0.0)
    }
    @Test fun futureWeekdaysOnlyEnterExplicitFullPeriodProjection() {
        val data = input(emptyList())
        val result = AttendanceEngine.derive(data)
        assertEquals(1080, AttendanceEngine.summary(data, result, TargetWindow.WEEK_TO_DATE).requiredMinutes)
        val projected = AttendanceEngine.summary(data, result, TargetWindow.FULL_WEEK)
        assertEquals(1800, projected.requiredMinutes)
        assertEquals(2, projected.projectedExpectedWorkdays)
        assertEquals(0, projected.unknownExpectedWorkdays)
        assertEquals(0, AttendanceEngine.daily(data, result, day.plusDays(1)).requiredMinutes)
    }
    @Test fun rolling30WindowIncludesExactlyTodayAndPrevious29Dates() = checkRolling(TargetWindow.ROLLING_30, 30)
    @Test fun rolling90WindowIncludesExactlyTodayAndPrevious89Dates() = checkRolling(TargetWindow.ROLLING_90, 90)
    private fun checkRolling(target: TargetWindow, days: Int) {
        val start = day.minusDays(days - 1L)
        val data = input(listOf(enter("1", "09:00", date = start.minusDays(1)), exit("2", "15:00", date = start.minusDays(1)), enter("3", "09:00", date = start), exit("4", "15:00", date = start)))
        val summary = AttendanceEngine.summary(data, AttendanceEngine.derive(data), target)
        assertEquals(start, summary.startDate)
        assertEquals(day, summary.endDate)
        assertEquals(370.0, summary.creditedMinutes, 0.0)
        val expected = (0 until days).map { start.plusDays(it.toLong()) }.count { it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        assertEquals(expected, summary.expectedWorkdays)
    }
    @Test fun coverageNeverTruncatesRollingDenominatorToInstallDate() {
        val data = input(emptyList()).copy(historyStartDate = day)
        val summary = AttendanceEngine.summary(data, AttendanceEngine.derive(data), TargetWindow.ROLLING_90)
        assertTrue(summary.expectedWorkdays > 60)
        assertEquals(1, summary.knownExpectedWorkdays)
        assertEquals(summary.expectedWorkdays - 1, summary.unknownExpectedWorkdays)
        assertFalse(summary.hasCompleteHistory)
        assertEquals(DepartureStatus.INCOMPLETE_HISTORY, AttendanceEngine.departure(data, AttendanceEngine.derive(data), TargetWindow.ROLLING_90).status)
    }
    @Test fun explicitCaptureOutageMakesDayUnknown() {
        val data = input(emptyList()).copy(unknownDates = setOf(day))
        assertFalse(AttendanceEngine.daily(data, AttendanceEngine.derive(data), day).hasCompleteHistory)
        val unknown = data.copy(historyStartDate = null, unknownDates = emptySet())
        assertEquals(1, AttendanceEngine.daily(unknown, AttendanceEngine.derive(unknown), day).unknownExpectedWorkdays)
    }
    @Test fun correctionsRetainRawEventsAndResolveReview() {
        val events = listOf(exit("exitOnly", "15:00"))
        val correction = Correction("c", "session:exitOnly", at("09:00"), at("14:00"), at("18:00"), "Restored missed enter")
        val data = input(events, corrections = listOf(correction))
        val result = AttendanceEngine.derive(data)
        assertMinutes(310.0, data)
        assertEquals(events, data.events)
        assertEquals(setOf("exitOnly"), result.sessions.single().sourceEventIds)
        assertEquals(Confidence.MANUAL, result.sessions.single().confidence)
        assertTrue(result.reviews.isEmpty())
    }
    @Test fun observedDailyTotalUnionsRawEvidenceAndIgnoresCorrectionGraceAndGaps() {
        val events = listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "11:00", "b"), exit("4", "15:00", "b"))
        val correction = Correction("c", "session:1", at("08:00"), at("16:00"), at("18:00"))
        val data = input(events, offices = listOf(office, office.copy(id = "b")), corrections = listOf(correction))
        assertEquals(360.0, AttendanceEngine.observedDailyMinutes(data, day), 0.0)
        assertMinutes(490.0, data)
    }
    @Test fun latestCorrectionWinsDeterministically() {
        val early = Correction("c1", "session:1", at("09:00"), at("14:00"), at("18:00"))
        val late = early.copy(id = "c2", end = at("15:00"), createdAt = at("19:00"))
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00")), corrections = listOf(late, early))
        assertMinutes(370.0, data)
        assertEquals(AttendanceEngine.derive(data), AttendanceEngine.derive(data.copy(corrections = listOf(early, late))))
    }
    @Test fun invalidAndOrphanCorrectionsAreReviewable() {
        val bad = Correction("bad", "session:1", at("15:00"), at("09:00"), at("18:00"))
        val orphan = bad.copy(id = "orphan", sessionId = "missing")
        val data = input(listOf(enter("1", "09:00"), exit("2", "15:00")), corrections = listOf(bad, orphan))
        assertMinutes(370.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.map { it.reason }.containsAll(listOf(ReviewReason.INVALID_CORRECTION, ReviewReason.ORPHAN_CORRECTION)))
    }
    @Test fun targetAlreadySatisfiedRequiresNoAdditionalMinutes() {
        val data = input(listOf(enter("1", "09:00")), now = at("16:00"))
        val departure = AttendanceEngine.departure(data, AttendanceEngine.derive(data), TargetWindow.TODAY)
        assertEquals(DepartureStatus.TARGET_SATISFIED, departure.status)
        assertEquals(0.0, departure.remainingMinutes, 0.0)
    }
    @Test fun departureUsesGraceWithoutAddingItToLiveCredit() {
        val data = input(listOf(enter("1", "09:00")), now = at("10:00"))
        val result = AttendanceEngine.derive(data)
        val departure = AttendanceEngine.departure(data, result, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, departure.status)
        assertEquals(at("14:55"), departure.creditedTargetAt)
        assertEquals(at("14:50"), departure.estimatedExitAt)
        assertEquals(65.0, AttendanceEngine.daily(data, result, day).creditedMinutes, 0.0)
    }
    @Test fun departureCannotUseTomorrowToSatisfyTodaysWindow() {
        val data = input(listOf(enter("1", "23:00")), now = at("23:30"))
        assertEquals(DepartureStatus.UNREACHABLE_IN_WINDOW, AttendanceEngine.departure(data, AttendanceEngine.derive(data), TargetWindow.TODAY).status)
    }
    @Test fun departureRequiresUnambiguousActiveOffice() {
        val data = input(listOf(enter("1", "09:00"), enter("2", "09:01")), now = at("10:00"))
        assertEquals(DepartureStatus.NEEDS_REVIEW, AttendanceEngine.departure(data, AttendanceEngine.derive(data), TargetWindow.TODAY).status)
        val empty = input(emptyList())
        assertEquals(DepartureStatus.NOT_IN_OFFICE, AttendanceEngine.departure(empty, AttendanceEngine.derive(empty), TargetWindow.TODAY).status)
    }
    @Test fun zeroExpectedDaysHaveUndefinedAverage() {
        val data = input(emptyList(), policy = Policy(expectedWeekdays = emptySet()))
        val summary = AttendanceEngine.summary(data, AttendanceEngine.derive(data), TargetWindow.ROLLING_30)
        assertEquals(0, summary.requiredMinutes)
        assertNull(summary.averageMinutes)
    }
    @Test fun changingPolicyRecomputesHistoryFromSameEvidence() {
        val original = input(listOf(enter("1", "09:00"), exit("2", "15:00")))
        val updated = original.copy(policy = original.policy.copy(targetMinutesPerDay = 420))
        assertEquals(-50.0, AttendanceEngine.summary(updated, AttendanceEngine.derive(updated), TargetWindow.TODAY).balanceMinutes, 0.0)
        assertEquals(original.events, updated.events)
        val noGrace = original.copy(offices = listOf(office.copy(entryGraceMinutes = 0, exitGraceMinutes = 0)))
        assertMinutes(360.0, noGrace)
    }
    @Test fun duplicatesDoNotIncreaseTimeAndKeepAllSourceIds() {
        val events = listOf(enter("1", "09:00"), exit("2", "15:00"))
        val repeated = input(events + events + enter("replayed", "09:00"))
        assertMinutes(370.0, repeated)
        assertEquals(setOf("1", "2", "replayed"), AttendanceEngine.derive(repeated).sessions.single().sourceEventIds)
        assertEquals(Confidence.MEDIUM, AttendanceEngine.derive(repeated).sessions.single().confidence)
    }
    @Test fun unorderedInputProducesIdenticalOutput() {
        val events = listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "13:00"), exit("4", "15:00"))
        assertEquals(AttendanceEngine.derive(input(events)), AttendanceEngine.derive(input(events.reversed())))
    }
    @Test fun futureUnknownOfficeAndConflictingIdsNeverBecomeCredit() {
        val data = input(listOf(enter("future", "21:00"), enter("unknown", "09:00", "missing"), enter("conflict", "09:00"), exit("conflict", "15:00")))
        assertMinutes(0.0, data)
        assertEquals(setOf(ReviewReason.FUTURE_EVENT, ReviewReason.UNKNOWN_OFFICE, ReviewReason.CONFLICTING_EVENT_ID), AttendanceEngine.derive(data).reviews.map { it.reason }.toSet())
    }
    @Test fun unionInvariantsAcrossDeterministicSyntheticIntervals() {
        val random = java.util.Random(42)
        repeat(100) {
            val intervals = (0..30).map { index ->
                val start = at("00:00").plusSeconds(random.nextInt(86400).toLong())
                CreditedInterval(start, start.plusSeconds(random.nextInt(10000).toLong()), setOf(index.toString()))
            }
            val united = AttendanceEngine.union(intervals)
            assertTrue(united.all { it.minutes >= 0.0 })
            assertTrue(united.sumOf { it.minutes } <= intervals.sumOf { it.minutes } + 0.00001)
            assertEquals(united, AttendanceEngine.union(intervals + intervals))
            assertEquals(united, AttendanceEngine.union(intervals.reversed()))
            assertTrue(united.zipWithNext().all { (a, b) -> a.end < b.start })
        }
    }
    @Test fun dstCalendarWindowUsesLocalDatesNot86400SecondDays() {
        val date = LocalDate.of(2026, 3, 8)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val data = input(emptyList(), now = end)
        val result = AttendanceResult(emptyList(), listOf(CreditedInterval(start, end, emptySet())), emptyList())
        assertEquals(23 * 60.0, AttendanceEngine.daily(data, result, date).creditedMinutes, 0.0)
    }
    @Test fun overlappingManualIntervalCannotConsumeAutomaticExit() {
        val events = listOf(enter("in", "09:00"), exit("out", "17:00"))
        val manual = ManualSession("lunch", "a", at("12:00"), at("13:00"), at("18:00"), "User observation")
        val data = input(events).copy(manualSessions = listOf(manual))
        val result = AttendanceEngine.derive(data)
        assertMinutes(490.0, data)
        assertEquals(2, result.sessions.size)
        val automatic = result.sessions.single { it.id == "session:in" }
        assertEquals(at("17:00"), automatic.end)
        assertEquals(setOf("in", "out"), automatic.sourceEventIds)
        assertNull(automatic.manualSessionId)
        val user = result.sessions.single { it.id == "manual:lunch" }
        assertEquals("lunch", user.manualSessionId)
        assertEquals(Confidence.MANUAL, user.confidence)
        assertTrue(user.sourceEventIds.isEmpty())
        assertTrue(result.reviews.isEmpty())
        assertEquals(events, data.events)
        assertEquals(480.0, AttendanceEngine.observedDailyMinutes(data, day), 0.0)
    }
    @Test fun manualCorrectionRetainsStableSourceAndDoesNotTouchAutomaticSession() {
        val manual = ManualSession("m", "a", at("12:00"), at("13:00"), at("18:00"))
        val correction = Correction("c", "manual:m", at("08:00"), at("18:00"), at("19:00"))
        val data = input(listOf(enter("1", "09:00"), exit("2", "17:00")), corrections = listOf(correction))
            .copy(manualSessions = listOf(manual))
        val result = AttendanceEngine.derive(data)
        assertMinutes(610.0, data)
        assertEquals(at("17:00"), result.sessions.single { it.id == "session:1" }.end)
        val edited = result.sessions.single { it.id == "manual:m" }
        assertEquals("m", edited.manualSessionId)
        assertEquals("c", edited.correctionId)
        assertEquals(listOf(manual), data.manualSessions)
        assertTrue(result.reviews.isEmpty())
    }
    @Test fun multipleOverlappingManualIntervalsUnionWithoutDoubleCount() {
        val manuals = listOf(
            ManualSession("a", "a", at("09:00"), at("13:00"), at("18:00")),
            ManualSession("b", "a", at("12:00"), at("15:00"), at("18:00")),
            ManualSession("c", "a", at("14:00"), at("17:00"), at("18:00")),
        )
        val data = input(emptyList()).copy(manualSessions = manuals)
        assertMinutes(490.0, data)
        val result = AttendanceEngine.derive(data)
        assertEquals(setOf("manual:a", "manual:b", "manual:c"), result.intervals.single().sessionIds)
        assertEquals(result, AttendanceEngine.derive(data.copy(manualSessions = manuals.reversed() + manuals)))
        assertEquals(0.0, AttendanceEngine.observedDailyMinutes(data, day), 0.0)
    }
    @Test fun openManualSessionIsIndependentAndNeverReceivesFutureGrace() {
        val manual = ManualSession("m", "a", at("09:00"), null, at("09:30"))
        val data = input(emptyList(), now = at("10:00")).copy(manualSessions = listOf(manual))
        assertMinutes(65.0, data)
        val result = AttendanceEngine.derive(data)
        assertEquals(input(emptyList(), now = at("10:00")).now, result.intervals.single().end)
        assertEquals(Confidence.MANUAL, result.sessions.single().confidence)
        assertEquals(DepartureStatus.ESTIMATED, AttendanceEngine.departure(data, result, TargetWindow.TODAY).status)
        assertTrue(data.events.isEmpty())
    }
    @Test fun overlappingClosedManualDoesNotCloseAutomaticOpenSession() {
        val data = input(listOf(enter("1", "09:00")), now = at("14:00")).copy(
            manualSessions = listOf(ManualSession("m", "a", at("12:00"), at("13:00"), at("13:30"))))
        val result = AttendanceEngine.derive(data)
        assertMinutes(305.0, data)
        assertTrue(result.sessions.single { it.id == "session:1" }.isOpen)
        assertEquals(DepartureStatus.ESTIMATED, AttendanceEngine.departure(data, result, TargetWindow.TODAY).status)
    }
    @Test fun staleManualOpenRequiresReviewInsteadOfUnboundedAccrual() {
        val manual = ManualSession("m", "a", at("09:00", day.minusDays(1)), null, at("09:30", day.minusDays(1)))
        val data = input(emptyList()).copy(manualSessions = listOf(manual))
        assertMinutes(0.0, data)
        assertEquals(DepartureStatus.NEEDS_REVIEW, AttendanceEngine.departure(data, AttendanceEngine.derive(data), TargetWindow.TODAY).status)
    }
    @Test fun invalidAndConflictingManualEvidenceIsFlaggedWithoutCredit() {
        val original = ManualSession("m", "a", at("09:00"), at("12:00"), at("18:00"))
        val conflict = original.copy(end = at("13:00"))
        val bad = original.copy(id = "bad", start = at("15:00"))
        val future = original.copy(id = "future", end = at("21:00"))
        val data = input(emptyList()).copy(manualSessions = listOf(original, conflict, bad, future))
        assertMinutes(0.0, data)
        val reasons = AttendanceEngine.derive(data).reviews.map { it.reason }.toSet()
        assertEquals(setOf(ReviewReason.CONFLICTING_MANUAL_SESSION_ID, ReviewReason.INVALID_MANUAL_SESSION), reasons)
    }
}
