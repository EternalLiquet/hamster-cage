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
}
