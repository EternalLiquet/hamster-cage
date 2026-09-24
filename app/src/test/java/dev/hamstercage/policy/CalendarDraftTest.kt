package dev.hamstercage.policy

import dev.hamstercage.domain.ExclusionReason
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CalendarDraftTest {
    @Test fun datesAreStrictAndFutureDatesRemainAvailable() {
        assertEquals(LocalDate.of(2028, 2, 29), calendarDate("2028-02-29"))
        assertEquals(LocalDate.of(2030, 12, 25), calendarDate(" 2030-12-25 "))
        listOf("2026-02-29", "2026-13-01", "not-a-date", "2026-09-23T12:00:00Z").forEach {
            try { calendarDate(it); fail("Expected invalid date") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun reasonsAndBoundedNotesArePreservedWithoutInventedFeed() {
        ExclusionReason.entries.forEach { reason ->
            val value = excludedDate("2026-09-23", reason, " Synthetic note ")
            assertEquals(reason, value.reason); assertEquals("Synthetic note", value.note)
        }
        assertEquals(2000, excludedDate("2026-09-23", ExclusionReason.PTO, "a".repeat(2000)).note.length)
        try { excludedDate("2026-09-23", ExclusionReason.PTO, "a".repeat(2001)); fail("Expected note bound") }
        catch (_: IllegalArgumentException) { }
    }
}
