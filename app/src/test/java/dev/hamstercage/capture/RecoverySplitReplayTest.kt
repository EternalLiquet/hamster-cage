package dev.hamstercage.capture

import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.RecoverySplitFixture
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.TargetWindow
import dev.hamstercage.domain.Transition
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transformed export of one uninterrupted office-2 visit. Every routine background check wrote
 * an office-1 ABSENCE beside its office-2 fix; the next check read that ABSENCE as the user
 * being seen elsewhere, so it wrote a recovery PRESENCE that split the visit and restarted
 * arrival grace. Each split visit then earned nothing.
 */
class RecoverySplitReplayTest {
    private val fixture = RecoverySplitFixture
    private val exported = fixture.snapshot()
    private val office2 = "office-2"
    private val background = "BACKGROUND_LOCATION_RECONCILIATION"

    @Test fun exportedFactsReproduceTheSplitDay() {
        val input = fixture.input(exported)
        val result = AttendanceEngine.derive(input)
        val visits = result.sessions.filter { it.officeId == office2 && it.start != null }
        assertEquals(5, visits.size)
        assertTrue(visits.dropLast(1).all { ReviewReason.UNCONFIRMED_GAP in it.reviewReasons })
        assertEquals(0.0, AttendanceEngine.daily(input, result, fixture.selectedDay).creditedMinutes, 0.0)
    }

    @Test fun inVisitCheckNoLongerTreatsTheOtherOfficeAbsenceAsElsewhere() {
        // The first check that split: an office-1 ABSENCE written by the previous check sits
        // after the office-2 opening.
        val splitAt = exported.eventEvidence.filter { it.source == background &&
            it.event.transition == Transition.PRESENCE }.minOf { it.event.at }
        val before = exported.copy(eventEvidence = exported.eventEvidence.filter { it.event.at < splitAt ||
            (it.event.at == splitAt && it.source != background) })
        assertTrue(before.events.any { it.officeId != office2 && it.transition == Transition.ABSENCE &&
            it.at > before.derive(splitAt).sessions.single { s -> s.isOpen }.start })
        val now = splitAt.plusSeconds(1)
        val coverage = ledger(splitAt)
        assertEquals(ReconcileOutcome.ALREADY_PRESENT, canOpenFromObservation(before, office2, coverage, splitAt, now))
        assertTrue(reconciliationFacts(before, coverage, now, splitAt, office2)
            .none { it.event.officeId == office2 })
    }

    @Test fun replayedChecksKeepOneVisitAndCreditItFromTheFirstArrival() {
        // Platform and adaptive facts are kept; each background check is rerun at its own time.
        val checks = exported.eventEvidence.filter { it.source == background }.groupBy { it.event.at }.toSortedMap()
        var evidence = exported.eventEvidence.filter { it.source != background }
        checks.forEach { (at, original) ->
            val snapshot = exported.copy(eventEvidence = evidence.filter { it.event.at <= at })
            // A check that found the visit already open wrote only the other office's ABSENCE;
            // its fix is the one the same-instant platform ENTER reports.
            val inside = (original + snapshot.eventEvidence.filter { it.event.at == at })
                .firstOrNull { it.event.transition in setOf(Transition.ENTER, Transition.PRESENCE) }?.event?.officeId
            evidence = evidence + reconciliationFacts(snapshot, ledger(at), original.maxOf { it.receivedAt }, at, inside)
        }
        val replayed = exported.copy(eventEvidence = evidence)
        val input = fixture.input(replayed)
        val result = AttendanceEngine.derive(input)
        val visit = result.sessions.single { it.officeId == office2 && it.start != null }
        val arrival = exported.events.filter { it.officeId == office2 && it.transition == Transition.ENTER }.minOf { it.at }
        assertEquals(arrival, visit.start)
        assertEquals(setOf(ReviewReason.OPEN_SESSION), visit.reviewReasons)
        assertTrue(evidence.none { it.source == background && it.event.transition == Transition.PRESENCE })
        // No office-1 fact follows its morning outside check, so no further ABSENCE is added.
        assertEquals(1, evidence.count { it.source == background && it.event.officeId == "office-1" })
        val credited = AttendanceEngine.daily(input, result, fixture.selectedDay).creditedMinutes
        assertEquals(Duration.between(arrival.plusSeconds(300), fixture.evaluatedAt).toNanos() / 60e9, credited, 1e-9)
        assertEquals(DepartureStatus.ESTIMATED, AttendanceEngine.departure(input, result, TargetWindow.TODAY).status)
    }

    @Test fun supersededUncertainExitNoLongerAddsAnAbsenceToEveryCheck() {
        val zone = fixture.zone
        val a = exported.offices.first { it.id == "office-1" }
        val enter = RawEvent("a-in", a.id, Transition.ENTER, Instant.parse("2025-11-03T14:00:00Z"))
        val exit = RawEvent("a-out", a.id, Transition.EXIT, Instant.parse("2025-11-03T20:00:00Z"))
        // Expired undecided verification leaves this EXIT uncertain once a return follows.
        val back = RawEvent("a-back", a.id, Transition.ENTER, Instant.parse("2025-11-03T21:00:00Z"))
        val left = RawEvent("a-left", a.id, Transition.ABSENCE, Instant.parse("2025-11-03T22:00:00Z"))
        val snapshot = AppSnapshot(exported.offices, listOf(enter, exit, back, left).map { RecordedEvent(it, it.at) },
            emptyList(), emptyList(), exported.policy.copy(zoneId = zone))
        val check = Instant.parse("2025-11-04T15:00:00Z")
        assertTrue(exit.id in snapshot.input(check).unconfirmedExitIds)
        assertTrue(reconciliationFacts(snapshot, ledger(check), check, check, null).isEmpty())
        // The latest uncertain boundary is still resolved by an outside fix.
        val latest = snapshot.copy(eventEvidence = snapshot.eventEvidence.take(3))
        assertTrue(reconciliationFacts(latest, ledger(check), check, check, null)
            .any { it.event.officeId == a.id && it.event.transition == Transition.ABSENCE })
    }

    private fun ledger(at: Instant) = CoverageLedger()
        .registrationSucceeded(at.minus(Duration.ofDays(30)), fixture.zone, true).observed(at)
}
