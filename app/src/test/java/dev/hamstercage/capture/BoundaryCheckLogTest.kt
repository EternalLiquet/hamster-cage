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
        assertNull(fixFreshnessFailure(requested - 5_000, requested - 10_000, 5_000, 5_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_CACHED, fixFreshnessFailure(requested - 1, requested, 5_000, 5_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested + 1, requested, 30_001, 30_001_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested + 1, requested, -1, 5_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested + 1, requested, 5_000, 30_000_000_001, true))
        assertEquals(BoundaryCheckOutcome.FIX_STALE, fixFreshnessFailure(requested, requested - 1,
            5_000, 7_000_000_000, true))
        assertEquals(BoundaryCheckOutcome.FIX_NO_ACCURACY, fixFreshnessFailure(requested + 1, requested, 5_000, 5_000_000_000, false))
        assertNull(fixFreshnessFailure(requested + 1, requested, 30_000, 30_000_000_000, true))
    }

    // Review finding 2: the deadline label must reflect when the worker began, while the stop
    // itself still uses the clock after preparation exactly as before.
    private val received = Instant.parse("2026-10-19T20:22:19.227Z")
    private val deadline = received.plusSeconds(AdaptiveConfirmation.EXIT_WINDOW_SECONDS)

    @Test fun workerThatBeganAfterTheDeadlineIsALateStart() {
        val began = deadline.plusSeconds(40)
        assertEquals(BoundaryCheckOutcome.STARTED_PAST_DEADLINE, deadlineStop(began, began.plusSeconds(2), deadline))
        assertEquals(BoundaryCheckOutcome.STARTED_PAST_DEADLINE, deadlineStop(deadline, deadline, deadline))
    }

    @Test fun workerThatBeganInTimeButCrossedTheDeadlineWhilePreparingIsNotALateStart() {
        val began = received.plusSeconds(295)
        val afterPreparation = began.plusSeconds(6)
        assertEquals(BoundaryCheckOutcome.DEADLINE_PASSED_DURING_PREPARATION, deadlineStop(began, afterPreparation, deadline))
        // Exactly reaching the deadline still stops, as the original `now >= deadline` check did.
        assertEquals(BoundaryCheckOutcome.DEADLINE_PASSED_DURING_PREPARATION,
            deadlineStop(deadline.minusMillis(1), deadline, deadline))
    }

    @Test fun workerStillWithinTheDeadlineAfterPreparationContinues() {
        val began = received.plusSeconds(290)
        assertNull(deadlineStop(began, began.plusSeconds(6), deadline))
        assertNull(deadlineStop(began, deadline.minusMillis(1), deadline))
        // An ENTER check has no verification deadline.
        assertNull(deadlineStop(began, began.plusSeconds(600), null))
    }

    @Test fun legacyDeadlineRowsAreReadAsAmbiguousAndOtherOutcomesKeepTheirMeaning() {
        val v1 = listOf("v1;ZXhpdA;2;1000;1500;STARTED_PAST_DEADLINE;-;-",
            "v1;ZXhpdA;3;2000;2500;FIX_STALE;16.5;41000",
            // Outcomes that did not exist in v1 cannot be trusted on a v1 line.
            "v1;ZXhpdA;4;3000;3500;DEADLINE_PASSED_DURING_PREPARATION;-;-",
            "v1;ZXhpdA;5;4000;4500;PAST_DEADLINE_BEFORE_REQUEST;-;-").joinToString("\n")
        val decoded = BoundaryCheckCodec.decode(v1)
        assertEquals(listOf(BoundaryCheckOutcome.PAST_DEADLINE_BEFORE_REQUEST, BoundaryCheckOutcome.FIX_STALE),
            decoded.map { it.outcome })
        // Rewriting the log after a new attempt keeps the legacy meaning instead of upgrading it.
        assertEquals(decoded, BoundaryCheckCodec.decode(BoundaryCheckCodec.encode(decoded)))
        assertTrue(BoundaryCheckCodec.encode(decoded).lines().all { it.startsWith("v2;") })
        val current = listOf(entry(outcome = BoundaryCheckOutcome.STARTED_PAST_DEADLINE, accuracy = null, age = null),
            entry(attempt = 2, outcome = BoundaryCheckOutcome.DEADLINE_PASSED_DURING_PREPARATION, accuracy = null, age = null))
        assertEquals(current, BoundaryCheckCodec.decode(BoundaryCheckCodec.encode(current)))
    }
}
