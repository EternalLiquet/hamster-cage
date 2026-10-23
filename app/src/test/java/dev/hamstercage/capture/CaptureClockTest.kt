package dev.hamstercage.capture

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureClockTest {
    @Test fun captureTimeIsWholeMilliseconds() {
        val now = captureNow { 1_761_239_040_001L }
        assertEquals(Instant.ofEpochMilli(1_761_239_040_001L), now)
        assertEquals(0, now.nano % 1_000_000)
        assertEquals(0, captureNow().nano % 1_000_000)
    }

    @Test fun checkFactsBuiltFromCaptureTimeKeepMillisecondReceipt() {
        val received = captureNow { 1_761_239_040_001L }
        val observed = Instant.ofEpochMilli(1_761_239_039_500L)
        val facts = exitVerificationFacts(listOf("a"), null, observed, received, 10f, newId = { "f" }) +
            adaptiveFacts(listOf("a"), "b", observed, received, 10f, newId = { "g" })
        assertEquals(setOf(received), facts.map { it.receivedAt }.toSet())
    }
}
