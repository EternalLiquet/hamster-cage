package dev.hamstercage.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeekdaySchedulingIntegrationTest {
    @Test fun backgroundObservationsChangeOnePersistedPeriodicInterval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val office = Office("synthetic-office", "Synthetic", 39.0, -86.0, 150f)
        val now = Instant.parse("2026-09-28T14:30:00Z")
        val outside = AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy())
        val enter = RawEvent("enter", office.id, Transition.ENTER, now.minusSeconds(3600))
        val exit = RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(300))
        val absence = RawEvent("absence", office.id, Transition.ABSENCE, now.minusSeconds(240))
        fun withFacts(vararg facts: RawEvent) = outside.copy(eventEvidence = facts.map {
            RecordedEvent(it, it.at, if (it.transition == Transition.ABSENCE) it.at else null,
                if (it.transition == Transition.ABSENCE) "BACKGROUND_LOCATION_RECONCILIATION"
                else "PLAY_SERVICES_GEOFENCE")
        })
        fun activeWork(): Pair<UUID, Long> {
            val work = manager.getWorkInfosForUniqueWork(WeekdayReconciliation.NAME)
                .get(5, TimeUnit.SECONDS).filter { it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING) }
                .single()
            return work.id to (requireNotNull(work.periodicityInfo).repeatIntervalMillis / 60_000)
        }
        CaptureWriteGate.mutex.withLock {
            manager.cancelUniqueWork(WeekdayReconciliation.NAME).result.get(5, TimeUnit.SECONDS)
            try {
                // Exercise the background write path directly, without launching an activity here.
                // Keep the real worker from starting while its persisted schedule is inspected.
                updateReconciliationAfterObservation(context, outside, now, initialDelayMinutes = 60)
                val (id, firstInterval) = activeWork()
                assertEquals(30L, firstInterval)
                updateReconciliationAfterObservation(context, withFacts(enter), now, initialDelayMinutes = 60)
                assertEquals(id to 15L, activeWork())
                updateReconciliationAfterObservation(context, withFacts(enter, exit), now, initialDelayMinutes = 60)
                assertEquals(id to 15L, activeWork())
                updateReconciliationAfterObservation(context, withFacts(enter, exit, absence), now,
                    initialDelayMinutes = 60)
                assertEquals(id to 30L, activeWork())
            } finally {
                manager.cancelUniqueWork(WeekdayReconciliation.NAME).result.get(5, TimeUnit.SECONDS)
            }
        }
    }
}
