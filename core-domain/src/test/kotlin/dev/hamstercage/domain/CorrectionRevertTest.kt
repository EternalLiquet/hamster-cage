package dev.hamstercage.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CorrectionRevertTest {
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    private fun input(events: List<RawEvent>) = AttendanceInput(listOf(office), events, now = now)
    @Test fun supersedingAndRevertingEditsRestorePriorDerivationAndKeepTheAudit() {
        val data = input(listOf(RawEvent("in", office.id, Transition.ENTER, now.minusSeconds(7200)),
            RawEvent("out", office.id, Transition.EXIT, now.minusSeconds(3600))))
        val original = AttendanceEngine.derive(data)
        val first = Correction("a", "session:in", now.minusSeconds(6000), now.minusSeconds(3600), now.minusSeconds(30))
        val second = first.copy(id = "b", start = now.minusSeconds(5400), createdAt = now.minusSeconds(20))
        val reverted = second.copy(id = "c", createdAt = now.minusSeconds(10), revertToOriginal = true)
        val edited = data.copy(corrections = listOf(first, second))
        assertEquals("b", AttendanceEngine.derive(edited).sessions.single().correctionId)
        assertEquals(Confidence.MANUAL, AttendanceEngine.derive(edited).sessions.single().confidence)
        val restored = AttendanceEngine.derive(edited.copy(corrections = edited.corrections + reverted))
        assertTrue(restored.sessions.single().correctionReverted)
        assertEquals("c", restored.sessions.single().correctionId)
        assertEquals(original, restored.copy(sessions = restored.sessions.map { it.copy(correctionId = null, correctionReverted = false) }))
        assertEquals(data.events, edited.events)
        val later = first.copy(id = "d", createdAt = now)
        assertEquals("d", AttendanceEngine.derive(data.copy(corrections = listOf(first, second, reverted, later))).sessions.single().correctionId)
    }
    @Test fun revertCanRestoreMissingStartAndReviewWithoutFabricatedEnter() {
        val data = input(listOf(RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))))
        val original = AttendanceEngine.derive(data)
        val edit = Correction("a", "session:exit", now.minusSeconds(7200), now.minusSeconds(3600), now.minusSeconds(10))
        val revert = edit.copy(id = "b", start = edit.end!!, createdAt = now, revertToOriginal = true)
        val restored = AttendanceEngine.derive(data.copy(corrections = listOf(edit, revert)))
        assertEquals(original, restored.copy(sessions = restored.sessions.map { it.copy(correctionId = null, correctionReverted = false) }))
        assertNull(original.sessions.single().start)
        assertTrue(original.reviews.any { it.reason == ReviewReason.MISSING_ENTER })
        assertTrue(original.intervals.isEmpty())
    }
    @Test fun futureOrConflictingRevertCannotHideALatestValidEdit() {
        val data = input(listOf(RawEvent("in", office.id, Transition.ENTER, now.minusSeconds(7200))))
        val edit = Correction("a", "session:in", now.minusSeconds(6000), now.minusSeconds(3600), now.minusSeconds(10))
        val future = edit.copy(id = "b", createdAt = now.plusSeconds(1), revertToOriginal = true)
        val result = AttendanceEngine.derive(data.copy(corrections = listOf(edit, future)))
        assertEquals("a", result.sessions.single().correctionId)
        assertTrue(result.reviews.any { it.reason == ReviewReason.INVALID_CORRECTION })
        val conflicting = edit.copy(id = "b", createdAt = now, revertToOriginal = true)
        val conflictResult = AttendanceEngine.derive(data.copy(corrections = listOf(edit, conflicting, conflicting.copy(revertToOriginal = false))))
        assertEquals("a", conflictResult.sessions.single().correctionId)
    }
}
