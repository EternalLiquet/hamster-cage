package dev.hamstercage.data

import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.Transition
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ExitVerificationTest {
    private val zero = Instant.parse("2030-01-07T13:00:00Z")
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun fact(id: String, transition: Transition, minute: Long,
        source: String = "PLAY_SERVICES_GEOFENCE") = RecordedEvent(
            RawEvent(id, "a", transition, zero.plusSeconds(minute * 60)),
            zero.plusSeconds(minute * 60), source = source,
            accuracyMeters = if (source == "PLAY_SERVICES_GEOFENCE") null else 15f)
    private val enter = fact("enter", Transition.ENTER, 0)
    private val exit = fact("exit", Transition.EXIT, 120)
    private fun state(facts: List<RecordedEvent>, minute: Long) = AppSnapshot(
        listOf(office), facts, emptyList(), emptyList(), Policy())
            .let { it.input(zero.plusSeconds(minute * 60)) to it.derive(zero.plusSeconds(minute * 60)) }

    @Test fun fiveInsideSamplesRejectPhantomExitWithoutRestartingGrace() {
        val inside = (1L..5L).map { fact("inside-$it", Transition.PRESENCE, 120 + it, EXIT_VERIFY_INSIDE) }
        val facts = listOf(enter, exit) + inside
        val pending = state(facts, 124)
        assertEquals(setOf("exit"), pending.first.candidateExitIds)
        assertEquals(115.0, pending.second.intervals.single().minutes, 0.001)
        assertTrue(pending.second.sessions.single().isOpen)
        val settled = state(facts, 126)
        assertEquals(setOf("exit"), settled.first.rejectedExitIds)
        assertEquals(emptySet<String>(), settled.first.candidateExitIds)
        assertEquals(1, settled.second.sessions.size)
        assertTrue(settled.second.sessions.single().isOpen)
        assertTrue(settled.second.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        assertEquals(121.0, settled.second.intervals.single().minutes, 0.001)
    }

    @Test fun earlyInsideFixDoesNotDiscardGenuineDeparture() {
        val facts = listOf(enter, exit,
            fact("early-inside", Transition.PRESENCE, 121, EXIT_VERIFY_INSIDE),
            fact("outside-1", Transition.ABSENCE, 122, EXIT_VERIFY_OUTSIDE),
            fact("outside-2", Transition.ABSENCE, 123, EXIT_VERIFY_OUTSIDE),
            fact("return", Transition.ENTER, 160))
        val result = state(facts, 180)
        assertTrue(result.first.candidateExitIds.isEmpty())
        assertTrue(result.first.rejectedExitIds.isEmpty())
        assertEquals(2, result.second.sessions.size)
        assertEquals(exit.event.at, result.second.sessions.first().end)
        assertEquals(facts.last().event.at, result.second.sessions.last().start)
        assertEquals(130.0, result.second.intervals.sumOf { it.minutes }, 0.001)
    }

    @Test fun missingOrOneOutsideSampleLeavesOneOpenUnresolvedCandidate() {
        val facts = listOf(enter, exit,
            fact("outside-once", Transition.ABSENCE, 121, EXIT_VERIFY_OUTSIDE))
        val result = state(facts, 130)
        assertEquals(setOf("exit"), result.first.candidateExitIds)
        assertEquals(setOf("outside-once"), result.first.provisionalAbsenceIds)
        assertEquals(1, result.second.sessions.size)
        assertTrue(result.second.sessions.single().isOpen)
        assertEquals(115.0, result.second.intervals.single().minutes, 0.001)
        assertEquals(1, result.second.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun historicalPendingExitDoesNotErasePreExitCreditAsStale() {
        val result = state(listOf(enter, exit), 3_000)
        assertEquals(setOf("exit"), result.first.candidateExitIds)
        assertFalse(ReviewReason.STALE_OPEN_SESSION in result.second.sessions.single().reviewReasons)
        assertEquals(115.0, result.second.intervals.single().minutes, 0.001)
    }

    @Test fun laterReconciliationOutsideSettlesCandidateAtOriginalExitBoundary() {
        val facts = listOf(enter, exit,
            fact("outside-later", Transition.ABSENCE, 130, "BACKGROUND_LOCATION_RECONCILIATION"))
        val result = state(facts, 131)
        assertTrue(result.first.candidateExitIds.isEmpty())
        assertEquals(exit.event.at, result.second.sessions.single().end)
        assertTrue(result.second.reviews.none { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun quickExitEnterFlapKeepsOneVisit() {
        val quickReturn = RecordedEvent(RawEvent("quick-return", "a", Transition.ENTER,
            exit.event.at.plusSeconds(20)), exit.event.at.plusSeconds(20))
        val result = AppSnapshot(listOf(office), listOf(enter, exit, quickReturn),
            emptyList(), emptyList(), Policy()).derive(exit.event.at.plusSeconds(60))
        assertEquals(1, result.sessions.size)
        assertTrue(result.sessions.single().isOpen)
        assertEquals(116.0, result.intervals.single().minutes, 0.001)
    }

    @Test fun lateReturnCannotEraseUnconfirmedDeparture() {
        val lateReturn = fact("return", Transition.ENTER, 130)
        val noSamples = state(listOf(enter, exit, lateReturn), 140)
        assertTrue(noSamples.first.candidateExitIds.isEmpty())
        assertEquals(setOf("exit"), noSamples.first.unconfirmedExitIds)
        assertEquals(2, noSamples.second.sessions.size)
        assertEquals(1, noSamples.second.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })

        val oneSample = state(listOf(enter, exit,
            fact("outside-once", Transition.ABSENCE, 121, EXIT_VERIFY_OUTSIDE), lateReturn), 140)
        assertEquals(setOf("exit"), oneSample.first.unconfirmedExitIds)
        assertEquals(2, oneSample.second.sessions.size)
        assertEquals(1, oneSample.second.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })

        val lateInside = state(listOf(enter, exit,
            fact("recheck", Transition.PRESENCE, 130, "BACKGROUND_LOCATION_RECONCILIATION")), 140)
        assertEquals(setOf("exit"), lateInside.first.unconfirmedExitIds)
        assertEquals(2, lateInside.second.sessions.size)
        assertEquals(1, lateInside.second.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun bunchedInsideSamplesCannotRejectCandidate() {
        val bunched = (1..5).map { index -> RecordedEvent(
            RawEvent("inside-$index", "a", Transition.PRESENCE,
                exit.event.at.plusSeconds(270 + index.toLong())),
            exit.event.at.plusSeconds(270 + index.toLong()), source = EXIT_VERIFY_INSIDE,
            accuracyMeters = 12f) }
        val result = state(listOf(enter, exit) + bunched, 126)
        assertEquals(setOf("exit"), result.first.candidateExitIds)
        assertTrue(result.first.rejectedExitIds.isEmpty())
    }

    @Test fun alternatingInsideOutsideSamplesRemainUnresolved() {
        val facts = listOf(enter, exit,
            fact("outside-1", Transition.ABSENCE, 121, EXIT_VERIFY_OUTSIDE),
            RecordedEvent(RawEvent("inside", "a", Transition.PRESENCE,
                exit.event.at.plusSeconds(90)), exit.event.at.plusSeconds(90),
                source = EXIT_VERIFY_INSIDE, accuracyMeters = 12f),
            fact("outside-2", Transition.ABSENCE, 122, EXIT_VERIFY_OUTSIDE))
        val result = state(facts, 126)
        assertEquals(setOf("exit"), result.first.candidateExitIds)
        assertEquals(1, result.second.reviews.count { it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
    }

    @Test fun insideAfterConfirmedOutsideStartsFixTimeReturn() {
        val facts = listOf(enter, exit,
            fact("outside-1", Transition.ABSENCE, 121, EXIT_VERIFY_OUTSIDE),
            fact("outside-2", Transition.ABSENCE, 122, EXIT_VERIFY_OUTSIDE),
            fact("inside-return", Transition.PRESENCE, 123, EXIT_VERIFY_INSIDE))
        val result = state(facts, 130)
        assertTrue(result.first.candidateExitIds.isEmpty())
        assertFalse("inside-return" in result.first.provisionalPresenceIds)
        assertEquals(2, result.second.sessions.size)
        assertEquals(exit.event.at, result.second.sessions.first().end)
        assertEquals(facts.last().event.at, result.second.sessions.last().start)
    }

    @Test fun earlyRecoveryPresenceDoesNotFalselyRejectExit() {
        val early = RecordedEvent(RawEvent("early", "a", Transition.PRESENCE,
            exit.event.at.plusSeconds(30)), exit.event.at.plusSeconds(30),
            source = "BACKGROUND_LOCATION_RECONCILIATION", accuracyMeters = 12f)
        val facts = listOf(enter, exit, early,
            fact("outside-1", Transition.ABSENCE, 122, EXIT_VERIFY_OUTSIDE),
            fact("outside-2", Transition.ABSENCE, 123, EXIT_VERIFY_OUTSIDE))
        val result = state(facts, 126)
        assertTrue(result.first.candidateExitIds.isEmpty())
        assertFalse("exit" in result.first.rejectedExitIds)
        assertTrue(result.first.provisionalPresenceIds.isEmpty())
        assertEquals(setOf("exit"), result.first.unconfirmedExitIds)
        assertEquals(2, result.second.sessions.size)
        assertEquals(exit.event.at, result.second.sessions.first().end)
        assertEquals(early.event.at, result.second.sessions.last().start)
    }

    @Test fun laterExitAfterRejectedPhantomNeedsItsOwnEvidence() {
        val firstInside = (1L..5L).map {
            fact("first-inside-$it", Transition.PRESENCE, 120 + it, EXIT_VERIFY_INSIDE)
        }
        val laterExit = fact("later-exit", Transition.EXIT, 150)
        val result = state(listOf(enter, exit) + firstInside + laterExit, 151)
        assertEquals(setOf("exit"), result.first.rejectedExitIds)
        assertEquals(setOf("later-exit"), result.first.candidateExitIds)
        assertEquals(1, result.second.sessions.size)
        assertTrue(result.second.sessions.single().isOpen)
        assertEquals(laterExit.event.at, result.second.intervals.single().end)
    }

    @Test fun delayedExitDeliveryCannotBridgeUnobservedHour() {
        val delayedExit = exit.copy(receivedAt = exit.event.at.plusSeconds(3600))
        val laterInside = (1L..5L).map {
            fact("later-inside-$it", Transition.PRESENCE, 180 + it, EXIT_VERIFY_INSIDE)
        }
        val pending = state(listOf(enter, delayedExit) + laterInside, 186)
        assertEquals(setOf("exit"), pending.first.candidateExitIds)
        assertEquals(setOf("exit"), pending.first.delayedExitIds)
        assertTrue(pending.first.rejectedExitIds.isEmpty())
        assertEquals(exit.event.at, pending.second.intervals.single().end)
        val recovery = fact("recovered", Transition.PRESENCE, 181,
            "ADAPTIVE_RECOVERY_CONFIRMATION")
        val resumed = state(listOf(enter, delayedExit, recovery), 190)
        assertEquals(recovery.event.at, resumed.second.sessions.last().start)
        assertTrue(resumed.second.intervals.none { it.start < recovery.event.at })
        val quick = RecordedEvent(RawEvent("quick", "a", Transition.ENTER,
            exit.event.at.plusSeconds(20)), delayedExit.receivedAt.plusSeconds(20))
        val uncertain = state(listOf(enter, delayedExit, quick), 186)
        assertEquals(setOf("exit"), uncertain.first.unconfirmedExitIds)
        assertEquals(2, uncertain.second.sessions.size)
    }

    @Test fun otherOfficeArrivalCapsPreviouslyRejectedExitVisit() {
        val inside = (1L..5L).map {
            fact("inside-$it", Transition.PRESENCE, 120 + it, EXIT_VERIFY_INSIDE)
        }
        val b = office.copy(id = "b", name = "Synthetic second office", latitude = 1.0)
        val bArrival = RecordedEvent(RawEvent("b-enter", "b", Transition.ENTER,
            zero.plusSeconds(150 * 60)), zero.plusSeconds(150 * 60))
        val snapshot = AppSnapshot(listOf(office, b), listOf(enter, exit) + inside + bArrival,
            emptyList(), emptyList(), Policy())
        val input = snapshot.input(zero.plusSeconds(180 * 60))
        val result = snapshot.derive(input.now)
        assertEquals(setOf("exit"), input.rejectedExitIds)
        val a = result.sessions.single { it.officeId == "a" }
        assertTrue(a.isOpen)
        assertTrue(ReviewReason.UNCONFIRMED_BOUNDARY in a.reviewReasons)
        assertEquals(bArrival.event.at, result.intervals.first().end)
        assertEquals(170.0, result.intervals.sumOf { it.minutes }, 0.001)
        for (returnType in listOf(Transition.ENTER, Transition.PRESENCE)) {
            val returning = RecordedEvent(RawEvent("a-return", "a", returnType,
                zero.plusSeconds(180 * 60)), zero.plusSeconds(180 * 60),
                source = if (returnType == Transition.ENTER) "PLAY_SERVICES_GEOFENCE"
                    else EXIT_VERIFY_INSIDE)
            val resumed = snapshot.copy(eventEvidence = snapshot.eventEvidence + returning)
                .derive(zero.plusSeconds(210 * 60))
            val aVisits = resumed.sessions.filter { it.officeId == "a" }
            assertEquals(2, aVisits.size)
            assertEquals(returning.event.at, aVisits.last().start)
            assertTrue(aVisits.last().isOpen)
            assertTrue(resumed.intervals.any { aVisits.last().id in it.sessionIds &&
                it.end == zero.plusSeconds(210 * 60) })
        }
    }
}
