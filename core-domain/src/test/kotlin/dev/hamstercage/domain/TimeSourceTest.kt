package dev.hamstercage.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeSourceTest {
    @Test fun injectedInstantUsesTheRequestedCalendarZone() {
        val source = TimeSource { Instant.parse("2026-09-24T00:30:00Z") }
        assertEquals(LocalDate.of(2026, 9, 23), source.localDate(ZoneId.of("America/New_York")))
        assertEquals(LocalDate.of(2026, 9, 24), source.localDate(ZoneId.of("UTC")))
    }

    @Test fun daylightSavingBoundaryUsesTheSuppliedInstant() {
        val source = TimeSource { Instant.parse("2026-03-08T07:30:00Z") }
        assertEquals(LocalDate.of(2026, 3, 8), source.localDate(ZoneId.of("America/New_York")))
        assertEquals(LocalDate.of(2026, 3, 7), source.localDate(ZoneId.of("America/Los_Angeles")))
    }
}
