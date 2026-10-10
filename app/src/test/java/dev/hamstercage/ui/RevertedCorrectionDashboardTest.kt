package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** #136: Today reads the same after a correction is reverted as it did before the correction. Synthetic data. */
class RevertedCorrectionDashboardTest {
    private val zone = ZoneId.of("UTC")
    private val a = Office("a", "Synthetic office A", 0.0, 0.0)
    private val b = Office("b", "Synthetic office B", 0.0, 0.0)
    private fun at(time: String) = Instant.parse("2026-09-25T${time}:00Z")
    private fun event(id: String, transition: Transition, time: String, officeId: String = "a") =
        RawEvent(id, officeId, transition, at(time))
    private fun input(now: String, target: Int, vararg events: RawEvent) = AttendanceInput(listOf(a, b), events.toList(),
        policy = Policy(zoneId = zone, targetMinutesPerDay = target), now = at(now), historyStartDate = LocalDate.of(2026, 9, 1))
    private fun editThenRevert(sessionId: String, start: String) = listOf(
        Correction("edit", sessionId, at(start), null, at("08:00"), appendSequence = 1),
        Correction("undo", sessionId, at(start), null, at("08:01"), revertToOriginal = true, appendSequence = 2))

    /** Everything Today shows from the domain, composed as the Dashboard composes it. */
    private fun today(input: AttendanceInput, ready: Boolean = true): List<Any?> {
        val result = AttendanceEngine.derive(input)
        val presence = dashboardPresence(input, result, ready)
        val daily = AttendanceEngine.daily(input, result, input.now.atZone(zone).toLocalDate())
        val liveUnconfirmed = !ready && presence.sessionStarted != null
        val estimate = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
        return listOf(presence, minutesText(daily.creditedMinutes), todayCreditNote(daily.hasCompleteHistory, liveUnconfirmed),
            todayRemainingText(daily, liveUnconfirmed),
            todayLeaveText(estimate, ready, input.now, zone, todayLeaveConfident(input, result, ready))) +
            TargetWindow.entries.map { departureTargetText(AttendanceEngine.departure(input, result, it), ready, input.now, zone) }
    }

    @Test fun revertedVisitBeforeAnotherOfficeDoesNotClaimGoalMet() {
        val baseline = input("12:00", 120, event("a-in", Transition.ENTER, "09:00"), event("a-out", Transition.EXIT, "10:00"),
            event("b-in", Transition.ENTER, "10:30", "b"), event("b-out", Transition.EXIT, "11:00", "b"))
            .copy(rejectedExitIds = setOf("a-out"))
        val reverted = baseline.copy(corrections = editThenRevert("session:a-in", "09:00"))
        for (ready in listOf(true, false)) assertEquals(today(baseline, ready), today(reverted, ready))
        val leave = today(reverted)[4] as String
        assertFalse(leave, leave.contains("Goal met"))
        assertEquals("1h 35m", today(reverted)[3])
    }

    @Test fun revertedVisitWithPendingExitKeepsItsCappedCountAndPresence() {
        val baseline = input("11:10", 116, event("in", Transition.ENTER, "09:00"), event("maybe", Transition.EXIT, "11:00"))
            .copy(candidateExitIds = setOf("maybe"), unconfirmedExitIds = setOf("maybe"))
        val reverted = baseline.copy(corrections = editThenRevert("session:in", "09:00"))
        for (ready in listOf(true, false)) assertEquals(today(baseline, ready), today(reverted, ready))
        // Credit stays capped at the candidate, so a minute is still needed, and the restored
        // visit is not labelled as times the user entered.
        assertEquals("1h 55m", today(reverted)[1])
        assertEquals("1m", today(reverted)[3])
        assertFalse((today(reverted)[0] as DashboardPresence).manual)
        assertEquals("Checking whether you've left", (today(reverted)[0] as DashboardPresence).label)
    }

    @Test fun activeCorrectionStillReadsAsTimesYouEntered() {
        val base = input("11:10", 116, event("in", Transition.ENTER, "09:00"))
        val edited = base.copy(corrections = listOf(Correction("edit", "session:in", at("09:00"), null, at("10:00"))))
        assertTrue((today(edited)[0] as DashboardPresence).manual)
        assertFalse((today(base)[0] as DashboardPresence).manual)
    }
}
