package dev.hamstercage.capture

import dev.hamstercage.data.FixMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class FixMetadataTest {
    private val receipt = 9_000_000_000_000L

    @Test fun freshAndStaleFixesReportAgeAtReceipt() {
        assertEquals(FixMetadata(1_250, "fused", false), fixMetadata(receipt, receipt - 1_250_000_000L, "fused", false))
        // A cached fix from twenty minutes before delivery is reported as old, not corrected.
        assertEquals(1_200_000L, fixMetadata(receipt, receipt - 1_200_000_000_000L, "gps", false).ageMs)
        assertEquals(0L, fixMetadata(receipt, receipt, "network", false).ageMs)
    }

    @Test fun missingOrInconsistentTimestampsLeaveAgeUnknownRatherThanZero() {
        assertEquals(null, fixMetadata(null, receipt - 1_000_000L, "fused", false).ageMs)
        assertEquals(null, fixMetadata(0L, receipt - 1_000_000L, "fused", false).ageMs)
        // Location.elapsedRealtimeNanos is 0 when the provider did not set it.
        assertEquals(null, fixMetadata(receipt, 0L, "fused", false).ageMs)
        assertEquals(null, fixMetadata(receipt, -5L, "fused", false).ageMs)
        // A fix stamped after receipt is invalid, not negative age.
        assertEquals(null, fixMetadata(receipt, receipt + 1L, "fused", false).ageMs)
    }

    @Test fun providerIsLimitedToStandardNamesAndMockFlagIsKept() {
        assertEquals("passive", fixMetadata(receipt, receipt, "passive", false).provider)
        assertEquals("other", fixMetadata(receipt, receipt, "com.example.spoofer", true).provider)
        assertEquals(null, fixMetadata(receipt, receipt, null, false).provider)
        assertEquals(null, fixMetadata(receipt, receipt, " ", false).provider)
        assertEquals(true, fixMetadata(receipt, receipt, "gps", true).mock)
    }
}
