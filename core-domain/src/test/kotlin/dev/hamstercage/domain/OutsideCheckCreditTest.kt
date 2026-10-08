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

    @Test fun recoverySplitPreservesObservedPrefixOfOpenVisit() {
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("recovered", Transition.PRESENCE, "11:00")),
            "11:30", recovery = setOf("recovered"))
        val old = result.sessions.first()
        assertTrue(ReviewReason.UNCONFIRMED_GAP in old.reviewReasons)
        val oldCredit = result.intervals.single { old.id in it.sessionIds }
        assertEquals(at("09:05"), oldCredit.start)
        assertEquals(at("10:00"), oldCredit.end)
        assertEquals(at("11:05"), result.intervals.last().start)
        assertTrue(result.intervals.none { it.start < at("11:00") && it.end > at("10:00") })
    }

    @Test fun unsafeRecoveryAfterExitPreservesObservedPrefixOfOldVisit() {
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("out", Transition.EXIT, "11:00"),
            event("recovered", Transition.PRESENCE, "11:10")), "11:30",
            recovery = setOf("recovered"), unsafe = setOf("recovered"))
        val old = result.sessions.first()
        assertTrue(ReviewReason.UNCONFIRMED_GAP in old.reviewReasons)
        val oldCredit = result.intervals.single { old.id in it.sessionIds }
        assertEquals(at("09:05"), oldCredit.start)
        assertEquals(at("10:00"), oldCredit.end)
        assertEquals(at("11:15"), result.intervals.last().start)
        assertTrue(result.intervals.none { it.start < at("11:10") && it.end > at("10:00") })
    }

    @Test fun recoveryBeforeArrivalGraceStillEarnsNothing() {
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "09:03"),
            event("recovered", Transition.PRESENCE, "09:20")), "09:30",
            recovery = setOf("recovered"))
        assertTrue(result.intervals.none { "session:in" in it.sessionIds })
        assertEquals(at("09:25"), result.intervals.single().start)
    }

    @Test fun delayedExitCannotBackfillGapOrEraseEarlierCredit() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("out", Transition.EXIT, "11:00"),
            event("recovered", Transition.PRESENCE, "11:10"))
        val data = AttendanceInput(listOf(office), facts, policy = Policy(zoneId = zone,
            shortGapMinutes = 20), now = at("11:30"), historyStartDate = day,
            recoveryPresenceIds = setOf("recovered"), unsafeRecoveryPresenceIds = setOf("recovered"),
            delayedExitIds = setOf("out"))
        val result = AttendanceEngine.derive(data)
        assertEquals(at("10:00"), result.intervals.single { "session:in" in it.sessionIds }.end)
        assertTrue(result.intervals.none { it.reconciledGap })
        assertEquals(result, AttendanceEngine.derive(data.copy(events = facts.reversed())))
    }

    @Test fun shortGapPolicyCannotBridgeUnknownTailAfterOutsideCheck() {
        val facts = listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("outside", Transition.ABSENCE, "11:00"),
            event("return", Transition.ENTER, "11:10"))
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office), facts,
            policy = Policy(zoneId = zone, shortGapMinutes = 120), now = at("12:00"),
            historyStartDate = day))
        assertEquals(listOf(at("09:05") to at("10:00"), at("11:15") to at("12:00")),
            result.intervals.map { it.start to it.end })
        assertTrue(result.intervals.none { it.reconciledGap })
        assertTrue(ReviewReason.UNCONFIRMED_GAP in result.sessions.first().reviewReasons)
    }

    @Test fun laterSameOfficeEnterEndsThePrefixInsteadOfExtendingIt() {
        // A re-entry proves the user had been outside: credit cannot run through it.
        val result = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("check", Transition.PRESENCE, "10:00"), event("back", Transition.ENTER, "10:30"),
            event("late", Transition.PRESENCE, "10:45"), event("recovered", Transition.PRESENCE, "11:00")),
            "11:30", recovery = setOf("recovered"))
        assertEquals(at("10:00"), result.intervals.single { "session:in" in it.sessionIds }.end)
        val enterOnly = derive(listOf(event("in", Transition.ENTER, "09:00"),
            event("back", Transition.ENTER, "10:30"), event("recovered", Transition.PRESENCE, "11:00")),
            "11:30", recovery = setOf("recovered"))
        assertTrue(enterOnly.intervals.none { "session:in" in it.sessionIds })
    }

    @Test fun otherOfficeEvidenceEndsThePrefix() {
        val b = office.copy(id = "b", name = "Synthetic second office", latitude = 1.0)
        val facts = listOf(event("in", Transition.ENTER, "14:00"), event("check", Transition.PRESENCE, "14:20"),
            RawEvent("b-in", "b", Transition.ENTER, at("14:30")), RawEvent("b-out", "b", Transition.EXIT, at("14:40")),
            event("back", Transition.ENTER, "14:55"), event("recovered", Transition.PRESENCE, "14:56"),
            event("after-b", Transition.PRESENCE, "14:50"))
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office, b), facts,
            policy = Policy(zoneId = zone, shortGapMinutes = 0), now = at("15:30"), historyStartDate = day,
            recoveryPresenceIds = setOf("recovered")))
        val old = result.intervals.single { "session:in" in it.sessionIds }
        assertEquals(at("14:05"), old.start)
        assertEquals(at("14:20"), old.end)
    }

    @Test fun gapOnTheLaterVisitDoesNotBlockAnObservedBridge() {
        // EXIT 10:00 → ENTER 10:10 is an ordinary observed short gap; the later visit's own
        // uncertain end does not make it uncertain.
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office), listOf(
            event("in", Transition.ENTER, "09:00"), event("out", Transition.EXIT, "10:00"),
            event("back", Transition.ENTER, "10:10"), event("check", Transition.PRESENCE, "11:00"),
            event("outside", Transition.ABSENCE, "12:00")),
            policy = Policy(zoneId = zone, shortGapMinutes = 20), now = at("12:30"), historyStartDate = day))
        assertTrue(result.intervals.any { it.reconciledGap })
        // 09:05–10:00 credit, 10:00–10:10 bridge, then 10:15–11:00 up to the last inside check.
        assertEquals(listOf(at("09:05") to at("10:10"), at("10:15") to at("11:00")),
            result.intervals.map { it.start to it.end })
    }

    @Test fun bounceEnterDoesNotEndThePrefix() {
        // EXIT 09:30 / ENTER 09:30:20 is merged as jitter; the visit continues.
        val facts = listOf(event("in", Transition.ENTER, "09:00"), event("out", Transition.EXIT, "09:30"),
            event("back", Transition.ENTER, "09:30:20"), event("check", Transition.PRESENCE, "10:00"))
        val outside = derive(facts + event("outside", Transition.ABSENCE, "11:00"), "11:30")
        assertEquals(at("10:00"), outside.intervals.single().end)
        val recovered = derive(facts + event("recovered", Transition.PRESENCE, "11:00"), "11:30",
            recovery = setOf("recovered"))
        assertEquals(at("10:00"), recovered.intervals.single { "session:in" in it.sessionIds }.end)
    }

    @Test fun reEnterExplainedByARejectedExitDoesNotEndThePrefix() {
        // A rejected phantom EXIT stays in the open visit; the resync ENTER that follows it is
        // corroboration, so later corroborating checks still bound the credit.
        val facts = listOf(event("in", Transition.ENTER, "13:00"), event("out", Transition.EXIT, "14:00"),
            event("s1", Transition.PRESENCE, "14:01"), event("s2", Transition.PRESENCE, "14:02"),
            event("s3", Transition.PRESENCE, "14:03"), event("s4", Transition.PRESENCE, "14:04:30"),
            event("resync", Transition.ENTER, "14:05"), event("c1", Transition.PRESENCE, "15:00"),
            event("c2", Transition.PRESENCE, "16:00"), event("outside", Transition.ABSENCE, "17:00"))
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office), facts,
            policy = Policy(zoneId = zone, shortGapMinutes = 0), now = at("17:30"), historyStartDate = day,
            rejectedExitIds = setOf("out")))
        assertEquals(at("16:00"), result.intervals.single().end)
    }

    @Test fun otherOfficeAbsenceDoesNotEndThePrefix() {
        val b = office.copy(id = "b", name = "Synthetic second office", latitude = 1.0)
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office, b), listOf(
            event("in", Transition.ENTER, "09:00"), RawEvent("b-out", "b", Transition.ABSENCE, at("09:30")),
            event("check", Transition.PRESENCE, "10:00"), event("outside", Transition.ABSENCE, "11:00")),
            policy = Policy(zoneId = zone, shortGapMinutes = 0), now = at("11:30"), historyStartDate = day))
        assertEquals(at("10:00"), result.intervals.single().end)
    }

    @Test fun lateReEnterAfterRejectedExitStillEndsThePrefix() {
        // The resync did not take: the platform stayed "outside", the user left unseen at 12:00
        // and its ENTER on return at 13:00 is the only sign. Credit stops at the 11:45 check.
        val facts = listOf(event("in", Transition.ENTER, "09:00"), event("out", Transition.EXIT, "10:00"),
            event("s1", Transition.PRESENCE, "10:01"), event("s4", Transition.PRESENCE, "10:04:30"),
            event("check", Transition.PRESENCE, "11:45"), event("back", Transition.ENTER, "13:00"),
            event("later", Transition.PRESENCE, "13:30"), event("outside", Transition.ABSENCE, "14:00"))
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office), facts,
            policy = Policy(zoneId = zone, shortGapMinutes = 0), now = at("14:30"), historyStartDate = day,
            rejectedExitIds = setOf("out")))
        assertEquals(at("11:45"), result.intervals.single().end)
    }
}
