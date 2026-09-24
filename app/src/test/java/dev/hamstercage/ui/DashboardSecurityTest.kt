package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class DashboardSecurityTest {
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val office = Office("synthetic", "Synthetic office", 0.125, -0.25)
    @Test fun corruptControlLabelsCannotSpoofPresenceAndLargeLabelsAreBounded() {
        val label = "\u202e\u0000Synthetic\n office\u2066"
        val input = AttendanceInput(listOf(office.copy(name = label)), listOf(RawEvent("enter", office.id, Transition.ENTER, now.minusSeconds(60))), now = now)
        assertEquals("In Synthetic office", dashboardPresence(input, AttendanceEngine.derive(input), true).label)
        assertEquals(120, dashboardOfficeName("a".repeat(100_000)).length)
        assertEquals("configured office", dashboardOfficeName("\u202e\u0000"))
        assertEquals(label, input.offices.single().name) // Display sanitation never changes source facts.
    }
    @Test fun conflictingExitAndFutureEvidenceCannotClaimHealthyOutsidePresence() {
        val input = AttendanceInput(listOf(office), listOf(
            RawEvent("collision", office.id, Transition.ENTER, now.minusSeconds(120)),
            RawEvent("collision", office.id, Transition.EXIT, now.minusSeconds(60)),
            RawEvent("future", office.id, Transition.EXIT, now.plusSeconds(60))), now = now)
        val result = AttendanceEngine.derive(input)
        assertTrue(result.intervals.isEmpty())
        assertEquals("Needs review", dashboardPresence(input, result, true).label)
        assertEquals("Office state unknown", dashboardPresence(input, result, false).label)
        TargetWindow.entries.forEach { assertEquals(DepartureStatus.INCOMPLETE_HISTORY, AttendanceEngine.departure(input, result, it).status) }
    }
    @Test fun nonFiniteCoordinatesAreRejectedBeforeReachingDisplayOrCapture() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 91.0).forEach { latitude ->
            var rejected = false
            try { office.copy(latitude = latitude) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }
}
