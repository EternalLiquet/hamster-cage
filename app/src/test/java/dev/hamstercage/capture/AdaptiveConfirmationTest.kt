package dev.hamstercage.capture

import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import dev.hamstercage.location.LocationSetup
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveConfirmationTest {
    private val at = Instant.parse("2026-09-25T15:00:00Z")
    private fun event(id: String, office: String, type: Transition, second: Long = 0,
        source: String = "PLAY_SERVICES_GEOFENCE") = RecordedEvent(
            RawEvent(id, office, type, at.plusSeconds(second)), at.plusSeconds(second), source = source)

    @Test fun latestRawCandidateCanBeConfirmedButDelayedOrOtherOfficeEventSupersedesIt() {
        val exit = event("exit", "a", Transition.EXIT)
        assertTrue(confirmableCandidate(listOf(exit), "exit", "a"))
        assertFalse(confirmableCandidate(listOf(exit, event("return", "a", Transition.ENTER, 1)), "exit", "a"))
        assertFalse(confirmableCandidate(listOf(exit, event("other", "b", Transition.ENTER, 1)), "exit", "a"))
        assertFalse(confirmableCandidate(listOf(exit, event("old", "a", Transition.ENTER, -1)), "old", "a"))
        assertFalse(confirmableCandidate(listOf(exit), "missing", "a"))
        assertFalse(confirmableCandidate(listOf(event("fix", "a", Transition.PRESENCE,
            source = "ADAPTIVE_LOCATION_CONFIRMATION")), "fix", "a"))
    }

    @Test fun approximateLocationLocationOffAndOptOutCannotRequestFix() {
        val ready = LocationSetup(true, true, true, true, true)
        assertTrue(canRequestAdaptiveFix(ready, true, true, RegistrationStatus.ACTIVE))
        assertFalse(canRequestAdaptiveFix(ready.copy(fineLocation = false), true, true, RegistrationStatus.ACTIVE))
        assertFalse(canRequestAdaptiveFix(ready.copy(locationEnabled = false), true, true, RegistrationStatus.ACTIVE))
        assertFalse(canRequestAdaptiveFix(ready.copy(backgroundLocation = false), true, true, RegistrationStatus.ACTIVE))
        assertFalse(canRequestAdaptiveFix(ready, false, true, RegistrationStatus.ACTIVE))
        assertFalse(canRequestAdaptiveFix(ready, true, false, RegistrationStatus.ACTIVE))
        assertFalse(canRequestAdaptiveFix(ready, true, true, RegistrationStatus.FAILED))
    }

    @Test fun confirmationFactsUseObservedFixTimeAndPreserveCrossOfficeMeaning() {
        var next = 0
        val inside = adaptiveFacts("a", "a", at, at.plusSeconds(8), 18f) { "id-${++next}" }
        assertEquals(listOf(Transition.PRESENCE), inside.map { it.event.transition })
        assertEquals(at, inside.single().event.at)
        assertEquals(at, inside.single().observedLocationAt)
        assertEquals(at.plusSeconds(8), inside.single().receivedAt)
        assertEquals(18f, inside.single().accuracyMeters!!, 0f)
        val moved = adaptiveFacts("a", "b", at, at.plusSeconds(8), 12f) { "id-${++next}" }
        assertEquals(listOf("a" to Transition.ABSENCE, "b" to Transition.PRESENCE),
            moved.map { it.event.officeId to it.event.transition })
        assertEquals(listOf(Transition.ABSENCE), adaptiveFacts("a", null, at, at.plusSeconds(8), 12f) { "id-${++next}" }
            .map { it.event.transition })
    }

    @Test fun oldPreRecoveryVisitGetsFixTimeSplitInsteadOfAdaptiveContinuity() {
        val office = Office("a", "A", 0.0, 0.0)
        val opening = event("in", "a", Transition.ENTER, -3600)
        val exit = event("out", "a", Transition.EXIT)
        val snapshot = AppSnapshot(listOf(office), listOf(opening, exit), emptyList(), emptyList(), Policy())
        val coverage = CoverageLedger(recoveryBoundaryAt = at.minusSeconds(120))
        assertTrue(requiresAdaptiveRecoverySplit(snapshot, coverage, "out", "a", at.plusSeconds(60)))
        val fix = adaptiveFacts("a", "a", at.plusSeconds(60), at.plusSeconds(65), 15f,
            recoveryPresence = true) { "fix" }.single()
        assertEquals("ADAPTIVE_RECOVERY_CONFIRMATION", fix.source)
        val result = snapshot.copy(eventEvidence = snapshot.eventEvidence + fix).derive(at.plusSeconds(600))
        assertEquals(2, result.sessions.size)
        assertEquals(at.plusSeconds(60), result.sessions.last().start)
        assertEquals(at.plusSeconds(360), result.intervals.single().start)
    }
}
