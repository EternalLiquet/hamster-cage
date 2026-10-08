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

    @Test fun unreadableSignalsStayConservative() {
        assertEquals(ProcessContinuity.UNKNOWN, decide(token = null, background = true))
        assertEquals(ProcessContinuity.UNKNOWN, decide(rebooted = null, background = true))
        assertEquals(ProcessContinuity.UNKNOWN, decide(replaced = null, background = true))
    }

    @Test fun survivalNeedsPositiveEvidenceOfNoForceStop() {
        // A background job cannot run for a force-stopped app on any version.
        assertEquals(ProcessContinuity.SURVIVED, decide(background = true))
        // Android 15+ cancels the token on force-stop, so a present token rules it out.
        assertEquals(ProcessContinuity.SURVIVED, decide(cancelsTokens = true, forceStop = false))
        // Android 11–14: a recorded system reclaim of the last healthy process.
        assertEquals(ProcessContinuity.SURVIVED, decide(exit = PreviousExit.RECLAIMED))
        // A foreground launch with no exit record (Android 8–10) cannot rule out a force-stop.
        assertEquals(ProcessContinuity.UNKNOWN, decide())
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
}
