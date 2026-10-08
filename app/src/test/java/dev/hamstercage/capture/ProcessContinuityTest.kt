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

    @Test fun continuityRequiresTheRegistrationTokenAndNoUserStop() {
        assertEquals(ProcessContinuity.SURVIVED, processContinuity(pendingIntentPresent = true, userStopped = false))
        assertEquals(ProcessContinuity.LOST, processContinuity(pendingIntentPresent = false, userStopped = false))
        assertEquals(ProcessContinuity.LOST, processContinuity(pendingIntentPresent = true, userStopped = true))
        assertEquals(ProcessContinuity.UNKNOWN, processContinuity(pendingIntentPresent = null, userStopped = false))
    }
}
