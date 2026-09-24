package dev.hamstercage.offices

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfficeFixValidationTest {
    private val now = 100_000_000_000L

    private fun fix(ageNanos: Long, accuracy: Float?): Location = Location("synthetic").apply {
        latitude = 0.0
        longitude = 0.0
        elapsedRealtimeNanos = now - ageNanos
        if (accuracy != null) this.accuracy = accuracy
    }

    private fun rejected(fix: Location?, expected: String) {
        try {
            validatedOfficeFix(fix, now)
            fail("Expected invalid one-shot location to fail closed")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(expected))
        }
    }

    @Test fun staleFutureAndMissingAgeCannotBecomeAnOffice() {
        rejected(fix(31_000_000_000L, 12f), "stale")
        rejected(fix(-1_000_000_000L, 12f), "stale")
        rejected(fix(now, 12f), "stale")
    }

    @Test fun inaccurateAndMissingAccuracyCannotBecomeAnOffice() {
        rejected(fix(1_000_000_000L, 150f), "too approximate")
        rejected(fix(1_000_000_000L, null), "too approximate")
        rejected(fix(1_000_000_000L, -1f), "too approximate")
    }

    @Test fun freshPreciseFixRemainsUsable() {
        val accepted = validatedOfficeFix(fix(1_000_000_000L, 12f), now)
        assertEquals(12f, accepted.accuracyMeters, 0f)
        assertEquals(0.0, accepted.place.latitude, 0.0)
    }
}
