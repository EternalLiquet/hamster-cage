package dev.hamstercage.privacy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.PolicySettings
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Correction
import dev.hamstercage.domain.ExcludedDate
import dev.hamstercage.domain.ExclusionReason
import dev.hamstercage.domain.ManualSession
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDeletionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun cancelDoesNothingAndFailedCleanupResumesFromFreshJournal() = runBlocking {
        val name = "privacy-journal-${UUID.randomUUID()}"
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var journal = PrivacyResetJournal(PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(name)
        })
        val retained = mutableListOf("raw", "correction", "calendar")
        var coveragePresent = true
        var healthPresent = true
        var fail = true
        fun protocol() = HistoryDeletionProtocol(journal, Mutex(),
            deleteFacts = { if (fail) error("synthetic interrupted transaction") else retained.clear() },
            resetCoverage = { coveragePresent = false },
            resetHealth = { healthPresent = false })
        try {
            assertFalse(protocol().run(beginIfNeeded = false))
            assertEquals(3, retained.size)
            assertEquals(PrivacyResetState.Idle(0), journal.read())
            try { protocol().run(beginIfNeeded = true); error("Expected failure") }
            catch (expected: IllegalStateException) { assertEquals("synthetic interrupted transaction", expected.message) }
            assertEquals(3, retained.size)
            assertEquals(PrivacyResetState.Pending(1), journal.read())
            assertTrue(coveragePresent && healthPresent)

            // A fresh DataStore scope simulates a new process, not the old in-memory cache.
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            journal = PrivacyResetJournal(PreferenceDataStoreFactory.create(scope = scope) {
                context.preferencesDataStoreFile(name)
            })
            fail = false
            assertTrue(protocol().run(beginIfNeeded = false))
            assertTrue(retained.isEmpty())
            assertFalse(coveragePresent || healthPresent)
            assertEquals(PrivacyResetState.Idle(1), journal.read())
        } finally {
            scope.cancel()
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun roomFailureRollsBackEveryFactAndConfirmedDeleteSurvivesRestart() = runBlocking {
        val name = "privacy-room-${UUID.randomUUID()}"
        val now = Instant.parse("2025-03-10T20:00:00Z")
        val office = Office("office", "Synthetic office", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        var database = HamsterDatabase.open(context, "$name.db")
        try {
            val repository = HamsterRepository(database, preferences, TimeSource { now })
            repository.saveOffice(office)
            val enter = RawEvent("enter", office.id, Transition.ENTER, Instant.parse("2025-03-10T09:00:00Z"))
            val exit = RawEvent("exit", office.id, Transition.EXIT, Instant.parse("2025-03-10T10:00:00Z"))
            val openEnter = RawEvent("open-enter", office.id, Transition.ENTER, Instant.parse("2025-03-10T12:00:00Z"))
            repository.appendRawEvents(listOf(RecordedEvent(enter, enter.at), RecordedEvent(exit, exit.at), RecordedEvent(openEnter, openEnter.at)))
            repository.appendCorrection(Correction("correction", "session:enter",
                Instant.parse("2025-03-10T09:15:00Z"), exit.at, now))
            repository.appendManualSession(ManualSession("manual", office.id,
                Instant.parse("2025-03-10T11:00:00Z"), Instant.parse("2025-03-10T11:30:00Z"), now))
            repository.saveExclusion(ExcludedDate(LocalDate.parse("2025-03-11"), ExclusionReason.PTO, "Synthetic leave"))
            repository.setWfh(LocalDate.parse("2025-03-12"), true)
            repository.savePolicy(PolicySettings(zoneId = ZoneId.of("America/Indianapolis"), targetMinutesPerDay = 420))
            try { repository.deleteAttendanceAndCalendarHistory { error("synthetic transaction failure") } }
            catch (expected: IllegalStateException) { assertEquals("synthetic transaction failure", expected.message) }
            assertEquals(3, database.dao().events().size)
            assertEquals(1, database.dao().corrections().size)
            assertEquals(1, database.dao().manualSessions().size)
            assertEquals(1, database.dao().exclusions().size)
            assertEquals(1, database.dao().labels().size)

            repository.deleteAttendanceAndCalendarHistory()
            database.close(); scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
            database = HamsterDatabase.open(context, "$name.db")
            val reopened = HamsterRepository(database, preferences, TimeSource { now })
            val state = reopened.state.first { it is StorageState.Ready } as StorageState.Ready
            assertEquals(listOf(office), state.snapshot.offices)
            assertEquals(420, state.snapshot.policy.targetMinutesPerDay)
            assertEquals(ZoneId.of("America/Indianapolis"), state.snapshot.policy.zoneId)
            assertTrue(state.snapshot.events.isEmpty())
            assertTrue(state.snapshot.corrections.isEmpty())
            assertTrue(state.snapshot.manualSessions.isEmpty())
            assertTrue(state.snapshot.policy.excludedDates.isEmpty())
            assertTrue(state.snapshot.policy.wfhDates.isEmpty())
            assertTrue(state.snapshot.derive(now).sessions.isEmpty())
        } finally {
            database.close()
            scope.cancel()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }
}
