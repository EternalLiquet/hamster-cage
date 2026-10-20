package dev.hamstercage.capture

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageLedgerTest {
    private val zone = ZoneId.of("America/Indianapolis")
    private fun at(value: String) = Instant.parse(value)

    @Test fun processDeathCreatesGapAndRequiresNewObservation() {
        val first = at("2025-03-09T15:00:00Z")
        val restarted = at("2025-03-11T15:00:00Z")
        val recovered = restarted.plusSeconds(60)
        val healthy = CoverageLedger().registrationSucceeded(first, zone, true).observed(first.plusSeconds(2))
        assertEquals(LocalDate.parse("2025-03-09"), healthy.historyStartDate)
        assertTrue(LocalDate.parse("2025-03-09") in healthy.unreviewedUnknownDates)
        assertTrue(healthy.presenceConfirmed(first.plusSeconds(3), zone))

        val gap = healthy.processStarted(restarted, zone)
        assertEquals(setOf(LocalDate.parse("2025-03-09"), LocalDate.parse("2025-03-10"),
            LocalDate.parse("2025-03-11")), gap.unreviewedUnknownDates)
        assertFalse(gap.presenceConfirmed(restarted, zone))

        val restored = gap.registrationSucceeded(recovered, zone, true)
        assertFalse(restored.presenceConfirmed(recovered, zone))
        assertFalse(restored.observed(first).presenceConfirmed(recovered, zone))
        assertTrue(restored.observed(recovered.plusSeconds(1)).presenceConfirmed(recovered.plusSeconds(2), zone))
    }

    @Test fun sameUnresolvedOutagePreservesPriorReviewButNewOutageInvalidatesIt() {
        val monday = LocalDate.parse("2025-03-10")
        val first = at("2025-03-10T15:00:00Z")
        val ledger = CoverageLedger().registrationSucceeded(first, zone, true)
            .outage(first.plusSeconds(300), zone).review(monday)
        assertFalse(monday in ledger.unreviewedUnknownDates)
        val same = ledger.outage(first.plusSeconds(600), zone)
        assertTrue(monday in same.reviewedDates)
        val recovered = same.registrationSucceeded(first.plusSeconds(900), zone, true)
        val newOutage = recovered.outage(first.plusSeconds(1200), zone)
        assertFalse(monday in newOutage.reviewedDates)
        assertTrue(monday in newOutage.unreviewedUnknownDates)
    }

    @Test fun failedOrEmptyRegistrationCannotConfirmPresence() {
        val now = at("2025-03-10T15:00:00Z")
        val empty = CoverageLedger().registrationSucceeded(now, zone, false).observed(now)
        assertFalse(empty.presenceConfirmed(now, zone))
        assertEquals(RegistrationStatus.NO_OFFICES, empty.registration)
        assertEquals(null, CoverageLedger().registrationSucceeded(now, zone, true).historyStartDate)
    }

    @Test fun deliveryFailureDefersDayAssignmentUntilPolicyZoneIsKnown() {
        val healthyAt = at("2025-03-10T03:30:00Z") // March 9 in Indianapolis.
        val failedAt = healthyAt.plusSeconds(60)
        val pending = CoverageLedger().registrationSucceeded(healthyAt, zone, true)
            .unlocatedOutage(failedAt)
        assertEquals(healthyAt, pending.outageStartedAt)
        assertEquals(null, pending.outageRecordedThrough)
        assertEquals(setOf(LocalDate.parse("2025-03-09")),
            pending.outage(failedAt, zone).unreviewedUnknownDates)
    }

    @Test fun veryLongClockGapFailsClosedInsteadOfDroppingUnknownDates() {
        val first = at("2025-03-10T15:00:00Z")
        val later = at("2040-03-10T15:00:00Z")
        val uncertain = CoverageLedger().registrationSucceeded(first, zone, true).observed(first)
            .processStarted(later, zone).registrationSucceeded(later, zone, true)
        assertEquals(null, uncertain.historyStartDate)
        assertFalse(uncertain.presenceConfirmed(later, zone))
    }

    @Test fun firstObservedTransitionStartsPolicyLocalCoverageImmediately() {
        val registered = at("2025-03-10T03:20:00Z")
        val firstEvent = at("2025-03-10T03:30:00Z")
        val ledger = CoverageLedger().registrationSucceeded(registered, zone, true)
        assertEquals(null, ledger.historyStartDate)
        val observed = ledger.observed(firstEvent)
        assertEquals(LocalDate.parse("2025-03-09"), observed.historyStartDate)
        assertTrue(LocalDate.parse("2025-03-09") in observed.unreviewedUnknownDates)
    }

    @Test fun changingPolicyZoneInvalidatesOldDateConfidence() {
        val event = at("2025-03-10T03:30:00Z")
        val indianapolis = CoverageLedger().registrationSucceeded(event.minusSeconds(60), zone, true)
            .observed(event).review(LocalDate.parse("2025-03-09"))
        assertEquals(LocalDate.parse("2025-03-09"), indianapolis.historyStartDate)
        val utc = ZoneId.of("UTC")
        val changed = indianapolis.registrationSucceeded(event.plusSeconds(60), utc, true)
        assertEquals(null, changed.historyStartDate)
        assertTrue(changed.reviewedDates.isEmpty())
        assertFalse(changed.presenceConfirmed(event.plusSeconds(61), utc))
        val observedAgain = changed.observed(event.plusSeconds(62))
        assertEquals(LocalDate.parse("2025-03-10"), observedAgain.historyStartDate)
        assertTrue(LocalDate.parse("2025-03-10") in observedAgain.unreviewedUnknownDates)
    }

    @Test fun orderlyShutdownStartsTheRebootGapAtShutdownNotAtTheLastObservation() {
        val arrived = at("2025-03-10T13:48:00Z")
        val shutdown = at("2025-03-10T19:24:30Z")
        val healthy = CoverageLedger().registrationSucceeded(arrived.minusSeconds(60), zone, true).observed(arrived)
        val recorded = healthy.shutdown(shutdown)
        assertEquals(shutdown, recorded.lastHealthyAt)
        assertEquals(shutdown, recorded.shutdownAt)
        val booted = recorded.processStarted(at("2025-03-10T19:25:03Z"), zone, ProcessContinuity.LOST)
            .registrationSucceeded(at("2025-03-10T19:25:04Z"), zone, true)
        assertEquals(shutdown, booted.lastOutageStartedAt)
        // A reboot is still a real gap: presence needs a new observation after it.
        assertFalse(booted.presenceConfirmed(at("2025-03-10T19:26:00Z"), zone))
        // Without the announcement (sudden power loss) the gap keeps the last observation as its start.
        val unannounced = healthy.processStarted(at("2025-03-10T19:25:03Z"), zone, ProcessContinuity.LOST)
            .registrationSucceeded(at("2025-03-10T19:25:04Z"), zone, true)
        assertEquals(arrived, unannounced.lastOutageStartedAt)
    }

    @Test fun shutdownKeepsEarlierDaysOutOfAnOvernightRebootGap() {
        val seen = at("2025-03-10T13:00:00Z")
        val healthy = CoverageLedger().registrationSucceeded(seen.minusSeconds(60), zone, true).observed(seen)
            .review(LocalDate.parse("2025-03-10"))
        val shutdown = at("2025-03-11T11:00:00Z")
        val boot = at("2025-03-11T11:02:00Z")
        val bounded = healthy.shutdown(shutdown).processStarted(boot, zone, ProcessContinuity.LOST)
        assertEquals(setOf(LocalDate.parse("2025-03-11")), bounded.unreviewedUnknownDates)
        val unbounded = healthy.processStarted(boot, zone, ProcessContinuity.LOST)
        assertEquals(setOf(LocalDate.parse("2025-03-10"), LocalDate.parse("2025-03-11")), unbounded.unreviewedUnknownDates)
    }

    @Test fun shutdownProvesNothingWithoutHealthyCapture() {
        val seen = at("2025-03-10T13:00:00Z")
        val later = at("2025-03-10T18:00:00Z")
        val healthy = CoverageLedger().registrationSucceeded(seen.minusSeconds(60), zone, true).observed(seen)
        // During an outage, before any health, with registration not active, or with a clock earlier
        // than the last health, the shutdown does not extend health.
        val inOutage = healthy.processStarted(seen.plusSeconds(600), zone, ProcessContinuity.LOST)
        assertEquals(inOutage, inOutage.shutdown(later))
        assertEquals(CoverageLedger(), CoverageLedger().shutdown(later))
        val disabled = healthy.copy(registration = RegistrationStatus.DISABLED)
        assertEquals(disabled, disabled.shutdown(later))
        assertEquals(healthy, healthy.shutdown(seen.minusSeconds(1)))
    }
}
