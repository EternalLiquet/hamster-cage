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
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveSchedulingIntegrationTest {
    @Test fun onePendingCheckIsReplacedInCommitOrderAndCancelledByTag() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        fun active(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(AdaptiveConfirmation.TAG)
            .get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }
        fun diagnostics(): String = manager.getWorkInfosByTag(AdaptiveConfirmation.TAG)
            .get(5, TimeUnit.SECONDS).joinToString { "${it.id}:${it.state}:${it.tags.sorted()}" }
        suspend fun awaitActive(candidateId: String): List<WorkInfo> {
            repeat(50) {
                val pending = active()
                if (pending.size == 1 && "candidate:$candidateId" in pending.single().tags)
                    return pending
                delay(100)
            }
            return active()
        }
        fun event(id: String, second: Long) = RecordedEvent(RawEvent(id, "synthetic-office",
            Transition.EXIT, Instant.parse("2026-09-25T15:00:00Z").plusSeconds(second)),
            Instant.parse("2026-09-25T15:00:00Z").plusSeconds(second))
        try {
            val version = AdaptiveConfirmation.fingerprint(mapOf("synthetic-office" to 1L))
            AdaptiveConfirmation.schedule(context, event("old", 0), 0, version, listOf("synthetic-office"))
            val firstPending = awaitActive("old")
            assertEquals("First enqueue by tag: ${diagnostics()}", 1, firstPending.size)
            val first = firstPending.single()
            assertEquals(true, "candidate:old" in first.tags)
            AdaptiveConfirmation.schedule(context, event("new", 10), 0, version, listOf("synthetic-office"))
            val latestPending = awaitActive("new")
            assertEquals("Replacement enqueue by tag: ${diagnostics()}", 1, latestPending.size)
            val latest = latestPending.single()
            assertNotEquals(first.id, latest.id)
            assertEquals(true, "candidate:new" in latest.tags)
        } finally {
            manager.cancelAllWorkByTag(AdaptiveConfirmation.TAG).result.get(5, TimeUnit.SECONDS)
        }
        assertEquals(0, active().size)
    }
}
