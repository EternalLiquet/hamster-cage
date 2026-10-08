package dev.hamstercage.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutsideCheckCreditTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2030, 1, 7)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun at(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun event(id: String, transition: Transition, time: String) = RawEvent(id, "a", transition, at(time))
    private fun derive(events: List<RawEvent>, now: String, recovery: Set<String> = emptySet(),
        unsafe: Set<String> = emptySet()) = AttendanceEngine.derive(AttendanceInput(listOf(office), events,
        policy = Policy(zoneId = zone, shortGapMinutes = 0), now = at(now), historyStartDate = day,
        recoveryPresenceIds = recovery, unsafeRecoveryPresenceIds = unsafe))

    @Test fun outsideCheckCreditsThroughLastInsideObservationOnly() {
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "11:00"), event("outside", Transition.ABSENCE, "12:00")), "12:30")
        val visit = result.sessions.single()
        assertEquals(at("12:00"), visit.end)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in visit.reviewReasons)
        assertEquals(at("11:00"), result.intervals.single().end)
        assertEquals(115.0, result.intervals.single().minutes, 0.0001)
    }

    @Test fun outsideCheckAfterOnlyAnEnterStillEarnsNothing() {
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("outside", Transition.ABSENCE, "12:00")), "12:30")
        assertTrue(result.intervals.isEmpty())
    }

    @Test fun recoverySplitSegmentStillEarnsNothing() {
        // A recovery presence splits a pre-outage visit; the old segment stays uncredited even
        // though it contained an inside observation, because coverage lapsed during it.
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("recovered", Transition.PRESENCE, "11:00")),
            "11:30", recovery = setOf("recovered"))
        val old = result.sessions.first()
        assertTrue(ReviewReason.UNCONFIRMED_GAP in old.reviewReasons)
        assertTrue(result.intervals.none { it.sessionIds.contains(old.id) })
    }

    @Test fun unsafeRecoveryAfterExitStillEarnsNothingForTheOldVisit() {
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("out", Transition.EXIT, "11:00"),
            event("recovered", Transition.PRESENCE, "11:10")), "11:30",
            recovery = setOf("recovered"), unsafe = setOf("recovered"))
        val old = result.sessions.first()
        assertTrue(ReviewReason.UNCONFIRMED_GAP in old.reviewReasons)
        assertTrue(result.intervals.none { it.sessionIds.contains(old.id) })
    }
}
