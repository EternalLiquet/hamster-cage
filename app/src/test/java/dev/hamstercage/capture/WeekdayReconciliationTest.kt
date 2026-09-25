package dev.hamstercage.capture

import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.Transition
import dev.hamstercage.location.LocationSetup
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekdayReconciliationTest {
    private val zone = ZoneId.of("America/New_York")
    private val office = Office("a", "Synthetic A", 39.0, -86.0, 150f, entryGraceMinutes = 5)
    private val second = office.copy(id = "b", name = "Synthetic B")
    private fun time(value: String) = Instant.parse(value)
    private fun snapshot(vararg facts: RawEvent) = AppSnapshot(listOf(office, second),
        facts.map { RecordedEvent(it, it.at) }, emptyList(), emptyList(), Policy(zoneId = zone))
    private fun snapshotEvidence(vararg evidence: RecordedEvent) = AppSnapshot(listOf(office, second),
        evidence.toList(), emptyList(), emptyList(), Policy(zoneId = zone))
    private fun ledger(at: Instant) = CoverageLedger().registrationSucceeded(at.minusSeconds(1), zone, true).observed(at)

    @Test fun workWindowUsesPolicyTimezoneAcrossWeekendsAndDst() {
        assertFalse(insideReconciliationWindow(time("2026-09-28T10:59:59Z"), zone)) // 6:59 EDT
        assertTrue(insideReconciliationWindow(time("2026-09-28T11:00:00Z"), zone))
        assertTrue(insideReconciliationWindow(time("2026-09-28T22:59:59Z"), zone))
        assertFalse(insideReconciliationWindow(time("2026-09-28T23:00:00Z"), zone))
        assertFalse(insideReconciliationWindow(time("2026-09-26T15:00:00Z"), zone))
        assertFalse(insideReconciliationWindow(time("2026-09-27T15:00:00Z"), zone))
        assertTrue(insideReconciliationWindow(time("2026-11-02T12:00:00Z"), zone)) // 7 EST
        assertFalse(insideReconciliationWindow(time("2026-11-02T11:59:59Z"), zone))
        assertTrue(insideReconciliationWindow(time("2026-09-28T10:30:00Z"), ZoneId.of("Europe/London")))
    }

    @Test fun permissionDisableAndRecoveryOutagePreventBackgroundFixRequest() {
        val now = time("2026-09-28T14:00:00Z")
        val ready = LocationSetup(true, true, true, true, true)
        val coverage = ledger(now.minusSeconds(60))
        fun allowed(setup: LocationSetup = ready, enabled: Boolean = true,
            ledger: CoverageLedger = coverage, idle: Boolean = true) =
            canRequestReconciliationFix(now, zone, enabled, setup, ledger, idle)
        assertTrue(allowed())
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(idle = false))
        assertFalse(allowed(setup = ready.copy(fineLocation = false)))
        assertFalse(allowed(setup = ready.copy(backgroundLocation = false)))
        assertFalse(allowed(setup = ready.copy(locationEnabled = false)))
        assertFalse(allowed(setup = ready.copy(playServicesAvailable = false)))
        assertFalse(allowed(ledger = coverage.outage(now, zone)))
        assertFalse(allowed(ledger = coverage.copy(policyZoneId = ZoneId.of("UTC"))))
    }

    @Test fun missedEnterStartsAtCheckAndDuplicateChecksDoNotAddFacts() {
        val check = time("2026-09-28T14:00:00Z")
        val receipt = check.plusSeconds(20)
        val first = reconciliationFacts(snapshot(), ledger(check.minusSeconds(60)), receipt, check, office.id)
        assertEquals(1, first.size)
        assertEquals(Transition.PRESENCE, first.single().event.transition)
        assertEquals(check, first.single().event.at)
        assertEquals(check, first.single().observedLocationAt)
        assertEquals(receipt, first.single().receivedAt)
        val saved = snapshot(first.single().event)
        assertEquals(0, reconciliationFacts(saved, ledger(check), check.plusSeconds(1800),
            check.plusSeconds(1800), office.id).size)
        val result = saved.derive(check.plusSeconds(360))
        assertEquals(check.plusSeconds(300), result.intervals.single().start)
    }

    @Test fun outsideFixResolvesUnconfirmedFinalExitWithoutAddingCreditOrNewReview() {
        val enter = RawEvent("enter", office.id, Transition.ENTER, time("2026-09-28T13:00:00Z"))
        val exit = RawEvent("exit", office.id, Transition.EXIT, time("2026-09-28T14:00:00Z"))
        val fixAt = time("2026-09-28T14:29:45Z")
        val receipt = fixAt.plusSeconds(15)
        val before = snapshot(enter, exit)
        val pending = before.derive(receipt)
        assertEquals(1, pending.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        val facts = reconciliationFacts(before, ledger(exit.at), receipt, fixAt, null, 12f)
        assertEquals(listOf(office.id to Transition.ABSENCE),
            facts.map { it.event.officeId to it.event.transition })
        assertEquals(fixAt, facts.single().event.at)
        assertEquals(fixAt, facts.single().observedLocationAt)
        assertEquals(receipt, facts.single().receivedAt)
        assertEquals(12f, facts.single().accuracyMeters!!, 0f)
        val saved = before.copy(eventEvidence = before.eventEvidence + facts)
        assertEquals(listOf(enter, exit), saved.events.take(2))
        val resolved = saved.derive(receipt)
        assertTrue(resolved.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertEquals(pending.intervals.sumOf { it.minutes }, resolved.intervals.sumOf { it.minutes }, 0.0)
        assertEquals(0, reconciliationFacts(saved, ledger(fixAt), fixAt.plusSeconds(1800),
            fixAt.plusSeconds(1800), null).size)
    }

    @Test fun insideOtherOfficeConfirmsPriorExitAndStartsOnlyAtNewFixTime() {
        val enter = RawEvent("enter-a", office.id, Transition.ENTER, time("2026-09-28T13:00:00Z"))
        val exit = RawEvent("exit-a", office.id, Transition.EXIT, time("2026-09-28T14:00:00Z"))
        val fixAt = time("2026-09-28T14:30:00Z")
        val before = snapshot(enter, exit)
        val facts = reconciliationFacts(before, ledger(exit.at), fixAt.plusSeconds(10), fixAt, second.id)
        assertEquals(setOf(office.id to Transition.ABSENCE, second.id to Transition.PRESENCE),
            facts.map { it.event.officeId to it.event.transition }.toSet())
        assertTrue(facts.all { it.event.at == fixAt })
        val saved = before.copy(eventEvidence = before.eventEvidence + facts)
        val result = saved.derive(fixAt.plusSeconds(360))
        assertTrue(result.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertEquals(fixAt, result.sessions.single { it.officeId == second.id }.start)
        assertEquals(fixAt.plusSeconds(300), result.intervals.last().start)
        assertEquals(result.intervals.sumOf { it.minutes },
            dev.hamstercage.domain.AttendanceEngine.daily(saved.input(fixAt.plusSeconds(360)), result,
                fixAt.atZone(zone).toLocalDate()).creditedMinutes, 0.0)
    }

    @Test fun falseFinalExitCannotLeaveCreditStoppedAfterLaterInsideCheck() {
        val enter = RawEvent("enter", office.id, Transition.ENTER, time("2026-09-28T13:00:00Z"))
        val exit = RawEvent("exit", office.id, Transition.EXIT, time("2026-09-28T14:00:00Z"))
        val fixAt = time("2026-09-28T14:30:00Z")
        val before = snapshot(enter, exit)
        val facts = reconciliationFacts(before, ledger(exit.at), fixAt.plusSeconds(12), fixAt, office.id)
        assertEquals(listOf(Transition.PRESENCE), facts.map { it.event.transition })
        assertEquals(fixAt, facts.single().event.at)
        val saved = before.copy(eventEvidence = before.eventEvidence + facts)
        val result = saved.derive(fixAt.plusSeconds(360))
        assertEquals(listOf(enter, exit), saved.events.take(2))
        assertEquals(2, result.sessions.size)
        assertEquals(fixAt, result.sessions.last().start)
        assertEquals(fixAt.plusSeconds(300), result.intervals.last().start)
        assertTrue(result.intervals.none { it.start < fixAt && it.end > exit.at })
        assertTrue(result.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertTrue(reconciliationFacts(saved, ledger(fixAt), fixAt.plusSeconds(1800),
            fixAt.plusSeconds(1800), office.id).isEmpty())
    }

    @Test fun priorDecisiveConfirmationPreventsRepeatedFinalExitResolution() {
        val enter = RawEvent("enter", office.id, Transition.ENTER, time("2026-09-28T13:00:00Z"))
        val exit = RawEvent("exit", office.id, Transition.EXIT, time("2026-09-28T14:00:00Z"))
        val confirmed = RawEvent("outside", office.id, Transition.ABSENCE, time("2026-09-28T14:01:00Z"))
        val before = snapshotEvidence(RecordedEvent(enter, enter.at), RecordedEvent(exit, exit.at),
            RecordedEvent(confirmed, confirmed.at, confirmed.at, "ADAPTIVE_LOCATION_CONFIRMATION", 15f))
        val check = time("2026-09-28T14:30:00Z")
        assertTrue(reconciliationFacts(before, ledger(check.minusSeconds(60)), check, check, null).isEmpty())
    }

    @Test fun missedExitClosesForReviewWithoutCreditingUnknownSpan() {
        val entered = time("2026-09-28T13:00:00Z")
        val check = entered.plusSeconds(3600)
        val before = snapshot(RawEvent("enter", office.id, Transition.ENTER, entered))
        val absence = reconciliationFacts(before, ledger(entered), check, check, null)
        assertEquals(listOf(Transition.ABSENCE), absence.map { it.event.transition })
        val after = snapshot(before.events.single(), absence.single().event).derive(check.plusSeconds(1800))
        assertFalse(after.sessions.single().isOpen)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in after.sessions.single().reviewReasons)
        assertTrue(after.intervals.isEmpty())
        assertTrue(reconciliationFacts(snapshot(before.events.single(), absence.single().event),
            ledger(check), check.plusSeconds(1800), check.plusSeconds(1800), null).isEmpty())
    }

    @Test fun changedOfficeLeavesOldUnknownAndStartsNewObservedVisit() {
        val entered = time("2026-09-28T13:00:00Z")
        val check = entered.plusSeconds(3600)
        val original = RawEvent("enter", office.id, Transition.ENTER, entered)
        val facts = reconciliationFacts(snapshot(original), ledger(entered), check, check, second.id)
        assertEquals(setOf(Transition.ABSENCE, Transition.PRESENCE), facts.map { it.event.transition }.toSet())
        val result = snapshot(original, *facts.map { it.event }.toTypedArray()).derive(check.plusSeconds(360))
        assertEquals(2, result.sessions.size)
        assertEquals(1, result.intervals.size)
        assertEquals(check.plusSeconds(300), result.intervals.single().start)
    }

    @Test fun backgroundPresenceAfterOutageSplitsOldOpenInsteadOfCreditingUnknownTime() {
        val oldAt = time("2026-09-28T13:00:00Z")
        val check = oldAt.plusSeconds(3600)
        val old = RawEvent("old-a", office.id, Transition.ENTER, oldAt)
        val coverage = ledger(oldAt).outage(oldAt.plusSeconds(600), zone)
            .registrationSucceeded(check.minusSeconds(60), zone, true)
        val before = snapshot(old)
        val fix = reconciliationFacts(before, coverage, check, check, office.id)
        assertEquals(listOf(Transition.PRESENCE), fix.map { it.event.transition })
        val after = snapshotEvidence(RecordedEvent(old, old.at), fix.single())
        val result = after.derive(check.plusSeconds(360))
        assertEquals(2, result.sessions.size)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in result.sessions.first().reviewReasons)
        assertTrue(result.intervals.none { result.sessions.first().id in it.sessionIds })
        assertEquals(check.plusSeconds(300), result.intervals.single().start)
    }

    @Test fun backgroundPresenceAfterOtherOfficeVisitCannotCertifyOldOffice() {
        val oldAt = time("2026-09-28T13:00:00Z")
        val bIn = oldAt.plusSeconds(600)
        val bOut = oldAt.plusSeconds(1200)
        val check = oldAt.plusSeconds(1800)
        val old = RawEvent("old-a", office.id, Transition.ENTER, oldAt)
        val secondIn = RawEvent("b-in", second.id, Transition.ENTER, bIn)
        val secondOut = RawEvent("b-out", second.id, Transition.EXIT, bOut)
        val coverage = ledger(oldAt).observed(bOut)
        val before = snapshot(old, secondIn, secondOut)
        val fix = reconciliationFacts(before, coverage, check, check, office.id)
        assertEquals(setOf(second.id to Transition.ABSENCE, office.id to Transition.PRESENCE),
            fix.map { it.event.officeId to it.event.transition }.toSet())
        val presence = fix.single { it.event.transition == Transition.PRESENCE }
        val after = snapshotEvidence(RecordedEvent(old, old.at), RecordedEvent(secondIn, bIn),
            RecordedEvent(secondOut, bOut), *fix.toTypedArray())
        val result = after.derive(check.plusSeconds(360))
        assertEquals(3, result.sessions.size)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in result.sessions.single { it.id == "session:old-a" }.reviewReasons)
        assertEquals(check, result.sessions.single { it.id == "session:${presence.event.id}" }.start)
        assertTrue(result.intervals.none { "session:old-a" in it.sessionIds })
        assertTrue(result.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }
}
