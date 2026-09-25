package dev.hamstercage.capture

import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.TargetWindow
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.Transition
import dev.hamstercage.ui.dashboardPresence
import java.time.Instant
import java.time.ZoneId
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
        val coverage = CoverageLedger().registrationSucceeded(observed.minusSeconds(10), Policy().zoneId, true)
        assertNull(canOpenFromObservation(fresh, office.id, coverage, observed, observed.plusSeconds(1)))
        val entered = snapshot(listOf(event("reconcile", Transition.PRESENCE, observed)))
        assertEquals(ReconcileOutcome.ALREADY_PRESENT,
            canOpenFromObservation(entered, office.id, coverage.observed(observed),
                observed.plusSeconds(2), observed.plusSeconds(2)))
        val session = entered.derive(observed.plusSeconds(180)).sessions.single()
        assertEquals(observed, session.start)
        assertEquals(0.0, entered.derive(observed.plusSeconds(180)).intervals.sumOf { it.minutes }, 0.0)
        val afterGrace = entered.derive(observed.plusSeconds(360)).intervals.single()
        assertEquals(observed.plusSeconds(300), afterGrace.start)
        assertEquals(1.0, afterGrace.minutes, 0.0)
    }

    @Test fun laterExitMakesOldFixUnsafeAndNormalTransitionsDoNotOverlap() {
        val coverage = CoverageLedger().registrationSucceeded(observed.minusSeconds(10), Policy().zoneId, true)
        val exited = snapshot(listOf(event("enter", Transition.ENTER, observed),
            event("exit", Transition.EXIT, observed.plusSeconds(30))))
        assertEquals(ReconcileOutcome.STALE,
            canOpenFromObservation(exited, office.id, coverage, observed, observed.plusSeconds(31)))
        assertNull(canOpenFromObservation(exited, office.id, coverage,
            observed.plusSeconds(40), observed.plusSeconds(40)))
        val repeated = snapshot(listOf(event("reconcile", Transition.PRESENCE, observed),
            event("geofence-enter", Transition.ENTER, observed.plusSeconds(5)),
            event("geofence-exit", Transition.EXIT, observed.plusSeconds(90))))
        val stillInside = snapshot(listOf(event("reconcile", Transition.PRESENCE, observed),
            event("geofence-enter", Transition.ENTER, observed.plusSeconds(5))))
        val live = stillInside.derive(observed.plusSeconds(60))
        assertEquals("In Synthetic office", dashboardPresence(stillInside.input(observed.plusSeconds(60)), live, true).label)
        val result = repeated.derive(observed.plusSeconds(100))
        assertEquals(1, result.sessions.size)
        assertEquals(false, ReviewReason.REPEATED_ENTER in result.sessions.single().reviewReasons)
        assertEquals("Needs review", dashboardPresence(repeated.input(observed.plusSeconds(100)), result, true).label)
    }

    @Test fun latestGeofenceExitIsUncertainUntilIndependentOutsideFix() {
        val enter = event("enter", Transition.ENTER, observed.minusSeconds(600))
        val exit = event("exit", Transition.EXIT, observed)
        val pending = snapshot(listOf(enter, exit))
        val pendingNow = observed.plusSeconds(120)
        val pendingResult = pending.derive(pendingNow)
        assertEquals(setOf("exit"), pending.input(pendingNow).unconfirmedExitIds)
        assertEquals(1, pendingResult.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertEquals("Needs review", dashboardPresence(pending.input(pendingNow), pendingResult, true).label)
        val outside = snapshot(listOf(enter, exit, RecordedEvent(RawEvent("outside", office.id,
            Transition.ABSENCE, observed.plusSeconds(60)), observed.plusSeconds(60), observed.plusSeconds(60),
            "ADAPTIVE_LOCATION_CONFIRMATION", 12f)))
        val result = outside.derive(pendingNow)
        assertEquals(emptySet<String>(), outside.input(pendingNow).unconfirmedExitIds)
        assertEquals("Outside office", dashboardPresence(outside.input(pendingNow), result, true).label)
        assertEquals(0, result.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun rawOtherOfficeEnterDoesNotEraseUnconfirmedFirstOfficeExit() {
        val b = office.copy(id = "two", name = "B", latitude = 40.0)
        val facts = listOf(event("a-in", Transition.ENTER, observed.minusSeconds(600)),
            event("a-out", Transition.EXIT, observed),
            event("b-in", Transition.ENTER, observed.plusSeconds(30), b.id))
        val rawOnly = snapshot(facts, listOf(office, b))
        assertEquals(setOf("a-out"), rawOnly.input(observed.plusSeconds(60)).unconfirmedExitIds)
        assertEquals(1, rawOnly.derive(observed.plusSeconds(60)).reviews.count {
            it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        val confirmedB = snapshot(facts + RecordedEvent(RawEvent("b-fix", b.id, Transition.PRESENCE,
            observed.plusSeconds(60)), observed.plusSeconds(60), observed.plusSeconds(60),
            "ADAPTIVE_LOCATION_CONFIRMATION", 10f), listOf(office, b))
        assertEquals(emptySet<String>(), confirmedB.input(observed.plusSeconds(120)).unconfirmedExitIds)
        val futureB = snapshot(facts + RecordedEvent(RawEvent("future-b-fix", b.id, Transition.PRESENCE,
            observed.plusSeconds(600)), observed.plusSeconds(600), observed.plusSeconds(600),
            "ADAPTIVE_LOCATION_CONFIRMATION", 10f), listOf(office, b))
        assertEquals(setOf("a-out"), futureB.input(observed.plusSeconds(120)).unconfirmedExitIds)
    }

    @Test fun retainedPreOutageEnterIsSplitWithoutBackdatedOrOverlappingCredit() {
        val oldAt = observed.minusSeconds(1_200)
        val zone = Policy().zoneId
        val recovered = CoverageLedger(lastHealthyAt = oldAt, lastObservationAt = oldAt,
            registration = RegistrationStatus.ACTIVE, policyZoneId = zone)
            .outage(observed.minusSeconds(600), zone)
            .registrationSucceeded(observed.minusSeconds(30), zone, true)
        val old = event("old-enter", Transition.ENTER, oldAt)
        val before = snapshot(listOf(old))
        val now = observed.plusSeconds(360)
        assertEquals(false, recovered.presenceConfirmed(now, zone))
        assertNull(canOpenFromObservation(before, office.id, recovered, observed, now))
        val after = snapshot(listOf(old, event("current-fix", Transition.PRESENCE, observed)))
        val result = after.derive(now)
        assertEquals(2, result.sessions.size)
        val former = result.sessions.single { it.id == "session:old-enter" }
        val current = result.sessions.single { it.id == "session:current-fix" }
        assertEquals(observed, former.end)
        assertEquals(true, ReviewReason.UNCONFIRMED_GAP in former.reviewReasons)
        assertEquals(observed, current.start)
        assertEquals(true, current.isOpen)
        assertEquals(listOf(observed.plusSeconds(300)), result.intervals.map { it.start })
        assertEquals(1.0, result.intervals.single().minutes, 0.0)
        assertEquals(true, recovered.observed(observed).presenceConfirmed(now, zone))
        assertEquals("In Synthetic office", dashboardPresence(after.input(now), result, true).label)
        assertEquals(true, dashboardPresence(after.input(now), result, true).needsReview)
        val projected = AttendanceEngine.departure(after.input(now), result, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, projected.status)
        assertEquals(ReconcileOutcome.ALREADY_PRESENT,
            canOpenFromObservation(after, office.id, recovered.observed(observed), now, now))
    }

    @Test fun otherOfficeObservationCannotConfirmOldOpenOfficeAcrossRecovery() {
        val second = office.copy(id = "two", name = "Second office", latitude = 40.0)
        val zone = Policy().zoneId
        val oldAt = observed.minusSeconds(2_400)
        val boundary = observed.minusSeconds(1_200)
        val otherIn = observed.minusSeconds(900)
        val otherOut = observed.minusSeconds(300)
        val recovered = CoverageLedger(lastHealthyAt = oldAt, lastObservationAt = oldAt,
            registration = RegistrationStatus.ACTIVE, policyZoneId = zone)
            .outage(boundary.minusSeconds(300), zone)
            .registrationSucceeded(boundary, zone, true).observed(otherOut)
        val oldA = event("old-a", Transition.ENTER, oldAt)
        val closedB = listOf(event("b-in", Transition.ENTER, otherIn, second.id),
            event("b-out", Transition.EXIT, otherOut, second.id))
        val before = snapshot(listOf(oldA) + closedB, listOf(office, second))
        val now = observed.plusSeconds(360)
        assertEquals(true, recovered.presenceConfirmed(now, zone))
        assertNull(canOpenFromObservation(before, office.id, recovered, observed, now))
        val after = snapshot(listOf(oldA) + closedB + event("fix-a", Transition.PRESENCE, observed),
            listOf(office, second))
        val result = after.derive(now)
        assertEquals(3, result.sessions.size)
        val oldSession = result.sessions.single { it.id == "session:old-a" }
        val newSession = result.sessions.single { it.id == "session:fix-a" }
        assertEquals(observed, oldSession.end)
        assertEquals(true, ReviewReason.UNCONFIRMED_GAP in oldSession.reviewReasons)
        assertEquals(observed, newSession.start)
        assertEquals(true, newSession.isOpen)
        assertEquals(0.0, result.intervals.filter { "session:old-a" in it.sessionIds }.sumOf { it.minutes }, 0.0)
        assertEquals(6.0, result.intervals.sumOf { it.minutes }, 0.0)
        assertEquals("In Synthetic office", dashboardPresence(after.input(now), result, true).label)
        assertEquals(ReconcileOutcome.ALREADY_PRESENT,
            canOpenFromObservation(after, office.id, recovered.observed(observed), now, now))
    }

    @Test fun postRecoveryAOpeningIsNotContinuousAfterClosedBVisit() {
        val second = office.copy(id = "two", name = "Second office", latitude = 40.0)
        val zone = Policy().zoneId
        val boundary = observed.minusSeconds(1_200)
        val aIn = observed.minusSeconds(1_000)
        val bIn = observed.minusSeconds(900)
        val bOut = observed.minusSeconds(300)
        val recovered = CoverageLedger(lastHealthyAt = boundary.minusSeconds(600),
            lastObservationAt = boundary.minusSeconds(600), registration = RegistrationStatus.ACTIVE,
            policyZoneId = zone).outage(boundary.minusSeconds(300), zone)
            .registrationSucceeded(boundary, zone, true).observed(bOut)
        val a = event("a-in", Transition.ENTER, aIn)
        val b = listOf(event("b-in", Transition.ENTER, bIn, second.id),
            event("b-out", Transition.EXIT, bOut, second.id))
        val before = snapshot(listOf(a) + b, listOf(office, second))
        val now = observed.plusSeconds(360)
        assertEquals(true, recovered.presenceConfirmed(now, zone))
        assertNull(canOpenFromObservation(before, office.id, recovered, observed, now))
        val after = snapshot(listOf(a) + b + event("a-fix", Transition.PRESENCE, observed),
            listOf(office, second))
        val result = after.derive(now)
        assertEquals(3, result.sessions.size)
        val oldA = result.sessions.single { it.id == "session:a-in" }
        val newA = result.sessions.single { it.id == "session:a-fix" }
        assertEquals(observed, oldA.end)
        assertEquals(true, ReviewReason.UNCONFIRMED_GAP in oldA.reviewReasons)
        assertEquals(observed, newA.start)
        assertEquals(true, newA.isOpen)
        assertEquals(0.0, result.intervals.filter { "session:a-in" in it.sessionIds }.sumOf { it.minutes }, 0.0)
        assertEquals(6.0, result.intervals.sumOf { it.minutes }, 0.0)
        assertEquals("In Synthetic office", dashboardPresence(after.input(now), result, true).label)
        assertEquals(ReconcileOutcome.ALREADY_PRESENT,
            canOpenFromObservation(after, office.id, recovered.observed(observed), now, now))
        val sameTimeBExit = snapshot(listOf(a, b.first(),
            event("b-out", Transition.EXIT, observed, second.id)), listOf(office, second))
        assertNull(canOpenFromObservation(sameTimeBExit, office.id, recovered.observed(observed), observed, now))
    }

    @Test fun repeatedSameOfficeEnterCorroboratesHealthyCurrentVisit() {
        val zone = Policy().zoneId
        val boundary = observed.minusSeconds(600)
        val first = observed.minusSeconds(300)
        val repeated = observed.minusSeconds(60)
        val coverage = CoverageLedger().registrationSucceeded(boundary, zone, true).observed(repeated)
        val snapshot = snapshot(listOf(event("first", Transition.ENTER, first),
            event("repeat", Transition.ENTER, repeated)))
        assertEquals(false, ReviewReason.REPEATED_ENTER in snapshot.derive(observed).sessions.single().reviewReasons)
        assertEquals(ReconcileOutcome.ALREADY_PRESENT,
            canOpenFromObservation(snapshot, office.id, coverage, observed, observed.plusSeconds(1)))
    }

    @Test fun cachedPreRegistrationFixCannotClaimVisiblePresenceButPostBoundaryFixCan() {
        val zone = ZoneId.of("America/New_York")
        val boundary = observed
        val registered = CoverageLedger().registrationSucceeded(boundary, zone, hasOffices = true)
        val now = observed.plusSeconds(10)
        val cached = observed.minusSeconds(5)
        val requested = boundary.plusSeconds(1)
        assertEquals(ReconcileOutcome.STALE, recoveryObservationGate(registered, cached, requested, now, zone))
        assertEquals(false, registered.observed(cached).presenceConfirmed(now, zone))
        val postBoundary = observed.plusSeconds(2)
        assertNull(recoveryObservationGate(registered, postBoundary, requested, now, zone))
        assertEquals(true, registered.observed(postBoundary).presenceConfirmed(now, zone))
        assertEquals(ReconcileOutcome.STALE,
            recoveryObservationGate(registered, boundary, requested, now, zone))
        assertEquals(ReconcileOutcome.NOT_REGISTERED,
            recoveryObservationGate(registered.outage(now, zone), postBoundary, requested, now, zone))
    }

    private fun event(id: String, transition: Transition, at: Instant, officeId: String = office.id) = RecordedEvent(
        RawEvent(id, officeId, transition, at), at, at,
        if (transition == Transition.PRESENCE) "FOREGROUND_LOCATION_RECONCILIATION" else "PLAY_SERVICES_GEOFENCE")

    private fun snapshot(events: List<RecordedEvent> = emptyList(), offices: List<Office> = listOf(office)) =
        AppSnapshot(offices, events, emptyList(), emptyList(), Policy())
}
