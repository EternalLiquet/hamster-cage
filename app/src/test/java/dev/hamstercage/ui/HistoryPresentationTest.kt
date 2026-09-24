package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HistoryPresentationTest {
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val today = LocalDate.of(2026, 9, 23)
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
    private fun input() = AttendanceInput(listOf(office), emptyList(), now = now, historyStartDate = today.minusYears(10))
    private fun days(input: AttendanceInput) = historyDays(input, AttendanceEngine.derive(input))

    @Test fun expectedZeroDaysRemainVisibleAndUnknownIsNotAConfirmedDeficit() {
        val day = days(input()).first()
        assertEquals(today, day.date)
        assertEquals(0.0, day.summary.creditedMinutes, 0.001)
        assertEquals(360, day.summary.requiredMinutes)
        assertEquals(-360.0, day.summary.balanceMinutes, 0.001)
        val unknown = days(input().copy(historyStartDate = null)).first()
        assertFalse(unknown.summary.hasCompleteHistory)
        assertTrue("UNKNOWN COVERAGE" in unknown.badges)
    }

    @Test fun textualHolidayWfhManualAndReviewBadgesKeepTheirDifferentMeanings() {
        val source = input().copy(
            policy = Policy(excludedDates = listOf(ExcludedDate(today, ExclusionReason.BANK_HOLIDAY)), wfhDates = setOf(today)),
            manualSessions = listOf(ManualSession("manual", office.id, now.minusSeconds(3600), null, now)),
        )
        val day = days(source).first()
        assertTrue(day.badges.containsAll(listOf("HOLIDAY", "WFH", "MANUAL", "REVIEW")))
        assertEquals(0, day.summary.requiredMinutes)
        assertEquals(60.0, day.summary.creditedMinutes, 0.001)
        assertEquals(360, days(source.copy(policy = Policy(wfhDates = setOf(today)))).first().summary.requiredMinutes)
    }

    @Test fun midnightAndDstUsePolicyTimezoneInsteadOfTheDeviceDate() {
        val source = input().copy(now = Instant.parse("2026-11-02T04:30:00Z"),
            manualSessions = listOf(ManualSession("dst", office.id, Instant.parse("2026-11-01T05:30:00Z"), Instant.parse("2026-11-01T06:30:00Z"), Instant.parse("2026-11-02T04:00:00Z"))))
        val day = days(source).first()
        assertEquals(LocalDate.of(2026, 11, 1), day.date)
        assertEquals(60.0, day.summary.creditedMinutes, 0.001)
        assertEquals(LocalDate.of(2026, 11, 2), days(source.copy(policy = Policy(zoneId = ZoneId.of("UTC")))).first().date)
    }

    @Test fun correctionAndPolicyRecomputeFromTheSameSource() {
        val source = input().copy(manualSessions = listOf(ManualSession("manual", office.id, now.minusSeconds(7200), now, now)))
        val result = AttendanceEngine.derive(source)
        val changed = source.copy(corrections = listOf(Correction("fix", result.sessions.single().id, now.minusSeconds(3600), now, now)), policy = Policy(targetMinutesPerDay = 480))
        assertEquals(120.0, days(source).first().summary.creditedMinutes, 0.001)
        assertEquals(60.0, days(changed).first().summary.creditedMinutes, 0.001)
        assertEquals(480, days(changed).first().summary.requiredMinutes)
        assertEquals(source.manualSessions, changed.manualSessions)
    }

    @Test fun largeSyntheticHistoryUsesFixedPagesAndCanReachTheOldestRecord() {
        val source = input().copy(manualSessions = (0 until 1000).map { index ->
            val end = now.minusSeconds(index * 86400L)
            ManualSession("m$index", office.id, end.minusSeconds(3600), end, now)
        })
        val result = AttendanceEngine.derive(source)
        val page = historyDays(source, result, 980)
        assertEquals(14, page.size)
        assertEquals(today.minusDays(980), page.first().date)
        assertTrue(earliestHistoryDate(source) <= today.minusDays(999))
        assertTrue(page.all { it.summary.creditedMinutes == 60.0 })
    }
}
