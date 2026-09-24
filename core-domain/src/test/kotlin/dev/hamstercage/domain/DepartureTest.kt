package dev.hamstercage.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DepartureTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 23)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun at(time: String, date: LocalDate = day) = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun enter(time: String = "09:00", id: String = "in", officeId: String = "a", date: LocalDate = day) =
        RawEvent(id, officeId, Transition.ENTER, at(time, date))
    private fun exit(time: String, id: String = "out", officeId: String = "a", date: LocalDate = day) =
        RawEvent(id, officeId, Transition.EXIT, at(time, date))
    private fun input(events: List<RawEvent> = listOf(enter()), now: Instant = at("12:00"), policy: Policy = Policy()) =
        AttendanceInput(listOf(office), events, policy = policy, now = now, historyStartDate = day.minusDays(100))
    private fun estimate(data: AttendanceInput, target: TargetWindow = TargetWindow.TODAY) =
        AttendanceEngine.departure(data, AttendanceEngine.derive(data), target)
    private fun assertSuppressed(status: DepartureStatus, estimate: DepartureEstimate) {
        assertEquals(status, estimate.status)
        assertNull(estimate.estimatedExitAt)
        assertNull(estimate.creditedTargetAt)
    }

    @Test fun everyOutcomeNamesItsTarget() {
        assertEquals(5, TargetWindow.entries.map { target ->
            val result = estimate(input(), target)
            assertEquals(target, result.target)
            assertTrue(result.targetName.isNotBlank())
            result.targetName
        }.distinct().size)
    }

    @Test fun exitGraceAdvancesDepartureWithoutCreditingFutureTime() {
        val data = input()
        val derived = AttendanceEngine.derive(data)
        val before = AttendanceEngine.summary(data, derived, TargetWindow.TODAY)
        val result = AttendanceEngine.departure(data, derived, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, result.status)
        assertEquals(185.0, result.remainingMinutes, 0.0)
        assertEquals(at("15:05"), result.creditedTargetAt)
        assertEquals(at("15:00"), result.estimatedExitAt)
        assertEquals(175.0, before.creditedMinutes, 0.0)
        assertEquals(before, AttendanceEngine.summary(data, derived, TargetWindow.TODAY))
        assertTrue(derived.intervals.all { it.end <= data.now })
    }

    @Test fun departureDuringArrivalDelayWaitsForCreditStart() {
        val data = input(now = at("09:03"), policy = Policy(targetMinutesPerDay = 10))
        val derived = AttendanceEngine.derive(data)
        assertEquals(0.0, AttendanceEngine.summary(data, derived, TargetWindow.TODAY).creditedMinutes, 0.0)
        val result = AttendanceEngine.departure(data, derived, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, result.status)
        assertEquals(at("09:15"), result.creditedTargetAt)
        assertEquals(at("09:10"), result.estimatedExitAt)
    }
    @Test fun repairedSameDayGapKeepsOldMinutesUncreditedAndProjectsFromCurrentPresence() {
        val data = input(listOf(enter("09:00", "old"),
            RawEvent("fix", "a", Transition.PRESENCE, at("10:00")),
            enter("10:02", "normal")), now = at("10:30"), policy = Policy(targetMinutesPerDay = 60))
        val derived = AttendanceEngine.derive(data)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in derived.sessions.first().reviewReasons)
        assertEquals(25.0, AttendanceEngine.summary(data, derived, TargetWindow.TODAY).creditedMinutes, 0.0)
        val projected = AttendanceEngine.departure(data, derived, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, projected.status)
        assertEquals(at("11:05"), projected.creditedTargetAt)
        assertEquals(at("11:00"), projected.estimatedExitAt)
        val anomalous = data.copy(events = data.events + enter("10:03", "repeat"))
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(anomalous))
    }

    @Test fun lateArrivalCannotUseTomorrowToMeetTodayTarget() {
        val data = input(listOf(enter("23:58")), now = at("23:59"), policy = Policy(targetMinutesPerDay = 10))
        assertSuppressed(DepartureStatus.UNREACHABLE_IN_WINDOW, estimate(data))
    }

    @Test fun graceCanPermitLeavingNowWithoutClaimingTargetAlreadyMet() {
        val result = estimate(input(now = at("15:00")))
        assertEquals(DepartureStatus.ESTIMATED, result.status)
        assertEquals(5.0, result.remainingMinutes, 0.0)
        assertEquals(at("15:00"), result.estimatedExitAt)
        assertEquals(at("15:05"), result.creditedTargetAt)
    }

    @Test fun cleanAlreadyMetTargetRequestsNoAdditionalTimeWithNoExitPrediction() {
        val result = estimate(input(listOf(enter(), exit("15:05")), now = at("16:00")))
        assertSuppressed(DepartureStatus.TARGET_SATISFIED, result)
        assertEquals(0.0, result.remainingMinutes, 0.0)
    }

    @Test fun noOpenEligibleSessionCannotPredictAnExit() {
        assertSuppressed(DepartureStatus.NOT_IN_OFFICE, estimate(input(emptyList())))
        assertSuppressed(DepartureStatus.NOT_IN_OFFICE, estimate(input().copy(offices = listOf(office.copy(enabled = false)))))
        assertSuppressed(DepartureStatus.NOT_IN_OFFICE, estimate(input().copy(offices = listOf(office.copy(countsTowardAttendance = false)))))
    }

    @Test fun unknownCoverageKeepsTodayProvisionalButDoesNotSuppressItsProjection() {
        val data = input(now = at("16:00")).copy(unknownDates = setOf(day))
        assertFalse(AttendanceEngine.daily(data, AttendanceEngine.derive(data), day).hasCompleteHistory)
        assertSuppressed(DepartureStatus.TARGET_SATISFIED, estimate(data))
        assertSuppressed(DepartureStatus.INCOMPLETE_HISTORY, estimate(input().copy(historyStartDate = day), TargetWindow.ROLLING_90))
    }

    @Test fun freshInstallUsesOnlyTodayAndRetainsUncreditedArrivalAndProjectedExitGrace() {
        val data = input().copy(historyStartDate = null)
        val derived = AttendanceEngine.derive(data)
        assertEquals(DepartureStatus.ESTIMATED, AttendanceEngine.departure(data, derived, TargetWindow.TODAY).status)
        assertEquals(at("15:00"), AttendanceEngine.departure(data, derived, TargetWindow.TODAY).estimatedExitAt)
        assertEquals(175.0, AttendanceEngine.daily(data, derived, day).creditedMinutes, 0.0)
        assertSuppressed(DepartureStatus.INCOMPLETE_HISTORY, AttendanceEngine.departure(data, derived, TargetWindow.ROLLING_30))
        val zeroGrace = data.copy(offices = listOf(office.copy(entryGraceMinutes = 0, exitGraceMinutes = 0)))
        assertEquals(at("15:00"), estimate(zeroGrace).estimatedExitAt)
        assertEquals(180.0, AttendanceEngine.daily(zeroGrace, AttendanceEngine.derive(zeroGrace), day).creditedMinutes, 0.0)
        val unknownYesterday = data.copy(unknownDates = setOf(day.minusDays(1)))
        assertEquals(at("15:00"), estimate(unknownYesterday).estimatedExitAt)
    }

    @Test fun freshInstallRecomputesAtNewNowAndAcrossPolicyMidnight() {
        val data = input(listOf(enter("23:00")), now = at("23:30"), policy = Policy(targetMinutesPerDay = 60, maxOpenSessionHours = 24))
            .copy(historyStartDate = null)
        assertSuppressed(DepartureStatus.UNREACHABLE_IN_WINDOW, estimate(data))
        val tomorrow = data.copy(now = at("00:30", day.plusDays(1)))
        assertEquals(DepartureStatus.ESTIMATED, estimate(tomorrow).status)
        assertEquals(at("00:55", day.plusDays(1)), estimate(tomorrow).estimatedExitAt)
        assertEquals(DepartureStatus.ESTIMATED, estimate(tomorrow.copy(now = at("00:35", day.plusDays(1)))).status)
    }

    @Test fun unrelatedPriorDayOrphanAndInvalidEventDoNotBlockToday() {
        val yesterday = day.minusDays(1)
        val data = input(listOf(
            RawEvent("old-invalid", "missing-office", Transition.EXIT, at("10:00", yesterday)),
            enter(),
        )).copy(corrections = listOf(Correction("old-orphan", "session:absent",
            at("09:00", yesterday), at("10:00", yesterday), at("11:00", yesterday))))
        assertEquals(DepartureStatus.ESTIMATED, estimate(data).status)
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(data, TargetWindow.ROLLING_30))
        val currentInvalid = data.copy(events = data.events + RawEvent("future", "a", Transition.EXIT, at("17:00")))
        val blocked = estimate(currentInvalid)
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, blocked)
        assertTrue(ReviewReason.FUTURE_EVENT in blocked.reviewReasons)
    }

    @Test fun repeatedEnterAmbiguityPrecedesTargetMet() {
        val result = estimate(input(listOf(enter(), enter("10:00", "repeat")), now = at("16:00")))
        assertEquals(0.0, result.remainingMinutes, 0.0)
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, result)
    }

    @Test fun simultaneousOpenOfficesSuppressPredictionEvenAfterTargetMet() {
        val data = input(listOf(enter(), enter("10:00", "other", "b")), now = at("16:00"))
            .copy(offices = listOf(office, office.copy(id = "b")))
        assertSuppressed(DepartureStatus.OVERLAPPING_SESSIONS, estimate(data))
    }

    @Test fun resolvedDuplicateObservationDoesNotSuppressAValidEstimate() {
        assertEquals(DepartureStatus.ESTIMATED, estimate(input(listOf(enter(), enter(id = "copy")))).status)
    }

    @Test fun malformedMissingOrStaleBoundsRemainReviewable() {
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(input(listOf(exit("08:00"), enter()))))
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(input(listOf(enter("01:00")), now = at("20:00"))))
        val futureClock = input(listOf(enter(), exit("17:00")), now = at("16:00"))
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(futureClock))
        val badManual = input().copy(manualSessions = listOf(ManualSession("bad", "a", at("11:00"), at("10:00"), at("11:00"))))
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(badManual))
    }

    @Test fun explicitCorrectionCanResolveBoundsWithoutRewritingEvents() {
        val data = input(listOf(enter(), enter("10:00", "repeat"))).copy(corrections =
            listOf(Correction("fixed", "session:in", at("09:00"), null, at("11:00"))))
        assertEquals(DepartureStatus.ESTIMATED, estimate(data).status)
        assertEquals(2, data.events.size)
    }

    @Test fun closedOverlappingOfficeDoesNotDoubleCountPriorCredit() {
        val data = input(listOf(enter(), enter("09:30", "bin", "b"), exit("11:00", "bout", "b")))
            .copy(offices = listOf(office, office.copy(id = "b", exitGraceMinutes = 120)))
        val result = estimate(data)
        assertEquals(185.0, result.remainingMinutes, 0.0)
        assertEquals(at("15:00"), result.estimatedExitAt)
    }

    @Test fun priorCreditAndWeeklyTargetBoundaryStayDistinct() {
        val prior = listOf(enter("09:00", "monin", date = day.minusDays(2)), exit("15:00", "monout", date = day.minusDays(2)),
            enter("09:00", "tuein", date = day.minusDays(1)), exit("15:00", "tueout", date = day.minusDays(1)))
        val data = input(prior + enter())
        val throughToday = estimate(data, TargetWindow.WEEK_TO_DATE)
        assertEquals(195.0, throughToday.remainingMinutes, 0.0)
        assertEquals(at("15:10"), throughToday.estimatedExitAt)
        val fullWeek = estimate(data, TargetWindow.FULL_WEEK)
        assertEquals(915.0, fullWeek.remainingMinutes, 0.0)
        assertSuppressed(DepartureStatus.UNREACHABLE_IN_WINDOW, fullWeek) // Would exceed the safe open-session horizon.
    }

    @Test fun rollingTargetsUseTheirOwnPriorCreditAndDenominator() {
        val dailyOnly = Policy(expectedWeekdays = setOf(DayOfWeek.WEDNESDAY), targetMinutesPerDay = 60)
        val data = input(policy = dailyOnly)
        val thirty = estimate(data, TargetWindow.ROLLING_30)
        val ninety = estimate(data, TargetWindow.ROLLING_90)
        assertEquals(125.0, thirty.remainingMinutes, 0.0)
        assertEquals(at("14:00"), thirty.estimatedExitAt)
        assertTrue(ninety.remainingMinutes > thirty.remainingMinutes)
    }

    @Test fun exclusionHasNoRequirementButWfhStillHasOne() {
        val holiday = input(emptyList(), policy = Policy(excludedDates = listOf(ExcludedDate(day, ExclusionReason.BANK_HOLIDAY))))
        assertSuppressed(DepartureStatus.TARGET_SATISFIED, estimate(holiday))
        assertEquals(185.0, estimate(input(policy = Policy(wfhDates = setOf(day)))).remainingMinutes, 0.0)
    }

    @Test fun targetCannotBorrowTomorrowButFullWeekCanProjectAcrossMidnight() {
        val policy = Policy(targetMinutesPerDay = 240, expectedWeekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), maxOpenSessionHours = 24)
        val data = input(listOf(enter("22:00")), now = at("23:00"), policy = policy)
        assertSuppressed(DepartureStatus.UNREACHABLE_IN_WINDOW, estimate(data))
        val week = estimate(data, TargetWindow.FULL_WEEK)
        assertEquals(DepartureStatus.ESTIMATED, week.status)
        assertEquals(at("06:00", day.plusDays(1)), week.estimatedExitAt)
        assertEquals(at("06:05", day.plusDays(1)), week.creditedTargetAt)
    }

    @Test fun mondayDoesNotReusePreviousWeekCredit() {
        val monday = day.plusDays(5)
        val data = input(listOf(enter("09:00", "previous", date = day), exit("15:00", "previousout", date = day),
            enter(date = monday)), now = at("12:00", monday))
        assertEquals(185.0, estimate(data, TargetWindow.WEEK_TO_DATE).remainingMinutes, 0.0)
    }

    @Test fun dstProjectionUsesElapsedTimeAndPolicyTimezone() {
        val spring = LocalDate.of(2026, 3, 8)
        val policy = Policy(expectedWeekdays = setOf(DayOfWeek.SUNDAY), targetMinutesPerDay = 180)
        val data = input(listOf(enter("00:30", date = spring)), now = at("01:30", spring), policy = policy)
            .copy(historyStartDate = spring)
        val result = estimate(data)
        assertEquals(at("04:30", spring), result.estimatedExitAt)
        assertEquals(at("04:35", spring), result.creditedTargetAt)
    }
}
