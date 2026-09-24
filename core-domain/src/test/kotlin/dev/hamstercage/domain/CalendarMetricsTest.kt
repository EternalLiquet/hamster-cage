package dev.hamstercage.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarMetricsTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 23) // Wednesday, synthetic fixture.
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun at(time: String, date: LocalDate = day) = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun visit(date: LocalDate = day, start: String = "09:00", end: String = "15:00", id: String = date.toString()) = listOf(
        RawEvent("$id-in", "a", Transition.ENTER, at(start, date)), RawEvent("$id-out", "a", Transition.EXIT, at(end, date)))
    private fun input(events: List<RawEvent> = emptyList(), policy: Policy = Policy(), now: Instant = at("20:00")) =
        AttendanceInput(listOf(office), events, policy = policy, now = now, historyStartDate = day.minusDays(100))
    private fun summary(data: AttendanceInput, target: TargetWindow = TargetWindow.TODAY) =
        AttendanceEngine.summary(data, AttendanceEngine.derive(data), target)

    @Test fun defaultsCountAllExpectedWeekdaysIncludingUnattendedDays() {
        val data = input(visit())
        val week = summary(data, TargetWindow.WEEK_TO_DATE)
        assertEquals(3, week.expectedWorkdays)
        assertEquals(1080, week.requiredMinutes)
        assertEquals(370.0, week.creditedMinutes, 0.0)
        assertEquals(370.0 / 3, week.averageMinutes!!, 0.00001)
        assertEquals(-710.0, week.balanceMinutes, 0.0)
    }

    @Test fun excludedDayRemovesRequirementAndPreservesActualAttendance() {
        val monday = day.minusDays(2)
        val data = input(visit(monday), Policy(excludedDates = listOf(ExcludedDate(monday, ExclusionReason.BANK_HOLIDAY))))
        val daily = AttendanceEngine.daily(data, AttendanceEngine.derive(data), monday)
        assertEquals(0, daily.requiredMinutes)
        assertNull(daily.averageMinutes)
        assertEquals(370.0, daily.creditedMinutes, 0.0)
        assertEquals(1440, summary(data, TargetWindow.FULL_WEEK).requiredMinutes)
    }

    @Test fun wfhIsALabelAndDoesNotRemoveRequirement() {
        val data = input(policy = Policy(wfhDates = setOf(day)))
        assertEquals(360, summary(data).requiredMinutes)
        assertEquals(0.0, summary(data).creditedMinutes, 0.0)
        assertEquals(1, summary(data).expectedWorkdays)
    }

    @Test fun fullWeekSeparatelyProjectsFutureRequirements() {
        val data = input()
        val throughToday = summary(data, TargetWindow.WEEK_TO_DATE)
        val projected = summary(data, TargetWindow.FULL_WEEK)
        assertEquals(1080, throughToday.requiredMinutes)
        assertEquals(1800, projected.requiredMinutes)
        assertEquals(2, projected.projectedExpectedWorkdays)
        assertEquals(3, projected.knownExpectedWorkdays)
        assertEquals(0, projected.unknownExpectedWorkdays)
        assertTrue(projected.hasCompleteHistory)
        val future = AttendanceEngine.daily(data, AttendanceEngine.derive(data), day.plusDays(1))
        assertEquals(0, future.requiredMinutes)
        assertEquals(0.0, future.creditedMinutes, 0.0)
        assertNull(future.averageMinutes)
    }

    @Test fun rolling30IncludesTodayAnd29PreviousDates() = rolling(TargetWindow.ROLLING_30, 30)
    @Test fun rolling90IncludesTodayAnd89PreviousDates() = rolling(TargetWindow.ROLLING_90, 90)
    private fun rolling(target: TargetWindow, days: Int) {
        val start = day.minusDays(days - 1L)
        val data = input(visit(start.minusDays(1)) + visit(start) + visit(day))
        val result = summary(data, target)
        assertEquals(start, result.startDate)
        assertEquals(day, result.endDate)
        assertEquals(740.0, result.creditedMinutes, 0.0)
        val expected = (0 until days).count { start.plusDays(it.toLong()).dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        assertEquals(expected, result.expectedWorkdays)
    }

    @Test fun installingTodayDoesNotShrinkHistoricalDenominator() {
        val result = summary(input().copy(historyStartDate = day), TargetWindow.ROLLING_90)
        assertTrue(result.expectedWorkdays > 60)
        assertEquals(1, result.knownExpectedWorkdays)
        assertEquals(result.expectedWorkdays - 1, result.unknownExpectedWorkdays)
        assertEquals(89, result.unknownCalendarDays)
        assertFalse(result.hasCompleteHistory)
    }

    @Test fun absentCoverageAndExplicitOutageAreUnknown() {
        val missing = summary(input().copy(historyStartDate = null))
        assertEquals(1, missing.unknownExpectedWorkdays)
        assertFalse(missing.hasCompleteHistory)
        assertFalse(summary(input().copy(unknownDates = setOf(day))).hasCompleteHistory)
    }

    @Test fun unknownWeekendCannotPretendToHaveCompleteHistory() {
        val saturday = day.plusDays(3)
        val data = input(now = at("20:00", saturday)).copy(unknownDates = setOf(saturday))
        val result = summary(data)
        assertEquals(0, result.expectedWorkdays)
        assertEquals(0, result.unknownExpectedWorkdays)
        assertEquals(1, result.unknownCalendarDays)
        assertFalse(result.hasCompleteHistory)
        assertNull(result.averageMinutes)
    }

    @Test fun unknownHolidayDoesNotChangeDenominatorButRemainsUnknown() {
        val data = input(policy = Policy(excludedDates = listOf(ExcludedDate(day, ExclusionReason.COMPANY_CLOSURE))))
            .copy(unknownDates = setOf(day))
        val result = summary(data)
        assertEquals(0, result.requiredMinutes)
        assertEquals(1, result.unknownCalendarDays)
        assertFalse(result.hasCompleteHistory)
    }

    @Test fun changedPolicyRecomputesSameRawFacts() {
        val original = input(visit())
        val updated = original.copy(policy = original.policy.copy(targetMinutesPerDay = 420))
        assertEquals(-50.0, summary(updated).balanceMinutes, 0.0)
        assertEquals(original.events, updated.events)
        val sundayPolicy = original.copy(policy = original.policy.copy(expectedWeekdays = setOf(DayOfWeek.SUNDAY)))
        assertEquals(0, summary(sundayPolicy).requiredMinutes)
        assertNull(summary(sundayPolicy).averageMinutes)
    }

    @Test fun midnightSplitsGraceUsingPolicyDayBoundary() {
        val events = listOf(RawEvent("in", "a", Transition.ENTER, at("23:00", day.minusDays(1))), RawEvent("out", "a", Transition.EXIT, at("01:00")))
        val data = input(events)
        val result = AttendanceEngine.derive(data)
        assertEquals(65.0, AttendanceEngine.daily(data, result, day.minusDays(1)).creditedMinutes, 0.0)
        assertEquals(65.0, AttendanceEngine.daily(data, result, day).creditedMinutes, 0.0)
    }

    @Test fun springAndFallDaysUseTheirActualElapsedLengths() {
        listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25).forEach { (date, hours) ->
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val data = input(now = end)
            val intervals = AttendanceResult(emptyList(), listOf(CreditedInterval(start, end, setOf("fixture"))), emptyList())
            assertEquals(hours * 60.0, AttendanceEngine.daily(data, intervals, date).creditedMinutes, 0.0)
        }
    }

    @Test fun configuredTimezoneControlsDateAttribution() {
        val events = listOf(RawEvent("in", "a", Transition.ENTER, Instant.parse("2026-09-23T00:30:00Z")),
            RawEvent("out", "a", Transition.EXIT, Instant.parse("2026-09-23T01:30:00Z")))
        val data = input(events, now = Instant.parse("2026-09-23T04:00:00Z"))
        val result = AttendanceEngine.derive(data)
        assertEquals(70.0, AttendanceEngine.daily(data, result, day.minusDays(1)).creditedMinutes, 0.0)
        assertEquals(0.0, AttendanceEngine.daily(data, result, day).creditedMinutes, 0.0)
        val utc = data.copy(policy = data.policy.copy(zoneId = ZoneId.of("UTC")))
        assertEquals(70.0, summary(utc).creditedMinutes, 0.0)
    }

    @Test fun rawObservedTotalIgnoresCorrectionsManualFactsAndGrace() {
        val data = input(visit()).copy(corrections = listOf(Correction("c", "session:$day-in", at("10:00"), at("14:00"), at("19:00"))),
            manualSessions = listOf(ManualSession("manual", "a", at("08:00"), at("18:00"), at("19:00"))))
        assertEquals(360.0, AttendanceEngine.observedDailyMinutes(data, day), 0.0)
        assertEquals(610.0, summary(data).creditedMinutes, 0.0)
    }

    @Test fun creditedInputsAreClippedToWindowAndNowThenUnioned() {
        val data = input(now = at("12:00"))
        val intervals = listOf(CreditedInterval(Instant.MIN, Instant.MAX, setOf("extreme")),
            CreditedInterval(at("09:00"), at("15:00"), setOf("overlap")))
        val result = AttendanceEngine.daily(data, AttendanceResult(emptyList(), intervals, emptyList()), day)
        assertEquals(720.0, result.creditedMinutes, 0.0)
    }

    @Test fun invalidOrOversizedPeriodsAreRejectedBeforeIteration() {
        val data = input()
        val result = AttendanceEngine.derive(data)
        listOf(day to day.minusDays(1), day to day.plusDays(36601), LocalDate.MAX to LocalDate.MAX).forEach { (start, end) ->
            assertThrows(IllegalArgumentException::class.java) { AttendanceEngine.period(data, result, start, end) }
        }
    }
}
