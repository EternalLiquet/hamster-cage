package dev.hamstercage.capture

import android.location.Location
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
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

    @Test fun twoOfficeTransitionsReplayAndUnknownOfficeAreAtomic() = runBlocking {
        val name = "capture-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val database = HamsterDatabase.open(context, "$name.db")
        try {
            val repository = HamsterRepository(database, prefs, TimeSource { receipt.plusSeconds(7200) })
            repository.saveOffice(Office("a", "Synthetic A", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0))
            repository.saveOffice(Office("b", "Synthetic B", 1.0, 1.0, entryGraceMinutes = 0, exitGraceMinutes = 0))
            val observed = Location("synthetic").apply { time = receipt.minusSeconds(2).toEpochMilli() }
            val enters = GeofenceObservation.records(listOf("a", "b"), Transition.ENTER, observed, receipt)
            repository.appendRawEvents(enters)
            repository.appendRawEvents(GeofenceObservation.records(listOf("a", "b"), Transition.ENTER,
                observed, receipt.plusSeconds(30)))
            assertEquals(2, database.dao().events().size)
            assertEquals(receipt.minusSeconds(2), enters.single { it.event.officeId == "a" }.event.at)
            assertEquals(receipt, database.dao().events().first().let { Instant.ofEpochMilli(it.receivedAt) })

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
        assertEquals(receipt, future.event.at)
        assertEquals(null, future.observedLocationAt)
        assertNotEquals(absent.event.id, GeofenceObservation.records(listOf("b"), Transition.ENTER, null, receipt).single().event.id)
        var rejected = false
        try { GeofenceObservation.parse(null, receipt) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
