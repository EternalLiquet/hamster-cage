package dev.hamstercage.capture

import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundReconciliationTest {
    private val office = Office("one", "Synthetic office", 39.0, -86.0,
        radiusMeters = 150f, entryGraceMinutes = 5)
    private val observed = Instant.parse("2026-09-24T14:00:00Z")

    @Test fun freshInsideAndOutsideAreDistinguished() {
        assertEquals(ReconcileOutcome.CONFIRMED to office,
            decidePresence(listOf(office), 39.0, -86.0, 15f, 500))
        assertEquals(ReconcileOutcome.OUTSIDE to null,
            decidePresence(listOf(office), 39.01, -86.0, 15f, 500))
        assertEquals(ReconcileOutcome.UNCERTAIN_BOUNDARY to null,
            decidePresence(listOf(office), 39.0012, -86.0, 30f, 500))
    }

    @Test fun staleInaccurateAndIneligibleCannotConfirm() {
        assertEquals(ReconcileOutcome.STALE to null,
            decidePresence(listOf(office), 39.0, -86.0, 10f, 30_001))
        assertEquals(ReconcileOutcome.INACCURATE to null,
            decidePresence(listOf(office), 39.0, -86.0, 101f, 100))
        assertEquals(ReconcileOutcome.OUTSIDE to null,
            decidePresence(listOf(office.copy(enabled = false)), 39.0, -86.0, 10f, 100))
        assertEquals(ReconcileOutcome.OUTSIDE to null,
            decidePresence(listOf(office.copy(countsTowardAttendance = false)), 39.0, -86.0, 10f, 100))
    }

    @Test fun overlappingOfficesCannotChooseArbitrarily() {
        assertEquals(ReconcileOutcome.OVERLAPPING_OFFICES to null,
            decidePresence(listOf(office, office.copy(id = "two")), 39.0, -86.0, 10f, 100))
    }

    @Test fun observationOpensOnlyAtFixTimeAndRepeatedEnterDoesNotOpenAgain() {
        val fresh = snapshot()
        assertNull(canOpenFromObservation(fresh, observed, observed.plusSeconds(1)))
        val entered = snapshot(listOf(event("reconcile", Transition.ENTER, observed)))
        assertEquals(ReconcileOutcome.ALREADY_PRESENT,
            canOpenFromObservation(entered, observed.plusSeconds(2), observed.plusSeconds(2)))
        val session = entered.derive(observed.plusSeconds(180)).sessions.single()
        assertEquals(observed, session.start)
        assertEquals(0.0, entered.derive(observed.plusSeconds(180)).intervals.sumOf { it.minutes }, 0.0)
        val afterGrace = entered.derive(observed.plusSeconds(360)).intervals.single()
        assertEquals(observed.plusSeconds(300), afterGrace.start)
        assertEquals(1.0, afterGrace.minutes, 0.0)
    }

    @Test fun laterExitMakesOldFixUnsafeAndNormalTransitionsDoNotOverlap() {
        val exited = snapshot(listOf(event("enter", Transition.ENTER, observed),
            event("exit", Transition.EXIT, observed.plusSeconds(30))))
        assertEquals(ReconcileOutcome.STALE,
            canOpenFromObservation(exited, observed, observed.plusSeconds(31)))
        assertNull(canOpenFromObservation(exited, observed.plusSeconds(40), observed.plusSeconds(40)))
        val repeated = snapshot(listOf(event("reconcile", Transition.ENTER, observed),
            event("geofence-enter", Transition.ENTER, observed.plusSeconds(5)),
            event("geofence-exit", Transition.EXIT, observed.plusSeconds(90))))
        assertEquals(1, repeated.derive(observed.plusSeconds(100)).sessions.size)
    }

    private fun event(id: String, transition: Transition, at: Instant) = RecordedEvent(
        RawEvent(id, office.id, transition, at), at, at, "FOREGROUND_LOCATION_RECONCILIATION")

    private fun snapshot(events: List<RecordedEvent> = emptyList()) =
        AppSnapshot(listOf(office), events, emptyList(), emptyList(), Policy())
}
