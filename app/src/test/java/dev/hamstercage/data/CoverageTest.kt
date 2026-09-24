package dev.hamstercage.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageTest {
    @Test fun unresolvedMondayToWednesdayGapIncludesTuesdayBeforeRecovery() {
        val unknown = unknownCoverageDates(setOf("2026-09-21", "2026-09-23"), "2026-09-21", LocalDate.parse("2026-09-23"), emptySet())
        assertEquals(setOf("2026-09-21", "2026-09-22", "2026-09-23").map(LocalDate::parse).toSet(), unknown)
    }

    @Test fun explicitPastDayReviewDoesNotMarkTodayOrInterveningDatesKnown() {
        val unknown = unknownCoverageDates(setOf("2026-09-21", "2026-09-23"), "2026-09-21", LocalDate.parse("2026-09-23"), setOf("2026-09-21"))
        assertEquals(setOf(LocalDate.parse("2026-09-22"), LocalDate.parse("2026-09-23")), unknown)
    }

    @Test fun absentOrFutureGapDoesNotInventUnknownDates() {
        assertEquals(emptySet<LocalDate>(), unknownCoverageDates(emptySet(), null, LocalDate.parse("2026-09-23"), emptySet()))
        assertEquals(emptySet<LocalDate>(), unknownCoverageDates(emptySet(), "2026-09-24", LocalDate.parse("2026-09-23"), emptySet()))
    }
}
