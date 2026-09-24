package dev.hamstercage.offices

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficeIntegrationTest {
    @Test fun twoOfficesEditIndependentlyAndDisableKeepsOpenEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "offices-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val now = Instant.parse("2025-03-10T12:00:00Z")
        val clock = TimeSource { now }
        val first = Office("office-a", "Synthetic A", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
        val second = Office("office-b", "Synthetic B", 1.0, 1.0)
        val database = HamsterDatabase.open(context, "$name.db")
        try {
            val repository = HamsterRepository(database, preferences, clock)
            repository.saveOffice(first)
            repository.saveOffice(second)
            val initial = (repository.state.first {
                it is StorageState.Ready && it.snapshot.offices.size == 2
            } as StorageState.Ready).snapshot
            assertEquals(listOf("office-a", "office-b"), initial.registrationIntents().map { it.officeId })
            val enter = RawEvent("synthetic-enter", first.id, Transition.ENTER, now.minusSeconds(3600))
            repository.appendRawEvents(listOf(RecordedEvent(enter, now)))

            repository.saveOffice(second.copy(radiusMeters = 300f, entryGraceMinutes = 12),
                repository.officeVersion(second.id))
            repository.saveOffice(first.copy(enabled = false), repository.officeVersion(first.id))
            val edited = (repository.state.first {
                it is StorageState.Ready && it.snapshot.offices.any { office -> !office.enabled }
            } as StorageState.Ready).snapshot
            assertEquals(listOf("office-b"), edited.registrationIntents().map { it.officeId })
            assertEquals(300f, edited.registrationIntents().single().radiusMeters, 0f)
            assertEquals(12, edited.offices.single { it.id == second.id }.entryGraceMinutes)
            assertFalse(edited.offices.single { it.id == first.id }.enabled)
            assertEquals(listOf(enter), edited.events)
            assertTrue("Disabled office cannot credit its open session", edited.derive(now).intervals.isEmpty())
            assertEquals(2L, repository.officeVersion(first.id))
            assertEquals(2L, repository.officeVersion(second.id))
            database.close()

            val reopened = HamsterDatabase.open(context, "$name.db")
            try {
                val recovered = (HamsterRepository(reopened, preferences, clock).state
                    .first { it is StorageState.Ready } as StorageState.Ready).snapshot
                assertEquals(2, recovered.offices.size)
                assertEquals(listOf(enter), recovered.events)
                assertEquals(listOf("office-b"), recovered.registrationIntents().map { it.officeId })
            } finally { reopened.close() }
        } finally {
            database.close()
            scope.cancel()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }
}
