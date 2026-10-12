package dev.hamstercage.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** A blank correction end changes only the start: the visit keeps the end its own evidence shows. */
class OpenCorrectionTest {
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    private val other = Office("other", "Other office", 1.0, 1.0)
    private fun at(value: String) = Instant.parse(value)
    private val enter = RawEvent("in", office.id, Transition.ENTER, at("2026-03-04T14:00:00Z"))
    private val edit = Correction("edit", "session:in", at("2026-03-04T13:50:00Z"), null, at("2026-03-04T16:57:00Z"))

    private fun derive(events: List<RawEvent>, now: Instant, corrections: List<Correction> = listOf(edit),
        offices: List<Office> = listOf(office), rejected: Set<String> = emptySet(),
        unconfirmed: Set<String> = emptySet(), candidate: Set<String> = emptySet()) =
        AttendanceEngine.derive(AttendanceInput(offices, events, corrections, now = now,
            rejectedExitIds = rejected, unconfirmedExitIds = unconfirmed, candidateExitIds = candidate))
    private fun corrected(result: AttendanceResult) = result.sessions.single { it.correctionId == "edit" }

    @Test fun exitObservedAfterAStartOnlyEditClosesTheCorrectedVisit() {
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val result = derive(listOf(enter, exit), at("2026-03-07T19:00:00Z"))
        val session = corrected(result)
        assertEquals(edit.start, session.start)
        assertEquals(exit.at, session.end)
        assertEquals(Confidence.MANUAL, session.confidence)
        assertTrue(session.reviewReasons.isEmpty())
        assertTrue(result.reviews.none { it.sessionId == session.id })
        // Credit starts after the 5-minute arrival grace from the corrected start and ends at the EXIT.
        assertEquals(at("2026-03-04T13:55:00Z"), result.intervals.single().start)
        assertEquals(exit.at, result.intervals.single().end)
    }

    @Test fun exitPendingAtEditTimeThatIsLaterConfirmedStillClosesTheVisit() {
        // The EXIT was still being checked (visit open) when the start-only edit was saved;
        // confirmation adds no new raw event, so it must not leave the visit frozen open.
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T16:52:00Z"))
        val pending = derive(listOf(enter, exit), at("2026-03-04T16:57:00Z"), candidate = setOf("out"))
        assertNull(corrected(pending).end)
        val settled = derive(listOf(enter, exit), at("2026-03-07T19:00:00Z"))
        assertEquals(exit.at, corrected(settled).end)
        assertTrue(settled.sessions.none { it.isOpen })
        assertTrue(settled.reviews.isEmpty())
        assertEquals(exit.at, settled.intervals.single().end)
    }

