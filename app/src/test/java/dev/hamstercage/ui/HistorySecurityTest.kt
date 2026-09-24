package dev.hamstercage.ui

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HistorySecurityTest {
    @Test fun identifiersAndNotesAreBoundedLiteralDisplayValues() {
        assertEquals("\\u{202E}raw\\u{A}-id\\u{0}\\u{2066}", evidenceId("\u202eraw\n-id\u0000\u2066"))
        assertEquals("\\u{0}\\u{202E}", evidenceId("\u0000\u202e"))
        assertTrue(evidenceId("x".repeat(100_000)).length <= 200)
        val ids = listOf("raw-id", "raw\n-id", "raw\\u{A}-id", "", "\\u{}", "x".repeat(300) + "a", "x".repeat(300) + "b")
        assertEquals(ids.size, ids.map(::evidenceId).distinct().size)
        val longLabel = evidenceId("x".repeat(300))
        assertTrue(longLabel.contains("sha256:"))
        assertNotEquals(longLabel, evidenceId(longLabel))
        val note = "<script>synthetic</script>\n' OR 1=1;\u202e\u0000"
        assertEquals("<script>synthetic</script>\n' OR 1=1;", evidenceText(note))
        assertEquals(2000, evidenceText("x".repeat(100_000)).length)
        assertTrue(note.endsWith("\u202e\u0000")) // Source content is untouched.
    }
    @Test fun exhaustedCorrectionOrderAndMaliciousDatesFailWithStaticMessages() {
        assertEquals(1L, nextCorrectionSequence(0))
        for (value in listOf(-1L, Long.MAX_VALUE)) {
            try { nextCorrectionSequence(value); fail("Invalid order must fail") }
            catch (failure: IllegalArgumentException) { assertEquals("Correction order is unavailable. Your existing facts were kept.", failure.message) }
        }
        for (value in listOf("PRIVATE_SENTINEL_28", "+999999999-01-01T00:00Z", "2026-09-23T10:00\u202eZ")) {
            try { correctionBounds(value, "", Instant.parse("2026-09-23T16:00:00Z")); fail("Malformed bounds must fail") }
            catch (failure: IllegalArgumentException) { assertFalse(failure.message.orEmpty().contains(value)) }
        }
    }
}
