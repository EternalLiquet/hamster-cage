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
        assertEquals("Using times you entered", state(manual).label)
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
    private val zone = ZoneId.of("UTC")
    private fun leave(input: AttendanceInput, ready: Boolean = true, confident: Boolean = ready && AttendanceEngine.daily(
        input, AttendanceEngine.derive(input), input.now.atZone(zone).toLocalDate()).hasCompleteHistory): String =
        todayLeaveText(AttendanceEngine.departure(input, AttendanceEngine.derive(input), TargetWindow.TODAY), ready,
            input.now, zone, confident)
    private val jargon = Regex("(?i)\\b(enter|exit|geofence|bounds?|engine|eligible|observation|coverage|provisional|session)\\b")

    @Test fun dailyLeaveMessageNamesSafeActionForEachCommonStateInEverydayWords() {
        val messages = mutableListOf<String>()
        fun check(expected: String, actual: String) { assertEquals(expected, actual); messages += actual }
        fun checkContains(expected: String, actual: String) { assertTrue(actual, actual.contains(expected)); messages += actual }
        check("You can leave at 9:00 PM", leave(data(listOf(enter()))))
        check("No leave time yet: your location isn't confirmed. Check office setup.", leave(data(listOf(enter())), false))
        check("A leave time appears once you're at the office.", leave(data()))
        check("Goal met. You can leave now.", leave(data(listOf(enter().copy(at = now.minusSeconds(7 * 3600))))))
        val overlapping = data(listOf(enter(), enter("bin", "b"))).copy(offices = listOf(office, office.copy(id = "b")))
        check("Two office visits overlap. Fix them in History to see a leave time.", leave(overlapping))
        val future = data(listOf(enter(), RawEvent("future", "a", Transition.EXIT, now.plusSeconds(60))))
        checkContains("Check your phone's clock", leave(future))
        val stale = data(listOf(enter())).copy(now = now.plusSeconds(24 * 3600))
        checkContains("unusually long", leave(stale))
        val unreachable = data(listOf(RawEvent("late", "a", Transition.ENTER, Instant.parse("2026-09-24T03:58:00Z"))))
            .copy(now = Instant.parse("2026-09-24T03:59:00Z"))
        checkContains("Check your goal in Settings", leave(unreachable))
        for (message in messages) assertFalse(message, jargon.containsMatchIn(message))
    }

    @Test fun uncertainTimeNeverBecomesAConfidentLeaveInstruction() {
        // Missing history: the projection stays useful but is visibly an estimate.
        val unknownHistory = data(listOf(enter())).copy(historyStartDate = null)
        assertEquals("Estimated leave time: about 9:00 PM. Check today's timeline.", leave(unknownHistory))
        val metUnknown = data(listOf(enter().copy(at = now.minusSeconds(7 * 3600)))).copy(historyStartDate = null)
        assertEquals("Goal may be met. Check today's timeline before you leave.", leave(metUnknown))
        // Detection not confirmed: no time at all, and a met goal is not confirmed either.
        assertFalse(leave(data(listOf(enter())), ready = false).contains("leave at"))
        assertEquals("Goal may be met. Check today's timeline before you leave.",
            leave(data(listOf(enter().copy(at = now.minusSeconds(7 * 3600)))), ready = false))
        for (text in listOf(leave(unknownHistory), leave(metUnknown))) assertFalse(text, text.startsWith("You can leave"))
    }

    @Test fun creditAndRemainingLabelsQualifyUncertainNumbersWithoutChangingThem() {
        val input = data(listOf(enter().copy(at = now.minusSeconds(2 * 3600))))
        val daily = AttendanceEngine.daily(input, AttendanceEngine.derive(input), input.now.atZone(zone).toLocalDate())
        // The numbers are the engine's, unchanged; only their labels change.
        assertEquals("1h 55m", minutesText(daily.creditedMinutes))
        assertEquals("Counted toward today's goal", todayCreditNote(historyComplete = true, liveUnconfirmed = false))
        assertEquals("Estimate · part of today wasn't tracked", todayCreditNote(historyComplete = false, liveUnconfirmed = false))
        assertEquals("Estimate · we can't confirm you're still there", todayCreditNote(historyComplete = true, liveUnconfirmed = true))
        assertEquals("4h 5m", todayRemainingText(daily, liveUnconfirmed = false))
        assertEquals("About 4h 5m", todayRemainingText(daily, liveUnconfirmed = true))
        assertEquals("Unknown", todayRemainingText(daily.copy(unknownCalendarDays = 1), liveUnconfirmed = false))
        assertEquals("None, goal met", todayRemainingText(daily.copy(balanceMinutes = 0.0), liveUnconfirmed = false))
        assertEquals("None, goal met (+30m)", todayRemainingText(daily.copy(balanceMinutes = 30.4), liveUnconfirmed = false))
        assertEquals("1m", todayRemainingText(daily.copy(balanceMinutes = -0.2), liveUnconfirmed = false))
        for (text in listOf(todayCreditNote(true, false), todayCreditNote(false, false), todayCreditNote(true, true)))
            assertFalse(text, jargon.containsMatchIn(text))
    }

    @Test fun earlierOrphanExitKeepsAuditReviewWithoutHidingCurrentOfficeOrLeaveTime() {
        val input = data(listOf(RawEvent("orphan", "a", Transition.EXIT, now.minusSeconds(12 * 3600)),
            enter()))
        val result = AttendanceEngine.derive(input)
        assertTrue(dashboardPresence(input, result, true).needsReview)
        assertEquals("In Synthetic office", dashboardPresence(input, result, true).label)
        // An unrelated earlier review still shows the time, but only as an estimate.
        assertEquals("Estimated leave time: about 9:00 PM. Check today's timeline.", todayLeaveText(
            AttendanceEngine.departure(input, result, TargetWindow.TODAY), true, now, ZoneId.of("UTC"), confident = false))
    }
}