    @Test fun phantomExitThenPlatformReturnKeepsBothObservedVisitsWithoutCarryOver() {
        val phantom = RawEvent("phantom", office.id, Transition.EXIT, at("2026-03-04T16:52:00Z"))
        val back = RawEvent("back", office.id, Transition.ENTER, at("2026-03-04T16:57:30Z"))
        val real = RawEvent("real", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val nextDay = RawEvent("nextDay", office.id, Transition.ENTER, at("2026-03-07T14:30:00Z"))
        val result = derive(listOf(enter, phantom, back, real, nextDay), at("2026-03-07T19:30:00Z"),
            unconfirmed = setOf("phantom"))
        val session = corrected(result)
        assertEquals(phantom.at, session.end)
        assertEquals(setOf(ReviewReason.UNCONFIRMED_BOUNDARY), session.reviewReasons)
        assertEquals(listOf("session:nextDay"), result.sessions.filter { it.isOpen }.map { it.id })
        // The 5.5-minute gap is within the policy's short-gap bridge; the return's arrival grace is not.
        val firstDay = result.intervals.filter { it.start < at("2026-03-05T05:00:00Z") }
        assertEquals(listOf(at("2026-03-04T13:55:00Z") to back.at, at("2026-03-04T17:02:30Z") to real.at),
            firstDay.map { it.start to it.end })
    }

    @Test fun bounceIsMergedSoTheRealDepartureClosesTheVisit() {
        val bounceOut = RawEvent("b1", office.id, Transition.EXIT, at("2026-03-04T17:30:00Z"))
        val bounceIn = RawEvent("b2", office.id, Transition.ENTER, at("2026-03-04T17:30:30Z"))
        val real = RawEvent("real", office.id, Transition.EXIT, at("2026-03-04T21:00:00Z"))
        val result = derive(listOf(enter, bounceOut, bounceIn, real), at("2026-03-04T22:00:00Z"))
        assertEquals(real.at, corrected(result).end)
        assertEquals(at("2026-03-04T13:55:00Z") to real.at, result.intervals.single().let { it.start to it.end })
    }

    @Test fun laterVisitsElsewhereOrAfterAGapNeverExtendTheCorrectedVisit() {
        val out = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T16:00:00Z"))
        val otherIn = RawEvent("oe", other.id, Transition.ENTER, at("2026-03-04T18:00:00Z"))
        val otherOut = RawEvent("ox", other.id, Transition.EXIT, at("2026-03-04T20:00:00Z"))
        val againIn = RawEvent("a2", office.id, Transition.ENTER, at("2026-03-04T23:00:00Z"))
        val againOut = RawEvent("a2x", office.id, Transition.EXIT, at("2026-03-05T02:00:00Z"))
        val result = derive(listOf(enter, out, otherIn, otherOut, againIn, againOut), at("2026-03-05T03:00:00Z"),
            offices = listOf(office, other))
        assertEquals(out.at, corrected(result).end)
        assertTrue(result.intervals.none { it.start < at("2026-03-04T23:00:00Z") && it.end > at("2026-03-04T20:00:00Z") })
        assertTrue(result.intervals.none { "session:in" in it.sessionIds && it.end > out.at })
    }

    @Test fun rejectedPhantomExitOrStillPendingExitKeepsTheVisitOpen() {
        val phantom = RawEvent("phantom", office.id, Transition.EXIT, at("2026-03-04T17:30:00Z"))
        assertNull(corrected(derive(listOf(enter, phantom), at("2026-03-04T18:00:00Z"), rejected = setOf("phantom"))).end)
        val open = corrected(derive(listOf(enter, phantom), at("2026-03-04T17:31:00Z"), candidate = setOf("phantom")))
        assertNull(open.end)
        assertEquals(setOf(ReviewReason.OPEN_SESSION), open.reviewReasons)
    }

    @Test fun observedEndBeyondTheOpenSessionLimitCannotCreditDaysOfAbsence() {
        // A departure reported days later must not credit the whole gap.
        val later = RawEvent("later", office.id, Transition.EXIT, at("2026-03-06T19:00:00Z"))
        val result = derive(listOf(enter, later), at("2026-03-07T19:00:00Z"))
        val session = corrected(result)
        assertNull(session.end)
        assertEquals(setOf(ReviewReason.OPEN_SESSION, ReviewReason.STALE_OPEN_SESSION), session.reviewReasons)
        assertTrue(result.intervals.isEmpty())
    }

    @Test fun explicitEndAndRevertAreUnchanged() {
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val ended = edit.copy(end = at("2026-03-04T17:00:00Z"))
        assertEquals(ended.end, corrected(derive(listOf(enter, exit), at("2026-03-04T19:00:00Z"), listOf(ended))).end)
        val revert = edit.copy(id = "revert", createdAt = at("2026-03-04T16:58:00Z"), revertToOriginal = true, appendSequence = 1)
        val restored = derive(listOf(enter, exit), at("2026-03-04T19:00:00Z"), listOf(edit, revert)).sessions.single()
        assertTrue(restored.correctionReverted)
        assertEquals(exit.at, restored.end)
        assertEquals(enter.at, restored.start)
    }

    @Test fun manualIntervalWithBlankEndStaysExplicitlyOpen() {
        val manual = ManualSession("m", office.id, at("2026-03-04T14:00:00Z"), null, at("2026-03-04T14:10:00Z"))
        val exit = RawEvent("out", office.id, Transition.EXIT, at("2026-03-04T18:40:00Z"))
        val result = AttendanceEngine.derive(AttendanceInput(listOf(office), listOf(exit), now = at("2026-03-04T19:00:00Z"),
            manualSessions = listOf(manual)))
        assertNull(result.sessions.single { it.manualSessionId == "m" }.end)
    }
}
