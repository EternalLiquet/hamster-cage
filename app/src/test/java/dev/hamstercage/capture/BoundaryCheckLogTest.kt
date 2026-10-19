package dev.hamstercage.capture

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** #135: every started check attempt leaves an explainable, bounded diagnostic row. */
class BoundaryCheckLogTest {
    private val t0 = Instant.parse("2026-10-19T20:21:46Z")
    private fun entry(id: String = "exit-1", attempt: Int = 1, at: Instant = t0,
        outcome: BoundaryCheckOutcome = BoundaryCheckOutcome.FIX_STALE, accuracy: Float? = 16.5f, age: Long? = 41_000) =
        BoundaryCheckEntry(id, attempt, at, at.plusMillis(900), outcome, accuracy, age)

    @Test fun roundTripKeepsEveryFieldIncludingIdsWithSeparators() {
        val entries = listOf(entry(), entry("a;b\nc|d", 5, t0.plusSeconds(240), BoundaryCheckOutcome.RECORDED_INSIDE, null, null))
        assertEquals(entries, BoundaryCheckCodec.decode(BoundaryCheckCodec.encode(entries)))
    }

    @Test fun malformedOrUnknownLinesAreDroppedNotFatal() {
        val good = BoundaryCheckCodec.encode(listOf(entry()))
        val text = listOf("", "v0;x;1;0;0;FIX_STALE;-;-", "v1;not base64!;1;0;0;FIX_STALE;-;-",
            "v1;ZXhpdA;1;0;0;NOT_AN_OUTCOME;-;-", "v1;ZXhpdA;9;0;0;FIX_STALE;-;-",
            "v1;ZXhpdA;1;10;5;FIX_STALE;-;-", "v1;ZXhpdA;1;0;0;FIX_STALE;NaN;-",
            "v1;ZXhpdA;1;0;0;FIX_STALE;-;-1", "v1;ZXhpdA;1;0;0", good).joinToString("\n")
        assertEquals(listOf(entry()), BoundaryCheckCodec.decode(text))
        assertEquals(emptyList<BoundaryCheckEntry>(), BoundaryCheckCodec.decode(null))
    }

    @Test fun pruneKeepsRetentionWindowAndNewestRows() {
        val old = entry("old", at = t0.minus(BoundaryCheckCodec.RETENTION).minusMillis(1))
        val edge = entry("edge", at = t0.minus(BoundaryCheckCodec.RETENTION))
        assertEquals(listOf(edge), BoundaryCheckCodec.prune(listOf(old, edge), t0))
        val many = (0 until BoundaryCheckCodec.MAX_ENTRIES + 5).map { entry("e$it", at = t0.minusSeconds(it.toLong())) }
        val kept = BoundaryCheckCodec.prune(many, t0)
        assertEquals(BoundaryCheckCodec.MAX_ENTRIES, kept.size)
        assertEquals(t0, kept.last().startedAt)
        assertFalse(kept.any { it.candidateEventId in setOf("e${BoundaryCheckCodec.MAX_ENTRIES}", "e${BoundaryCheckCodec.MAX_ENTRIES + 4}") })
    }

    @Test fun invalidEntriesAreRejectedBeforeWriting() {
        assertTrue(BoundaryCheckCodec.valid(entry()))
        assertFalse(BoundaryCheckCodec.valid(entry(id = " ")))
        assertFalse(BoundaryCheckCodec.valid(entry(id = "x".repeat(201))))
        assertFalse(BoundaryCheckCodec.valid(entry(attempt = 0)))
        assertFalse(BoundaryCheckCodec.valid(entry(accuracy = Float.POSITIVE_INFINITY)))
        assertFalse(BoundaryCheckCodec.valid(entry(age = -1)))
        assertFalse(BoundaryCheckCodec.valid(entry().copy(finishedAt = t0.minus(Duration.ofMillis(1)))))
    }

    @Test fun freshnessGateNamesWhichRuleFailed() {
        val requested = t0.toEpochMilli()
        assertEquals(BoundaryCheckOutcome.FIX_CACHED, fixFreshnessFailure(requested - 1, requested, 5_000, 5_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested, requested, 30_001, 5_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested, requested, -1, 5_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested, requested, 5_000, 30_000_000_001, true))
        assertEquals(BoundaryCheckOutcome.FIX_NO_ACCURACY, fixFreshnessFailure(requested, requested, 5_000, 5_000_000_000, false))
        assertNull(fixFreshnessFailure(requested, requested, 30_000, 30_000_000_000, true))
    }
}
