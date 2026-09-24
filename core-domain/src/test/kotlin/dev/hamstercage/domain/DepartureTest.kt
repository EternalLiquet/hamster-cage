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
        assertEquals(175.0, result.remainingMinutes, 0.0)
        assertEquals(at("14:55"), result.creditedTargetAt)
        assertEquals(at("14:50"), result.estimatedExitAt)
        assertEquals(185.0, before.creditedMinutes, 0.0)
        assertEquals(before, AttendanceEngine.summary(data, derived, TargetWindow.TODAY))
        assertTrue(derived.intervals.all { it.end <= data.now })
    }

    @Test fun graceCanPermitLeavingNowWithoutClaimingTargetAlreadyMet() {
        val result = estimate(input(now = at("14:53")))
        assertEquals(DepartureStatus.ESTIMATED, result.status)
        assertEquals(2.0, result.remainingMinutes, 0.0)
        assertEquals(at("14:53"), result.estimatedExitAt)
        assertEquals(at("14:55"), result.creditedTargetAt)
    }

    @Test fun cleanAlreadyMetTargetRequestsNoAdditionalTimeWithNoExitPrediction() {
        val result = estimate(input(listOf(enter(), exit("15:00")), now = at("16:00")))
        assertSuppressed(DepartureStatus.TARGET_SATISFIED, result)
        assertEquals(0.0, result.remainingMinutes, 0.0)
    }

    @Test fun noOpenEligibleSessionCannotPredictAnExit() {
        assertSuppressed(DepartureStatus.NOT_IN_OFFICE, estimate(input(emptyList())))
        assertSuppressed(DepartureStatus.NOT_IN_OFFICE, estimate(input().copy(offices = listOf(office.copy(enabled = false)))))
        assertSuppressed(DepartureStatus.NOT_IN_OFFICE, estimate(input().copy(offices = listOf(office.copy(countsTowardAttendance = false)))))
    }

    @Test fun incompleteHistorySuppressesEvenAnApparentlyMetTarget() {
        val data = input(now = at("16:00")).copy(unknownDates = setOf(day))
        assertSuppressed(DepartureStatus.INCOMPLETE_HISTORY, estimate(data))
        assertSuppressed(DepartureStatus.INCOMPLETE_HISTORY, estimate(input().copy(historyStartDate = day), TargetWindow.ROLLING_90))
    }

    @Test fun repeatedEnterAmbiguityPrecedesTargetMet() {
        val result = estimate(input(listOf(enter(), enter("10:00", "repeat")), now = at("16:00")))
        assertEquals(0.0, result.remainingMinutes, 0.0)
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, result)
    }

    @Test fun simultaneousOpenOfficesSuppressPredictionEvenAfterTargetMet() {
        val data = input(listOf(enter(), enter("10:00", "other", "b")), now = at("16:00"))
            .copy(offices = listOf(office, office.copy(id = "b")))
        assertSuppressed(DepartureStatus.NEEDS_REVIEW, estimate(data))
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
        assertEquals(175.0, result.remainingMinutes, 0.0)
        assertEquals(at("14:50"), result.estimatedExitAt)
    }

    @Test fun priorCreditAndWeeklyTargetBoundaryStayDistinct() {
        val prior = listOf(enter("09:00", "monin", date = day.minusDays(2)), exit("15:00", "monout", date = day.minusDays(2)),
            enter("09:00", "tuein", date = day.minusDays(1)), exit("15:00", "tueout", date = day.minusDays(1)))
        val data = input(prior + enter())
        val throughToday = estimate(data, TargetWindow.WEEK_TO_DATE)
        assertEquals(155.0, throughToday.remainingMinutes, 0.0)
        assertEquals(at("14:30"), throughToday.estimatedExitAt)
        val fullWeek = estimate(data, TargetWindow.FULL_WEEK)
        assertEquals(875.0, fullWeek.remainingMinutes, 0.0)
        assertSuppressed(DepartureStatus.UNREACHABLE_IN_WINDOW, fullWeek) // Would exceed the safe open-session horizon.
    }

    @Test fun rollingTargetsUseTheirOwnPriorCreditAndDenominator() {
        val dailyOnly = Policy(expectedWeekdays = setOf(DayOfWeek.WEDNESDAY), targetMinutesPerDay = 60)
        val data = input(policy = dailyOnly)
        val thirty = estimate(data, TargetWindow.ROLLING_30)
        val ninety = estimate(data, TargetWindow.ROLLING_90)
        assertEquals(115.0, thirty.remainingMinutes, 0.0)
        assertEquals(at("13:50"), thirty.estimatedExitAt)
        assertTrue(ninety.remainingMinutes > thirty.remainingMinutes)
    }

    @Test fun exclusionHasNoRequirementButWfhStillHasOne() {
        val holiday = input(emptyList(), policy = Policy(excludedDates = listOf(ExcludedDate(day, ExclusionReason.BANK_HOLIDAY))))
        assertSuppressed(DepartureStatus.TARGET_SATISFIED, estimate(holiday))
        assertEquals(175.0, estimate(input(policy = Policy(wfhDates = setOf(day)))).remainingMinutes, 0.0)
    }

    @Test fun targetCannotBorrowTomorrowButFullWeekCanProjectAcrossMidnight() {
        val policy = Policy(targetMinutesPerDay = 240, expectedWeekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), maxOpenSessionHours = 24)
        val data = input(listOf(enter("22:00")), now = at("23:00"), policy = policy)
        assertSuppressed(DepartureStatus.UNREACHABLE_IN_WINDOW, estimate(data))
        val week = estimate(data, TargetWindow.FULL_WEEK)
        assertEquals(DepartureStatus.ESTIMATED, week.status)
        assertEquals(at("05:50", day.plusDays(1)), week.estimatedExitAt)
        assertEquals(at("05:55", day.plusDays(1)), week.creditedTargetAt)
    }

    @Test fun mondayDoesNotReusePreviousWeekCredit() {
        val monday = day.plusDays(5)
        val data = input(listOf(enter("09:00", "previous", date = day), exit("15:00", "previousout", date = day),
            enter(date = monday)), now = at("12:00", monday))
        assertEquals(175.0, estimate(data, TargetWindow.WEEK_TO_DATE).remainingMinutes, 0.0)
    }

    @Test fun dstProjectionUsesElapsedTimeAndPolicyTimezone() {
        val spring = LocalDate.of(2026, 3, 8)
        val policy = Policy(expectedWeekdays = setOf(DayOfWeek.SUNDAY), targetMinutesPerDay = 180)
        val data = input(listOf(enter("00:30", date = spring)), now = at("01:30", spring), policy = policy)
            .copy(historyStartDate = spring)
        val result = estimate(data)
        assertEquals(at("04:20", spring), result.estimatedExitAt)
        assertEquals(at("04:25", spring), result.creditedTargetAt)
    }
}
