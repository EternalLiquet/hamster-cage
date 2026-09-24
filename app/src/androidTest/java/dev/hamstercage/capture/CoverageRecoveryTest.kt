package dev.hamstercage.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverageRecoveryTest {
    @Test fun persistedGapSurvivesFreshReadAndRequiresPostRecoveryObservation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zone = ZoneId.of("America/Indianapolis")
        val previous = CoverageStore.state(context).first()
        try {
            val start = Instant.parse("2025-03-10T15:00:00Z")
            val returnAt = Instant.parse("2025-03-11T15:00:00Z")
            CoverageStore.change(context) {
                CoverageLedger().registrationSucceeded(start, zone, true).observed(start.plusSeconds(1))
            }
            CoverageStore.change(context) { it.processStarted(returnAt, zone) }
            val reopened = CoverageStore.state(context).first()
            assertEquals(setOf(LocalDate.parse("2025-03-10"), LocalDate.parse("2025-03-11")),
                reopened.unreviewedUnknownDates)
            assertFalse(reopened.presenceConfirmed(returnAt, zone))
            CoverageStore.change(context) { it.registrationSucceeded(returnAt.plusSeconds(1), zone, true) }
            assertFalse(CoverageStore.state(context).first().presenceConfirmed(returnAt.plusSeconds(2), zone))
            CoverageStore.change(context) { it.observed(returnAt.plusSeconds(3)) }
            assertTrue(CoverageStore.state(context).first().presenceConfirmed(returnAt.plusSeconds(4), zone))
        } finally {
            CoverageStore.change(context) { previous }
        }
    }
}
