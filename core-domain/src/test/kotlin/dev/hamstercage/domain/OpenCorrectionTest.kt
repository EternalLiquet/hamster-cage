package dev.hamstercage.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** A blank correction end records presence as of the edit; later departure evidence still closes it. */
class OpenCorrectionTest {
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    private val other = Office("other", "Other office", 1.0, 1.0)
    private fun at(value: String) = Instant.parse(value)
    private val enter = RawEvent("in", office.id, Transition.ENTER, at("2026-03-04T14:00:00Z"))
    private val edit = Correction("edit", "session:in", at("2026-03-04T14:00:00Z"), null, at("2026-03-04T16:57:00Z"))

    private fun derive(events: List<RawEvent>, now: Instant, corrections: List<Correction> = listOf(edit),
        offices: List<Office> = listOf(office), rejected: Set<String> = emptySet(),
        unconfirmed: Set<String> = emptySet(), candidate: Set<String> = emptySet()) =
        AttendanceEngine.derive(AttendanceInput(offices, events, corrections, now = now,
            rejectedExitIds = rejected, unconfirmedExitIds = unconfirmed, candidateExitIds = candidate))

    @Test fun exitObservedAfterAnOpenEditClosesTheCorrectedVisit() {
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val result = derive(listOf(enter, exit), at("2026-03-07T19:00:00Z"))
        val session = result.sessions.single()
        assertEquals(at("2026-03-04T14:00:00Z"), session.start)
        assertEquals(exit.at, session.end)
        assertEquals(Confidence.MANUAL, session.confidence)
        assertTrue(session.reviewReasons.isEmpty())
        assertTrue(result.reviews.none { it.sessionId == session.id })
        // Credit starts after the 5-minute arrival grace and ends at the observed EXIT.
        assertEquals(at("2026-03-04T14:05:00Z"), result.intervals.single().start)
        assertEquals(exit.at, result.intervals.single().end)
    }

    @Test fun pendingExitAtEditTimeLetsTheLaterRealDepartureCloseTheVisit() {
        // A low-accuracy EXIT was still being checked when the start-only edit was saved. A
        // later platform ENTER and the real departure follow. later days see no carried-over visit.
        val phantom = RawEvent("phantom", office.id, Transition.EXIT, at("2026-03-04T16:52:00Z"))
        val back = RawEvent("back", office.id, Transition.ENTER, at("2026-03-04T16:57:30Z"))
        val real = RawEvent("real", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val nextDay = RawEvent("nextDay", office.id, Transition.ENTER, at("2026-03-07T14:30:00Z"))
        val result = derive(listOf(enter, phantom, back, real, nextDay), at("2026-03-07T19:30:00Z"),
            unconfirmed = setOf("phantom"))
        val corrected = result.sessions.single { it.correctionId == "edit" }
        assertEquals(real.at, corrected.end)
        assertEquals(listOf("session:nextDay"), result.sessions.filter { it.isOpen }.map { it.id })
        val firstDay = result.intervals.filter { it.start < at("2026-03-05T05:00:00Z") }
        assertEquals(at("2026-03-04T14:05:00Z"), firstDay.minOf { it.start })
        assertEquals(real.at, firstDay.maxOf { it.end })
    }

    @Test fun exitBeforeTheEditIsOverriddenByTheUsersOpenEnd() {
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T16:00:00Z"))
        val now = at("2026-03-04T18:00:00Z")
        val session = derive(listOf(enter, exit), now).sessions.single()
        assertNull(session.end)
        assertEquals(setOf(ReviewReason.OPEN_SESSION), session.reviewReasons)
    }

    @Test fun rejectedPhantomExitDoesNotCloseAnOpenEdit() {
        val phantom = RawEvent("phantom", office.id, Transition.EXIT, at("2026-03-04T17:30:00Z"))
        val session = derive(listOf(enter, phantom), at("2026-03-04T18:00:00Z"), rejected = setOf("phantom"))
            .sessions.single()
        assertNull(session.end)
    }

    @Test fun firstEligibleLaterExitWinsAndOtherOfficeExitsAreIgnored() {
        val elsewhere = RawEvent("elsewhere", other.id, Transition.EXIT, at("2026-03-04T17:20:00Z"))
        val rejected = RawEvent("rejected", office.id, Transition.EXIT, at("2026-03-04T17:30:00Z"))
        val first = RawEvent("first", office.id, Transition.EXIT, at("2026-03-04T18:00:00Z"))
        val second = RawEvent("second", office.id, Transition.EXIT, at("2026-03-04T18:30:00Z"))
        val result = derive(listOf(enter, elsewhere, rejected, first, second), at("2026-03-04T19:00:00Z"),
            offices = listOf(office, other), rejected = setOf("rejected"))
        assertEquals(first.at, result.sessions.single { it.correctionId == "edit" }.end)
    }

    @Test fun unconfirmedOrPendingLaterExitClosesTheVisitButAsksForReview() {
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        listOf(setOf("out") to emptySet<String>(), emptySet<String>() to setOf("out")).forEach { (unconfirmed, candidate) ->
            val result = derive(listOf(enter, exit), at("2026-03-04T18:41:00Z"),
                unconfirmed = unconfirmed, candidate = candidate)
            val session = result.sessions.single()
            assertEquals(exit.at, session.end)
            assertEquals(setOf(ReviewReason.UNCONFIRMED_BOUNDARY), session.reviewReasons)
            assertTrue(result.reviews.any { it.sessionId == session.id && it.reason == ReviewReason.UNCONFIRMED_BOUNDARY })
        }
    }

    @Test fun exitBeyondTheOpenSessionLimitCannotCreditDaysOfAbsence() {
        // A departure reported days later must not credit the whole weekend.
        val later = RawEvent("later", office.id, Transition.EXIT, at("2026-03-06T19:00:00Z"))
        val result = derive(listOf(enter, later), at("2026-03-07T19:00:00Z"))
        val session = result.sessions.single()
        assertNull(session.end)
        assertEquals(setOf(ReviewReason.OPEN_SESSION, ReviewReason.STALE_OPEN_SESSION), session.reviewReasons)
        assertTrue(result.intervals.isEmpty())
    }

    @Test fun explicitEndAndRevertAreUnchanged() {
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val ended = edit.copy(end = at("2026-03-04T17:00:00Z"))
        assertEquals(ended.end, derive(listOf(enter, exit), at("2026-03-04T19:00:00Z"), listOf(ended)).sessions.single().end)
        val revert = edit.copy(id = "revert", createdAt = at("2026-03-04T16:58:00Z"), revertToOriginal = true, appendSequence = 1)
        val restored = derive(listOf(enter, exit), at("2026-03-04T19:00:00Z"), listOf(edit, revert)).sessions.single()
        assertTrue(restored.correctionReverted)
        assertEquals(exit.at, restored.end)
    }

    @Test fun openEditStillOpenWithoutLaterDeparture() {
        val session = derive(listOf(enter), at("2026-03-04T18:00:00Z")).sessions.single()
        assertNull(session.end)
        assertEquals(setOf(ReviewReason.OPEN_SESSION), session.reviewReasons)
    }
}
