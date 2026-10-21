package dev.hamstercage.capture

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessContinuityTest {
    private val zone = ZoneId.of("America/Indianapolis")

    @Test fun ordinaryProcessRecreationKeepsCoverageAndObservedAttendance() {
        val start = Instant.parse("2025-03-10T13:00:00Z")
        val healthy = CoverageLedger().registrationSucceeded(start, zone, true).observed(start.plusSeconds(60))
        val boundary = healthy.recoveryBoundaryAt
        // Android reclaimed the process; the registration token survived, so monitoring did not lapse.
        val recreated = healthy.processStarted(start.plusSeconds(3600), zone, ProcessContinuity.SURVIVED)
        assertEquals(healthy, recreated)
        val registered = recreated.registrationSucceeded(start.plusSeconds(3601), zone, true)
        assertEquals(boundary, registered.recoveryBoundaryAt)
        assertTrue(registered.presenceConfirmed(start.plusSeconds(3602), zone))
        assertFalse(LocalDate.parse("2025-03-11") in registered.unknownDates)
    }

    @Test fun lostRegistrationIsStillAnOutage() {
        val start = Instant.parse("2025-03-10T13:00:00Z")
        val healthy = CoverageLedger().registrationSucceeded(start, zone, true).observed(start.plusSeconds(60))
        val lost = healthy.processStarted(start.plusSeconds(86_400), zone, ProcessContinuity.LOST)
        assertEquals(healthy.lastHealthyAt, lost.outageStartedAt)
        assertTrue(LocalDate.parse("2025-03-11") in lost.unknownDates)
        val unknown = healthy.processStarted(start.plusSeconds(86_400), zone, ProcessContinuity.UNKNOWN)
        assertEquals(lost, unknown)
    }

    private fun decide(token: Boolean? = true, rebooted: Boolean? = false, replaced: Boolean? = false,
        exit: PreviousExit = PreviousExit.UNKNOWN, forceStop: Boolean? = null, background: Boolean = false,
        cancelsTokens: Boolean = false) = processContinuity(token, rebooted, replaced, exit, forceStop, background, cancelsTokens)

    @Test fun anyEvidenceOfLostRegistrationIsAnOutage() {
        assertEquals(ProcessContinuity.LOST, decide(token = false, background = true))
        assertEquals(ProcessContinuity.LOST, decide(rebooted = true, background = true))
        assertEquals(ProcessContinuity.LOST, decide(replaced = true, background = true))
        assertEquals(ProcessContinuity.LOST, decide(forceStop = true, cancelsTokens = true))
        assertEquals(ProcessContinuity.LOST, decide(exit = PreviousExit.STOPPED_OR_CHANGED, background = true))
    }

    @Test fun unreadableSignalsStayConservativeUnlessTheTokenRulesThemOut() {
        assertEquals(ProcessContinuity.UNKNOWN, decide(token = null, background = true))
        assertEquals(ProcessContinuity.UNKNOWN, decide(replaced = null, background = true))
        // A reboot cancels the token, so a present token makes an unreadable boot count moot.
        assertEquals(ProcessContinuity.SURVIVED, decide(rebooted = null))
    }

    @Test fun presentTokenIsTrustedUnlessAStopOrUpdateIsRecorded() {
        // #142: an ordinary restart with the registration still held is continuous monitoring,
        // even without a recorded reclaim (foreground launch, missing or stale exit record).
        assertEquals(ProcessContinuity.SURVIVED, decide())
        assertEquals(ProcessContinuity.SURVIVED, decide(background = true))
        assertEquals(ProcessContinuity.SURVIVED, decide(cancelsTokens = true, forceStop = false))
        assertEquals(ProcessContinuity.SURVIVED, decide(exit = PreviousExit.RECLAIMED))
        // Accepted risk: Android 8–10 record no exit reason, so a force-stop there cannot be told
        // apart from a reclaim and is trusted too.
        assertEquals(ProcessContinuity.SURVIVED, decide(exit = PreviousExit.UNKNOWN, cancelsTokens = false))
    }

    @Test fun userRequestedExitIsTrustedOnlyWhereItCannotBeAForceStop() {
        // Android 11–14 record a force-stop as a user request and keep the token.
        assertEquals(ProcessContinuity.LOST, decide(exit = PreviousExit.USER_REQUESTED))
        // Opening the app after a force-stop lets overdue work run first, so a background start
        // does not rule a force-stop out.
        assertEquals(ProcessContinuity.LOST, decide(exit = PreviousExit.USER_REQUESTED, background = true))
        // Android 15+ cancels the token on force-stop and records it separately, so a swipe-away is safe.
        assertEquals(ProcessContinuity.SURVIVED, decide(exit = PreviousExit.USER_REQUESTED, cancelsTokens = true, forceStop = false))
        assertEquals(ProcessContinuity.LOST, decide(exit = PreviousExit.USER_REQUESTED, cancelsTokens = true, forceStop = true))
        assertEquals(ProcessContinuity.LOST, decide(token = false, exit = PreviousExit.USER_REQUESTED, cancelsTokens = true))
    }

    @Test fun recoveryRemembersWhereTheOutageBegan() {
        val start = Instant.parse("2025-03-10T13:00:00Z")
        val lastHealthy = start.plusSeconds(7200)
        val healthy = CoverageLedger().registrationSucceeded(start, zone, true).observed(lastHealthy)
        val recovered = healthy.processStarted(start.plusSeconds(7500), zone, ProcessContinuity.UNKNOWN)
            .registrationSucceeded(start.plusSeconds(7501), zone, true)
        assertEquals(lastHealthy, recovered.lastOutageStartedAt)
        assertEquals(start.plusSeconds(7501), recovered.recoveryBoundaryAt)
        // An ordinary re-registration without an outage keeps the earlier record.
        assertEquals(lastHealthy, recovered.registrationSucceeded(start.plusSeconds(9000), zone, true).lastOutageStartedAt)
    }

    @Test fun healthyWindowStartsAtThePreviousRecovery() {
        val start = Instant.parse("2025-03-10T13:00:00Z")
        val firstRecovery = CoverageLedger().registrationSucceeded(start, zone, true).observed(start.plusSeconds(1200))
            .processStarted(start.plusSeconds(2700), zone, ProcessContinuity.LOST)
            .registrationSucceeded(start.plusSeconds(2760), zone, true)
        val boundary = firstRecovery.recoveryBoundaryAt
        val second = firstRecovery.observed(start.plusSeconds(3600))
            .processStarted(start.plusSeconds(3640), zone, ProcessContinuity.UNKNOWN)
            .registrationSucceeded(start.plusSeconds(3645), zone, true)
        assertEquals(start.plusSeconds(3600), second.lastOutageStartedAt)
        assertEquals(boundary, second.lastOutageHealthySince)
        assertEquals(null, second.outageHealthySince)
    }

    @Test fun liveShutdownAnchorsOnlyTheImmediatelyMatchedReboot() {
        val registeredAt = Instant.parse("2025-03-10T13:00:00Z")
        val observedAt = registeredAt.plusSeconds(60)
        val shutdownAt = registeredAt.plusSeconds(28_800)
        val returnedAt = shutdownAt.plusSeconds(120)
        val original = ProcessIdentity(42, 500L)
        val healthy = CoverageLedger().registrationSucceeded(registeredAt, zone, true, original)
            .observed(observedAt)
        val marked = healthy.shutdownObserved(shutdownAt, original, generation = 7)
        assertEquals(healthy.lastHealthyAt, marked.lastHealthyAt)
        assertEquals(healthy.lastObservationAt, marked.lastObservationAt)
        val rebooted = marked.processStarted(returnedAt, zone, ProcessContinuity.LOST,
            ProcessIdentity(43, 500L), generation = 7)
        assertEquals(shutdownAt, rebooted.outageStartedAt)
        assertEquals(null, rebooted.shutdownAt)
        assertFalse(rebooted.presenceConfirmed(returnedAt, zone))
        assertEquals(returnedAt, rebooted.registrationSucceeded(returnedAt, zone, true).recoveryBoundaryAt)
        // The old observation is not a new current presence even though the gap began later.
        assertFalse(rebooted.registrationSucceeded(returnedAt, zone, true)
            .presenceConfirmed(returnedAt.plusSeconds(1), zone))
    }

    @Test fun shutdownMarkerFailsClosedOnMismatchOrExistingOutage() {
        val registeredAt = Instant.parse("2025-03-10T13:00:00Z")
        val shutdownAt = registeredAt.plusSeconds(3600)
        val returnedAt = shutdownAt.plusSeconds(120)
        val old = ProcessIdentity(42, 500L)
        val healthy = CoverageLedger().registrationSucceeded(registeredAt, zone, true, old)
        val marked = healthy.shutdownObserved(shutdownAt, old, 7)
        val candidates = listOf(
            Triple(ProcessIdentity(42, 500L), 7L, zone), // reboot not confirmed
            Triple(ProcessIdentity(44, 500L), 7L, zone), // intervening reboot
            Triple(ProcessIdentity(43, 501L), 7L, zone), // package changed
            Triple(ProcessIdentity(43, 500L), 8L, zone), // privacy generation changed
            Triple(ProcessIdentity(43, 500L), 7L, ZoneId.of("UTC")),
        )
        candidates.forEach { (identity, generation, policyZone) ->
            val lost = marked.processStarted(returnedAt, policyZone, ProcessContinuity.LOST,
                identity, generation)
            assertEquals(registeredAt, lost.outageStartedAt)
            assertEquals(null, lost.shutdownAt)
        }
        assertEquals(registeredAt, marked.processStarted(registeredAt, zone,
            ProcessContinuity.LOST, ProcessIdentity(43, 500L), 7).outageStartedAt)
        assertEquals(registeredAt, healthy.outage(shutdownAt.minusSeconds(60), zone)
            .shutdownObserved(shutdownAt, old, 7).outageStartedAt)
        assertEquals(null, healthy.outage(shutdownAt.minusSeconds(60), zone)
            .shutdownObserved(shutdownAt, old, 7).shutdownAt)
    }

    @Test fun laterRegistrationObservationOrOutageInvalidatesAbandonedShutdown() {
        val at = Instant.parse("2025-03-10T13:00:00Z")
        val identity = ProcessIdentity(42, 500L)
        val marked = CoverageLedger().registrationSucceeded(at, zone, true, identity)
            .shutdownObserved(at.plusSeconds(60), identity, 7)
        assertEquals(null, marked.registrationSucceeded(at.plusSeconds(61), zone, true, identity).shutdownAt)
        assertEquals(null, marked.observed(at.plusSeconds(61)).shutdownAt)
        assertEquals(null, marked.outage(at.plusSeconds(61), zone).shutdownAt)
        assertEquals(null, marked.unlocatedOutage(at.plusSeconds(61)).shutdownAt)
        assertEquals(null, marked.processStarted(at.plusSeconds(61), zone,
            ProcessContinuity.SURVIVED, identity, 7).shutdownAt)
        assertEquals(null, CoverageLedger().registrationSucceeded(at, zone, true)
            .shutdownObserved(at.plusSeconds(60), identity, 7).shutdownAt)
    }

    @Test fun overnightShutdownKeepsEarlierHealthyDatesAndWindow() {
        val registeredAt = Instant.parse("2025-03-10T13:00:00Z")
        val lastObservation = Instant.parse("2025-03-11T13:00:00Z")
        val shutdownAt = Instant.parse("2025-03-12T23:00:00Z")
        val returnAt = Instant.parse("2025-03-13T14:00:00Z")
        val identity = ProcessIdentity(42, 500L)
        val firstDay = LocalDate.parse("2025-03-10")
        val recovered = CoverageLedger().registrationSucceeded(registeredAt, zone, true, identity)
            .observed(registeredAt.plusSeconds(60)).review(firstDay).observed(lastObservation)
            .shutdownObserved(shutdownAt, identity, 7)
            .processStarted(returnAt, zone, ProcessContinuity.LOST, ProcessIdentity(43, 500L), 7)
            .registrationSucceeded(returnAt, zone, true, ProcessIdentity(43, 500L))
        assertEquals(shutdownAt, recovered.lastOutageStartedAt)
        assertEquals(registeredAt, recovered.lastOutageHealthySince)
        assertFalse(firstDay in recovered.unreviewedUnknownDates)
        assertFalse(LocalDate.parse("2025-03-11") in recovered.unreviewedUnknownDates)
        assertTrue(LocalDate.parse("2025-03-12") in recovered.unreviewedUnknownDates)
        assertTrue(LocalDate.parse("2025-03-13") in recovered.unreviewedUnknownDates)
        assertFalse(recovered.presenceConfirmed(returnAt, zone))
    }

    @Test fun shutdownRequiresFreshActiveLedgerAndMatchingKnownIdentity() {
        val at = Instant.parse("2025-03-10T13:00:00Z")
        val identity = ProcessIdentity(42, 500L)
        val healthy = CoverageLedger().registrationSucceeded(at, zone, true, identity)
        val invalid = listOf(healthy.shutdownObserved(at.minusSeconds(1), identity, 7),
            healthy.shutdownObserved(at.plusSeconds(1), ProcessIdentity(null, 500L), 7),
            healthy.shutdownObserved(at.plusSeconds(1), ProcessIdentity(42, null), 7),
            healthy.shutdownObserved(at.plusSeconds(1), ProcessIdentity(43, 500L), 7),
            healthy.shutdownObserved(at.plusSeconds(1), identity, -1),
            healthy.outage(at.plusSeconds(1), zone).shutdownObserved(at.plusSeconds(2), identity, 7),
            healthy.copy(registration = RegistrationStatus.DISABLED)
                .shutdownObserved(at.plusSeconds(1), identity, 7),
            CoverageLedger().shutdownObserved(at, identity, 7))
        invalid.forEach { assertEquals(null, it.shutdownAt) }
    }
}
