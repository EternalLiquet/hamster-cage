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

    @Test fun oneMultiOfficeDeliverySelectsOneJobAndOneFixCanReconcileWholeBatch() {
        val a = event("a-enter", "a", Transition.ENTER)
        val b = event("b-enter", "b", Transition.ENTER)
        val snapshot = AppSnapshot(listOf(Office("a", "A", 0.0, 0.0),
            Office("b", "B", 1.0, 1.0)), listOf(a, b), emptyList(), emptyList(), Policy())
        val selected = AdaptiveConfirmation.selectDelivery(listOf(a, b), setOf(a.event.id, b.event.id), snapshot)!!
        assertEquals(2, selected.events.size)
        assertEquals(listOf(a, b), AdaptiveConfirmation.batch(snapshot.eventEvidence,
            selected.representative.event.id))
        var next = 0
        val facts = adaptiveFacts(selected.events.map { it.event.officeId }, "b", at.plusSeconds(60),
            at.plusSeconds(65), 12f) { "fix-${++next}" }
        assertEquals(listOf("a" to Transition.ABSENCE, "b" to Transition.PRESENCE),
            facts.map { it.event.officeId to it.event.transition })
    }

    @Test fun workerGateRejectsNewerEventEditOptOutPermissionLossAndReset() {
        val a = event("a-exit", "a", Transition.EXIT)
        val b = event("b-exit", "b", Transition.EXIT)
        val offices = listOf(Office("a", "A", 0.0, 0.0), Office("b", "B", 1.0, 1.0))
        val snapshot = AppSnapshot(offices, listOf(a, b), emptyList(), emptyList(), Policy())
        val versions = mapOf("a" to 1L, "b" to 1L)
        val candidate = AdaptiveConfirmation.Candidate("b-exit", "b", 7,
            AdaptiveConfirmation.fingerprint(versions))
        val setup = LocationSetup(true, true, true, true, true)
        val coverage = CoverageLedger(registration = RegistrationStatus.ACTIVE)
        fun eligible(state: AppSnapshot = snapshot, saved: Map<String, Long> = versions,
            generation: Long = 7, enabled: Boolean = true, location: LocationSetup = setup,
            ledger: CoverageLedger = coverage) = eligibleAdaptiveBatch(state, candidate,
            saved, generation, enabled, true, location, ledger)
        assertEquals(2, eligible()!!.size)
        assertEquals(null, eligible(generation = 8))
        assertEquals(null, eligibleAdaptiveBatch(snapshot, candidate, versions, 7, true,
            false, setup, coverage))
        assertEquals(null, eligible(saved = versions + ("a" to 2L)))
        assertEquals(null, eligible(enabled = false))
        assertEquals(null, eligible(location = setup.copy(fineLocation = false)))
        assertEquals(null, eligible(ledger = coverage.copy(registration = RegistrationStatus.FAILED)))
        assertEquals(null, eligible(state = snapshot.copy(offices = listOf(offices[0].copy(enabled = false), offices[1]))))
        assertEquals(null, eligible(state = snapshot.copy(eventEvidence = snapshot.eventEvidence +
            event("newer", "a", Transition.ENTER, 1))))
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

    @Test fun fixInOtherOfficeSplitsItsOldOpenVisitAfterCandidateOfficeEvidence() {
        val a = Office("a", "A", 0.0, 0.0)
        val b = Office("b", "B", 1.0, 1.0)
        val facts = listOf(event("b-old", "b", Transition.ENTER, -3600),
            event("a-in", "a", Transition.ENTER, -1800),
            event("a-out", "a", Transition.EXIT))
        val snapshot = AppSnapshot(listOf(a, b), facts, emptyList(), emptyList(), Policy())
        val coverage = CoverageLedger(recoveryBoundaryAt = at.minusSeconds(120))
        assertTrue(requiresAdaptiveRecoverySplit(snapshot, coverage, "a-out", "b", at.plusSeconds(60)))
        var next = 0
        val fix = adaptiveFacts("a", "b", at.plusSeconds(60), at.plusSeconds(65), 12f,
            recoveryPresence = true) { "fix-${++next}" }
        val result = snapshot.copy(eventEvidence = facts + fix).derive(at.plusSeconds(600))
        assertEquals(at.plusSeconds(60), result.sessions.single { it.id == "session:fix-2" }.start)
        assertEquals(0.0, result.intervals.filter { "session:b-old" in it.sessionIds }.sumOf { it.minutes }, 0.0)
    }
}
