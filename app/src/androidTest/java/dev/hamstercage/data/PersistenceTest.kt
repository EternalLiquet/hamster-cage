package dev.hamstercage.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(name: String) = Room.databaseBuilder(context, HamsterDatabase::class.java, name).build()
    private fun office() = OfficeRecord("test-office", "Test office", 0.0, 0.0, 150f, true, true, 5, 5, 1, 1, 1)
    private fun event(id: String = "event-1") = EventRecord(id, "test-office", "ENTER", 1000, 1000, 900, "PLAY_SERVICES_GEOFENCE")

    @Test fun rawEvidenceAndCorrectionSurviveDatabaseReopenWithoutRewritingEvidence() = runBlocking {
        val name = "persistence-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            db.dao().upsertOffice(office())
            val original = event()
            db.dao().insertEvents(listOf(original))
            db.dao().insertCorrection(CorrectionRecord("correction-1", "session:event-1", 800, 3000, 4000, "Verified times"))
            db.close()
            db = database(name)
            assertEquals(listOf(original), db.dao().events().first())
            assertEquals(800L, db.dao().corrections().first().single().start)
            assertEquals(900L, db.dao().events().first().single().observedLocationAt)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun duplicateEventIdCannotOverwriteImmutableSourceFact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, HamsterDatabase::class.java).build()
        try {
            val original = event()
            db.dao().insertEvents(listOf(original))
            db.dao().insertEvents(listOf(original.copy(at = 9999, source = "ALTERED")))
            assertEquals(listOf(original), db.dao().events().first())
        } finally { db.close() }
    }

    @Test fun independentManualSessionDoesNotMutateAutomaticEvidenceAndRejectsOverwrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, HamsterDatabase::class.java).build()
        try {
            val original = event()
            db.dao().insertEvents(listOf(original))
            val manual = ManualSessionRecord("manual-1", "test-office", 1100, 2000, 3000, "Verified")
            db.dao().insertManualSession(manual)
            var failed = false
            try { db.dao().insertManualSession(manual.copy(start = 9000)) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertEquals(listOf(original), db.dao().events().first())
            assertEquals(listOf(manual), db.dao().manualSessions().first())
        } finally { db.close() }
    }

    @Test fun attendanceDeletionKeepsOfficesAndClearsAllAttendanceFacts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, HamsterDatabase::class.java).build()
        try {
            db.dao().upsertOffice(office())
            db.dao().insertEvents(listOf(event()))
            db.dao().insertCorrection(CorrectionRecord("correction-1", "session:event-1", 1000, 2000, 3000, ""))
            db.dao().insertManualSession(ManualSessionRecord("manual-1", "test-office", 1100, 2000, 3000, ""))
            db.dao().upsertExclusion(ExclusionRecord("2026-09-23", "PTO", "", 1))
            db.dao().upsertLabel(DayLabelRecord("2026-09-23", true, 1))
            db.dao().clearAttendance()
            assertTrue(db.dao().events().first().isEmpty())
            assertTrue(db.dao().corrections().first().isEmpty())
            assertTrue(db.dao().exclusions().first().isEmpty())
            assertTrue(db.dao().labels().first().isEmpty())
            assertTrue(db.dao().manualSessions().first().isEmpty())
            assertEquals(listOf(office()), db.dao().officeList())
        } finally { db.close() }
    }

    @Test fun repositoryRejectsDeletionWithoutExactConfirmation() = runBlocking {
        var rejected = false
        try { HamsterRepository(context).deleteAttendanceHistory("delete") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
}
