package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DashboardPresentationTest {
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private fun data(events: List<RawEvent> = emptyList()) = AttendanceInput(listOf(office), events,
        now = now, historyStartDate = LocalDate.of(2026, 1, 1))
    private fun enter(id: String = "in", officeId: String = "a") = RawEvent(id, officeId, Transition.ENTER, now.minusSeconds(3600))
    private fun state(input: AttendanceInput, ready: Boolean = true) = dashboardPresence(input, AttendanceEngine.derive(input), ready)

    @Test fun registrationWithoutObservationNeverClaimsOutside() {
        assertEquals("Office state unknown", state(data()).label)
    }
    @Test fun healthyOpenSessionNamesOfficeButTrackingLossDominatesIt() {
        assertEquals("In Synthetic office", state(data(listOf(enter()))).label)
        assertEquals("Office state unknown", state(data(listOf(enter())), false).label)
        val stale = data(listOf(enter())).copy(now = now.plusSeconds(20 * 3600))
        assertEquals("Office state unknown", state(stale, false).label)
        assertTrue(state(stale, false).needsReview)
    }
    @Test fun outsideRequiresCleanCurrentDayExitAndHealthyTracking() {
        val input = data(listOf(enter(), RawEvent("out", "a", Transition.EXIT, now.minusSeconds(60))))
        assertEquals("Outside office", state(input).label)
        assertEquals("Office state unknown", state(input, false).label)
        assertEquals("Office state unknown", state(input.copy(now = now.plusSeconds(24 * 3600))).label)
    }
    @Test fun transientBoundaryDoesNotClaimConfirmedDeparture() {
        val input = data(listOf(RawEvent("in", "a", Transition.ENTER, now.minusSeconds(40)),
            RawEvent("out", "a", Transition.EXIT, now)))
        assertEquals("Needs review", state(input).label)
        assertTrue(ReviewReason.TRANSIENT_BOUNDARY in AttendanceEngine.derive(input).sessions.single().reviewReasons)
    }
    @Test fun multipleOpenOfficesAndMalformedBoundsNeedReview() {
        val overlapping = data(listOf(enter(), enter("bin", "b"))).copy(offices = listOf(office, office.copy(id = "b")))
        assertEquals("Needs review", state(overlapping).label)
        assertEquals("Needs review", state(data(listOf(RawEvent("out", "a", Transition.EXIT, now)))).label)
    }
    @Test fun manualBoundsAreExplicitAndDisabledOfficeDoesNotClaimPresence() {
        val manual = data().copy(manualSessions = listOf(ManualSession("m", "a", now.minusSeconds(60), null, now)))
        assertEquals("Manual session active", state(manual).label)
        assertTrue(state(manual).manual)
        val disabled = data(listOf(enter())).copy(offices = listOf(office.copy(enabled = false)))
        assertEquals("Office state unknown", state(disabled).label)
        assertNull(state(disabled).sessionStarted)
    }
    @Test fun minutePrecisionDoesNotOverstateCreditOrEraseASmallDeficit() {
        assertEquals("5h 59m", minutesText(359.9))
        assertEquals("−1m", balanceText(-0.1))
        assertEquals("+1h 0m", balanceText(60.1))
    }
    @Test fun departureRoundUpDoesNotInviteLeavingBeforeTheComputedInstant() {
        val zone = ZoneId.of("UTC")
        assertEquals("4:01 PM", departureTimeText(now.plusSeconds(30), now, zone))
        assertTrue(departureTimeText(now.plusSeconds(24 * 3600), now, zone).contains("Sep 24"))
    }
    @Test fun dailyLeaveMessageNamesSafeActionForEachCommonState() {
        val zone = ZoneId.of("UTC")
        fun message(input: AttendanceInput, ready: Boolean = true): String =
            todayLeaveText(AttendanceEngine.departure(input, AttendanceEngine.derive(input), TargetWindow.TODAY), ready, input.now, zone)
        assertEquals("You can leave at 9:00 PM", message(data(listOf(enter())).copy(historyStartDate = null)))
        assertEquals("Confirm office detection to see a leave time", message(data(listOf(enter())), false))
        assertEquals("Start an eligible office session to see a leave time", message(data()))
        assertEquals("You can leave now", message(data(listOf(enter().copy(at = now.minusSeconds(7 * 3600))))))
        val overlapping = data(listOf(enter(), enter("bin", "b"))).copy(offices = listOf(office, office.copy(id = "b")))
        assertEquals("Review overlapping active sessions in History", message(overlapping))
        val future = data(listOf(enter(), RawEvent("future", "a", Transition.EXIT, now.plusSeconds(60))))
        assertTrue(message(future).contains("Check the device clock"))
        val stale = data(listOf(enter())).copy(now = now.plusSeconds(24 * 3600))
        assertTrue(message(stale).contains("safe length"))
        val unreachable = data(listOf(RawEvent("late", "a", Transition.ENTER, Instant.parse("2026-09-24T03:58:00Z"))))
            .copy(now = Instant.parse("2026-09-24T03:59:00Z"))
        assertTrue(message(unreachable).contains("Review the target in Settings"))
    }
}
