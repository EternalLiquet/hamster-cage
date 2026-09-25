package dev.hamstercage.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CorrectionDraftTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val eastern = ZoneId.of("America/New_York")
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

    @Test fun ordinaryLocalSelectionConvertsToExactStoredInstantAndOpenEnd() {
        val start = LocalBoundary(LocalDateTime.parse("2026-09-23T09:30"))
        val end = LocalBoundary(LocalDateTime.parse("2026-09-23T10:15"))
        val bounds = localCorrectionBounds(start, end, eastern, now)
        assertEquals(Instant.parse("2026-09-23T13:30:00Z"), bounds.start)
        assertEquals(Instant.parse("2026-09-23T14:15:00Z"), bounds.end)
        assertNull(localCorrectionBounds(start, null, eastern, now).end)
    }

    @Test fun repeatedFallBackHourRequiresFirstOrSecondChoiceAndKeepsOffset() {
        val local = LocalDateTime.parse("2026-11-01T01:30")
        assertEquals(2, overlapOffsets(local, eastern).size)
        assertThrows(IllegalArgumentException::class.java) { resolveLocalBoundary(LocalBoundary(local), eastern) }
        val first = resolveLocalBoundary(LocalBoundary(local, 0), eastern)
        val second = resolveLocalBoundary(LocalBoundary(local, 1), eastern)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first)
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), second)
        assertEquals(0, overlapChoiceFor(first, eastern))
        assertEquals(1, overlapChoiceFor(second, eastern))
        assertEquals(3600, second.epochSecond - first.epochSecond)
    }

    @Test fun springForwardGapAndOrderingFutureAreRejectedBeforePreview() {
        val missing = LocalBoundary(LocalDateTime.parse("2026-03-08T02:30"))
        val gap = assertThrows(IllegalArgumentException::class.java) { resolveLocalBoundary(missing, eastern) }
        assertTrue(gap.message!!.contains("does not exist"))
        assertTrue(gap.message!!.contains("3:00 AM"))
        val valid = LocalBoundary(LocalDateTime.parse("2026-03-08T03:00"))
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), resolveLocalBoundary(valid, eastern))
        val early = LocalBoundary(LocalDateTime.parse("2026-09-23T09:00"))
        val late = LocalBoundary(LocalDateTime.parse("2026-09-23T10:00"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            localCorrectionBounds(late, early, eastern, now)
        }.message!!.contains("End must follow"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            localCorrectionBounds(late, null, eastern, Instant.parse("2026-09-23T13:00:00Z"))
        }.message!!.contains("future"))
    }

    @Test fun timeLabelsFollowTwelveAndTwentyFourHourPreferences() {
        val local = LocalDateTime.parse("2026-09-23T17:05")
        assertEquals("5:05 PM", correctionTimeText(local, false))
        assertEquals("17:05", correctionTimeText(local, true))
        val instant = Instant.parse("2026-09-23T21:05:00Z")
        assertTrue(correctionPreviewText(instant, eastern, false).contains("5:05 PM EDT"))
        assertTrue(correctionPreviewText(instant, eastern, true).contains("17:05 EDT"))
        assertFalse(correctionPreviewText(instant, eastern, true).contains("PM"))
    }

    @Test fun prefilledSecondsAndMillisAreRemovedBeforeAnyPickerOrDateOnlyChange() {
        val observed = Instant.parse("2026-09-23T13:49:00.002Z")
        val local = correctionPickerMinute(observed, eastern)
        assertEquals(LocalDateTime.parse("2026-09-23T09:49"), local)
        val unchangedDate = local.toLocalDate().atTime(local.toLocalTime())
        val bounds = localCorrectionBounds(LocalBoundary(unchangedDate), null, eastern, now)
        assertEquals(Instant.parse("2026-09-23T13:49:00Z"), bounds.start)
        assertEquals(0, bounds.start.nano)
    }
}
