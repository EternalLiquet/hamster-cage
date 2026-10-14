package dev.hamstercage.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegistrationDiagnosticsStorageTest {
    @get:Rule val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), HamsterDatabase::class.java,
    )
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixedNow = Instant.parse("2026-10-13T20:00:00Z")
    private val clock = TimeSource { fixedNow }

    private fun cycle(id: String, at: Instant, planned: Int = 1) = RegistrationCycle(id, RegistrationKind.SYNC,
        RegistrationReason.PROCESS_START, 0, planned, at, null, RegistrationOutcome.PENDING, null, null, null)
    private fun attempt(id: String, cycleId: String, at: Instant) = RegistrationAttempt(id, cycleId, "office-a", 2,
        0, at, null, RegistrationOutcome.PENDING, null)

    private fun withRepository(name: String, block: suspend (HamsterDatabase, HamsterRepository) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val database = HamsterDatabase.open(context, "$name.db")
        try { block(database, HamsterRepository(database, preferences, clock)) }
        finally {
            database.close()
            scope.cancel()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun versionThreeFactsAndCorrectionHistoryMigrateUnchangedWithUnknownFixMetadata() = runBlocking {
        val name = "registration-migration-${UUID.randomUUID()}.db"
        migration.createDatabase(name, 3).use { sqlite ->
            sqlite.execSQL("INSERT INTO offices VALUES ('fixture-office','Synthetic office',0,0,150,1,1,0,0,1,1,1)")
            sqlite.execSQL("INSERT INTO raw_events VALUES ('old-enter','fixture-office','ENTER',1741597200000,1741597201000,1741597200000,'PLAY_SERVICES_GEOFENCE',1,12.5)")
            sqlite.execSQL("INSERT INTO corrections VALUES ('edit-1','session:old-enter',1741597200000,1741600800000,1741640000000,'synthetic',0,1)")
            sqlite.execSQL("INSERT INTO corrections VALUES ('edit-2','session:old-enter',1741597200000,1741600800000,1741640000001,'synthetic',1,2)")
        }
        val database = HamsterDatabase.open(context, name)
        try {
            val event = database.dao().events().single()
            assertEquals(EventRecord("old-enter", "fixture-office", "ENTER", 1741597200000, 1741597201000,
                1741597200000, "PLAY_SERVICES_GEOFENCE", 1, 12.5f, null, null, null), event)
            assertEquals(listOf("edit-1", "edit-2"), database.dao().corrections().map { it.id })
            assertEquals(listOf(false, true), database.dao().corrections().map { it.revertToOriginal })
            assertTrue(database.dao().registrationCycles().isEmpty())
            assertTrue(database.dao().registrationAttempts().isEmpty())
            val log = RoomRegistrationLog(database)
            log.cycleStarted(cycle("c", fixedNow))
            log.attemptStarted(attempt("x", "c", fixedNow))
            assertEquals(1, database.dao().registrationAttempts().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun historyDeletionRemovesDiagnosticsAndLateCallbacksCannotRestoreThem() = withRepository(
        "registration-privacy-${UUID.randomUUID()}") { database, repository ->
        val log = repository.registrationLog
        log.cycleStarted(cycle("c", fixedNow))
        log.attemptStarted(attempt("x", "c", fixedNow))
        assertEquals(1, repository.registrationDiagnostics.first()!!.attempts.size)

        repository.deleteAttendanceAndCalendarHistory()
        // A Play services result or cancellation that lands after the deletion only updates.
        log.attemptFinished("x", fixedNow.plusSeconds(1), RegistrationOutcome.SUCCEEDED, null)
        log.cycleFinished("c", fixedNow.plusSeconds(1), RegistrationOutcome.SUCCEEDED, RegistrationStep.REMOVED, null, null)
        // An attempt for a cycle that no longer exists is not written.
        log.attemptStarted(attempt("y", "c", fixedNow.plusSeconds(2)))
        log.markInterrupted()
        assertTrue(database.dao().registrationCycles().isEmpty())
        assertTrue(database.dao().registrationAttempts().isEmpty())
        val after = repository.registrationDiagnostics.first()!!
        assertTrue(after.cycles.isEmpty() && after.attempts.isEmpty())
    }

    @Test fun completionUpdatesOnlyPendingRowsAndInterruptedRowsStayUnknown() = withRepository(
        "registration-complete-${UUID.randomUUID()}") { database, repository ->
        val log = repository.registrationLog
        log.cycleStarted(cycle("c", fixedNow, planned = 2))
        log.attemptStarted(attempt("ok", "c", fixedNow))
        log.attemptStarted(attempt("lost", "c", fixedNow.plusMillis(1)))
        log.attemptFinished("ok", fixedNow.plusSeconds(1), RegistrationOutcome.FAILED, "API_1000")
        log.markInterrupted()
        // A result arriving after the row was resolved does not turn it into success.
        log.attemptFinished("lost", fixedNow.plusSeconds(5), RegistrationOutcome.SUCCEEDED, null)
        val attempts = repository.registrationDiagnostics.first()!!.attempts.associateBy { it.id }
        assertEquals(RegistrationOutcome.FAILED, attempts.getValue("ok").status)
        assertEquals("API_1000", attempts.getValue("ok").errorCode)
        assertEquals(RegistrationOutcome.INTERRUPTED, attempts.getValue("lost").status)
        assertNull(attempts.getValue("lost").completedAt)
        assertEquals(RegistrationOutcome.INTERRUPTED, database.dao().registrationCycles().single().toEvidence().outcome)
    }

    @Test fun retentionPrunesByAgeWhenANewCycleStarts() = withRepository(
        "registration-retention-${UUID.randomUUID()}") { database, repository ->
        val log = repository.registrationLog
        val old = fixedNow.minusSeconds((RegistrationDiagnostics.RETENTION_DAYS + 1) * 24 * 60 * 60)
        log.cycleStarted(cycle("old", old))
        log.attemptStarted(attempt("old-x", "old", old))
        val recent = fixedNow.minusSeconds(24 * 60 * 60)
        log.cycleStarted(cycle("recent", recent))
        log.attemptStarted(attempt("recent-x", "recent", recent))
        log.cycleStarted(cycle("now", fixedNow))
        assertEquals(listOf("recent", "now"), database.dao().registrationCycles().map { it.id })
        assertEquals(listOf("recent-x"), database.dao().registrationAttempts().map { it.id })
    }


    @Test fun cycleCountRetentionCannotLeaveOrphanAttempts() = withRepository(
        "registration-cycle-limit-${UUID.randomUUID()}") { database, repository ->
        val dao = database.dao()
        val oldest = fixedNow.minusSeconds(RegistrationDiagnostics.MAX_CYCLES.toLong())
        repository.registrationLog.cycleStarted(cycle("oldest", oldest))
        repository.registrationLog.attemptStarted(attempt("oldest-add", "oldest", oldest.plusSeconds(1)))
        repeat(RegistrationDiagnostics.MAX_CYCLES - 1) { index ->
            val at = oldest.plusSeconds(index + 1L)
            dao.insertCycle(RegistrationCycleRecord("remove-$index", "REMOVE", "PROCESS_START", 0, 0,
                at.toEpochMilli(), null, "PENDING", null, null, null))
        }
        repository.registrationLog.cycleStarted(cycle("now", fixedNow))
        val cycleIds = dao.registrationCycles().map { it.id }.toSet()
        assertTrue(dao.registrationAttempts().all { it.cycleId in cycleIds })
        assertTrue(dao.registrationAttempts().isEmpty())
    }

    @Test fun attemptCountRetentionRemovesWholeCyclesIncludingTheirOtherAttempts() = withRepository(
        "registration-attempt-limit-${UUID.randomUUID()}") { database, repository ->
        val dao = database.dao()
        val at = fixedNow.minusSeconds(60)
        repository.registrationLog.cycleStarted(cycle("old", at, planned = 2))
        repository.registrationLog.attemptStarted(attempt("old-a", "old", at))
        repository.registrationLog.attemptStarted(attempt("old-b", "old", at.plusMillis(1)))
        dao.insertCycle(RegistrationCycleRecord("bulk", "SYNC", "PROCESS_START", 0,
            RegistrationDiagnostics.MAX_ATTEMPTS - 2, at.plusSeconds(1).toEpochMilli(), null,
            "PENDING", null, null, null))
        repeat(RegistrationDiagnostics.MAX_ATTEMPTS - 2) { index ->
            dao.insertAttempt(RegistrationAttemptRecord("bulk-$index", "bulk", "office-a", 1, 0,
                at.plusSeconds(2).toEpochMilli(), null, "PENDING", null))
        }
        repository.registrationLog.cycleStarted(cycle("now", fixedNow))
        assertTrue(dao.registrationCycles().none { it.id == "old" })
        assertTrue(dao.registrationAttempts().none { it.cycleId == "old" })
        assertEquals(RegistrationDiagnostics.MAX_ATTEMPTS - 2, dao.registrationAttempts().size)
    }

    @Test fun replayKeepsFirstSeenFixMetadataAndLegacyRowsStayUnknown() = withRepository(
        "registration-fix-${UUID.randomUUID()}") { database, repository ->
        repository.saveOffice(Office("office-a", "Synthetic office", 0.0, 0.0))
        val at = fixedNow.minusSeconds(60)
        val first = RecordedEvent(RawEvent("enter", "office-a", Transition.ENTER, at), fixedNow, at,
            accuracyMeters = 4f, fix = FixMetadata(1_500, "fused", false))
        val legacy = RecordedEvent(RawEvent("legacy", "office-a", Transition.EXIT, at.plusSeconds(10)), fixedNow, null)
        repository.appendRawEvents(listOf(first, legacy))
        // Play services may redeliver the same observation later with a different fix age.
        repository.appendRawEvents(listOf(first.copy(receivedAt = fixedNow.plusSeconds(30),
            accuracyMeters = 9f, fix = FixMetadata(31_500, "gps", true))))
        val stored = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
            .eventEvidence.associateBy { it.event.id }
        assertEquals(FixMetadata(1_500, "fused", false), stored.getValue("enter").fix)
        assertEquals(fixedNow, stored.getValue("enter").receivedAt)
        assertNull(stored.getValue("legacy").fix)
        assertEquals(2, database.dao().events().size)
    }
}
