package dev.hamstercage.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveSchedulingIntegrationTest {
    @Test fun boundedPendingChecksAreReplacedInCommitOrderAndCancelledByTag() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        fun active(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(AdaptiveConfirmation.TAG)
            .get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
        fun diagnostics(): String = manager.getWorkInfosByTag(AdaptiveConfirmation.TAG)
            .get(5, TimeUnit.SECONDS).joinToString { "${it.id}:${it.state}:${it.tags.sorted()}" }
        suspend fun awaitActive(candidateId: String, count: Int): List<WorkInfo> {
            repeat(50) {
                val matching = active().filter { "candidate:$candidateId" in it.tags }
                if (matching.size == count) return matching
                delay(100)
            }
            return active().filter { "candidate:$candidateId" in it.tags }
        }
        fun event(id: String, second: Long) = RecordedEvent(RawEvent(id, "synthetic-office",
            Transition.EXIT, Instant.parse("2026-09-25T15:00:00Z").plusSeconds(second)),
            Instant.parse("2026-09-25T15:00:00Z").plusSeconds(second))
        manager.cancelAllWorkByTag(AdaptiveConfirmation.TAG).result.get(5, TimeUnit.SECONDS)
        try {
            val version = AdaptiveConfirmation.fingerprint(mapOf("synthetic-office" to 1L))
            AdaptiveConfirmation.schedule(context, event("old", 0), 0, version, listOf("synthetic-office"))
            val first = awaitActive("old", AdaptiveConfirmation.MAX_EXIT_ATTEMPTS)
            assertEquals("First enqueue by tag: ${diagnostics()}",
                AdaptiveConfirmation.MAX_EXIT_ATTEMPTS, first.size)
            AdaptiveConfirmation.schedule(context, event("new", 10), 0, version, listOf("synthetic-office"))
            val latest = awaitActive("new", AdaptiveConfirmation.MAX_EXIT_ATTEMPTS)
            assertEquals("Replacement enqueue by tag: ${diagnostics()}",
                AdaptiveConfirmation.MAX_EXIT_ATTEMPTS, latest.size)
            assertEquals(0, active().count { "candidate:old" in it.tags })
            assertEquals(0, first.map { it.id }.toSet().intersect(latest.map { it.id }.toSet()).size)
        } finally {
            manager.cancelAllWorkByTag(AdaptiveConfirmation.TAG).result.get(5, TimeUnit.SECONDS)
        }
        assertEquals(0, active().size)
    }
}
