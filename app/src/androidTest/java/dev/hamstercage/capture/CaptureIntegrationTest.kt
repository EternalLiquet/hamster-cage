package dev.hamstercage.capture

import android.location.Location
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val receipt = Instant.parse("2025-03-10T10:00:02Z")

    @Test fun delayedBounceFactsRemainImmutableAndDeriveTheSameAfterRoomReopen() = runBlocking {
        val name = "bounce-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val now = Instant.parse("2026-09-25T17:00:00Z")
        val entered = Instant.parse("2026-09-25T13:00:00Z")
        val bouncedOut = Instant.parse("2026-09-25T15:01:29Z")
        val bouncedIn = Instant.parse("2026-09-25T15:01:41Z")
        val left = Instant.parse("2026-09-25T16:00:00Z")
        val facts = listOf(
            RecordedEvent(RawEvent("first", "a", Transition.ENTER, entered), entered),
            RecordedEvent(RawEvent("bounce-out", "a", Transition.EXIT, bouncedOut), now.minusSeconds(90), bouncedOut),
            RecordedEvent(RawEvent("bounce-in", "a", Transition.ENTER, bouncedIn), now.minusSeconds(120), bouncedIn),
            RecordedEvent(RawEvent("last", "a", Transition.EXIT, left), left),
        )
        val dbName = "$name.db"
        try {
            val firstDb = HamsterDatabase.open(context, dbName)
            try {
                val repository = HamsterRepository(firstDb, prefs, TimeSource { now })
                repository.saveOffice(Office("a", "Synthetic A", 0.0, 0.0))
                repository.appendRawEvents(listOf(facts[2], facts[0], facts[3], facts[1]))
                repository.appendRawEvents(listOf(facts[1]))
                assertEquals(4, firstDb.dao().events().size)
                val before = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
                assertEquals(1, before.derive(now).sessions.size)
                assertEquals(facts.map { it.event.id }.toSet(), before.derive(now).sessions.single().sourceEventIds)
            } finally { firstDb.close() }

            val reopened = HamsterDatabase.open(context, dbName)
            try {
                val repository = HamsterRepository(reopened, prefs, TimeSource { now })
                val snapshot = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
                assertEquals(facts.map { it.event }.toSet(), snapshot.events.toSet())
                val result = snapshot.derive(now)
                assertEquals(1, result.sessions.size)
                assertEquals(entered, result.sessions.single().start)
                assertEquals(left, result.sessions.single().end)
                assertTrue(result.reviews.none { it.reason == ReviewReason.REPEATED_ENTER })
            } finally { reopened.close() }
        } finally {
            scope.cancel()
            context.deleteDatabase(dbName)
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun twoOfficeTransitionsReplayAndUnknownOfficeAreAtomic() = runBlocking {
        val name = "capture-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val database = HamsterDatabase.open(context, "$name.db")
        try {
            val repository = HamsterRepository(database, prefs, TimeSource { receipt.plusSeconds(7200) })
            repository.saveOffice(Office("a", "Synthetic A", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0))
            repository.saveOffice(Office("b", "Synthetic B", 1.0, 1.0, entryGraceMinutes = 0, exitGraceMinutes = 0))
            val observed = Location("synthetic").apply { time = receipt.minusSeconds(2).toEpochMilli(); accuracy = 23f }
            val enters = GeofenceObservation.records(listOf("a", "b"), Transition.ENTER, observed, receipt)
            repository.appendRawEvents(enters)
            repository.appendRawEvents(GeofenceObservation.records(listOf("a", "b"), Transition.ENTER,
                Location("synthetic").apply { time = observed.time; accuracy = 31f }, receipt.plusSeconds(30)))
            assertEquals(2, database.dao().events().size)
            assertEquals(receipt.minusSeconds(2), enters.single { it.event.officeId == "a" }.event.at)
            assertEquals(receipt, database.dao().events().first().let { Instant.ofEpochMilli(it.receivedAt) })
            assertEquals(23f, database.dao().events().first().accuracyMeters!!, 0f)

            val exits = GeofenceObservation.records(listOf("a", "b"), Transition.EXIT,
                Location("synthetic").apply { time = receipt.minusSeconds(2).plusSeconds(1800).toEpochMilli() }, receipt.plusSeconds(1802))
            repository.appendRawEvents(exits)
            val ready = repository.state.first { it is StorageState.Ready } as StorageState.Ready
            assertEquals(4, ready.snapshot.events.size)
            assertEquals(30.0, ready.snapshot.derive(receipt.plusSeconds(1802)).intervals.sumOf { it.minutes }, 0.001)
            var rejected = false
            try {
                repository.appendRawEvents(GeofenceObservation.records(listOf("a", "unknown"), Transition.ENTER,
                    null, receipt.plusSeconds(3600)))
            } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(4, database.dao().events().size)
        } finally {
            database.close()
            scope.cancel()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun missingOrFutureLocationTimeUsesReceiptWithoutInventingHistory() {
        val absent = GeofenceObservation.records(listOf("a"), Transition.ENTER, null, receipt).single()
        val future = GeofenceObservation.records(listOf("a"), Transition.ENTER,
            Location("synthetic").apply { time = receipt.plusSeconds(60).toEpochMilli() }, receipt).single()
        assertEquals(receipt, absent.event.at)
        assertEquals(null, absent.observedLocationAt)
        assertEquals(null, absent.accuracyMeters)
        assertEquals(receipt, future.event.at)
        assertEquals(null, future.observedLocationAt)
        assertNotEquals(absent.event.id, GeofenceObservation.records(listOf("b"), Transition.ENTER, null, receipt).single().event.id)
        var rejected = false
        try { GeofenceObservation.parse(null, receipt) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun oversizedOrMalformedTransitionBatchIsRejectedBeforePersistence() {
        for (ids in listOf(List(101) { "office-$it" }, listOf(""), listOf("a".repeat(201)))) {
            var rejected = false
            try { GeofenceObservation.records(ids, Transition.ENTER, null, receipt) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }
}
