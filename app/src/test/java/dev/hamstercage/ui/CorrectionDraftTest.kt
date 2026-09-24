package dev.hamstercage.ui

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CorrectionDraftTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    @Test fun explicitOffsetsDistinguishDstRepeatedHourAndMidnight() {
        val bounds = correctionBounds("2026-11-01T01:30-04:00", "2026-11-01T01:30-05:00", now)
        assertEquals(3600, bounds.end!!.epochSecond - bounds.start.epochSecond)
        val midnight = correctionBounds("2026-10-31T23:30-04:00", "2026-11-01T00:30-04:00", now)
        assertEquals(3600, midnight.end!!.epochSecond - midnight.start.epochSecond)
        assertNull(correctionBounds("2026-11-01T01:30-04:00", "", now).end)
    }
    @Test fun invalidReversedFutureAndAmbiguousLocalOnlyInputAreRejected() {
        val pairs = listOf("bad" to "", "2026-11-01T01:30" to "", "2026-11-01T01:30Z" to "2026-11-01T01:29Z",
            "2026-11-01T01:30Z" to "2026-11-01T01:30Z", "2026-11-03T01:00Z" to "",
            "2026-11-01T01:00Z" to "2026-11-03T01:00Z", "2026-11-01T01:00:00.000000001Z" to "")
        pairs.forEach { (start, end) ->
            var rejected = false
            try { correctionBounds(start, end, now) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Invalid input must not become a source fact", rejected)
        }
    }
}
