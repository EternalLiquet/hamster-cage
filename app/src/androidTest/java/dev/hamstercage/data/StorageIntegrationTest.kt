package dev.hamstercage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    @get:Rule val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), HamsterDatabase::class.java,
    )
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixedNow = Instant.parse("2025-03-10T20:00:00Z")
    private val clock = TimeSource { fixedNow }

    @Test fun allFactsSurviveRestartAndDerivedMinutesRecompute() = runBlocking {
        val name = "storage-${UUID.randomUUID()}"
        val office = Office("office-one", "Synthetic office", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
        val enter = RawEvent("enter-one", office.id, Transition.ENTER, Instant.parse("2025-03-10T09:00:00Z"))
        val exit = RawEvent("exit-one", office.id, Transition.EXIT, Instant.parse("2025-03-10T10:00:00Z"))
        val correction = Correction("correction-one", "session:enter-one",
            Instant.parse("2025-03-10T09:15:00Z"), Instant.parse("2025-03-10T10:00:00Z"), fixedNow)
        val manual = ManualSession("manual-one", office.id,
            Instant.parse("2025-03-10T11:00:00Z"), Instant.parse("2025-03-10T11:30:00Z"), fixedNow)
        val excluded = ExcludedDate(LocalDate.parse("2025-03-11"), ExclusionReason.PTO, "Synthetic leave")
        val wfh = LocalDate.parse("2025-03-12")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(name)
        }
        val database = HamsterDatabase.open(context, "$name.db")
        try {
            val repository = HamsterRepository(database, preferences, clock)
            repository.saveOffice(office)
            repository.appendRawEvents(listOf(
                RecordedEvent(enter, enter.at), RecordedEvent(exit, exit.at),
            ))
            repository.appendCorrection(correction)
            repository.appendManualSession(manual)
            repository.saveExclusion(excluded)
            repository.setWfh(wfh, true)
            repository.savePolicy(PolicySettings(zoneId = ZoneId.of("America/Indianapolis"), targetMinutesPerDay = 420))
            database.close()

            val reopened = HamsterDatabase.open(context, "$name.db")
            try {
                val recovered = HamsterRepository(reopened, preferences, clock)
                val state = recovered.state.first { it is StorageState.Ready } as StorageState.Ready
                val snapshot = state.snapshot
                assertEquals(listOf(office), snapshot.offices)
                assertEquals(listOf(enter, exit), snapshot.events)
                assertEquals(listOf(correction), snapshot.corrections)
                assertEquals(listOf(manual), snapshot.manualSessions)
                assertEquals(listOf(excluded), snapshot.policy.excludedDates)
                assertEquals(setOf(wfh), snapshot.policy.wfhDates)
                assertEquals(420, snapshot.policy.targetMinutesPerDay)
                assertEquals(ZoneId.of("America/Indianapolis"), snapshot.policy.zoneId)
                assertEquals(75.0, snapshot.derive(fixedNow).intervals.sumOf { it.minutes }, 0.001)

                // A later policy calculation reads the same source facts, not a cached aggregate.
                assertEquals(75.0, snapshot.derive(fixedNow.plusSeconds(3600)).intervals.sumOf { it.minutes }, 0.001)
                val conflicting = RecordedEvent(enter.copy(at = enter.at.plusSeconds(60)), enter.at)
                var rejected = false
                try {
                    recovered.appendRawEvents(listOf(
                        RecordedEvent(RawEvent("new-event", office.id, Transition.ENTER, fixedNow), fixedNow),
                        conflicting,
                    ))
                } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue("Conflicting batch must fail", rejected)
                assertEquals(listOf(enter, exit), reopened.dao().events().map { it.toDomainEvent() })
                recovered.appendRawEvents(listOf(RecordedEvent(enter, enter.at)))
                assertEquals(2, reopened.dao().events().size)
            } finally { reopened.close() }
        } finally {
            database.close()
            scope.cancel()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun unsupportedSchemaDoesNotDeleteSourceDatabase() = runBlocking {
        val name = "unsupported-${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sqlite ->
            sqlite.execSQL("PRAGMA user_version = 2")
            sqlite.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
            sqlite.execSQL("INSERT INTO sentinel VALUES ('synthetic source fact')")
        }
        val database = HamsterDatabase.open(context, name)
        try {
            var rejected = false
            try { database.dao().offices() } catch (_: Exception) { rejected = true }
            assertTrue("Unsupported schema must fail visibly", rejected)
            assertTrue(dbFile.exists())
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("synthetic source fact", cursor.getString(0))
                }
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun preservedVersionOneSchemaReopensWithEveryFactType() = runBlocking {
        val name = "fixture-${UUID.randomUUID()}.db"
        migration.createDatabase(name, 1).use { sqlite ->
            sqlite.execSQL("INSERT INTO offices VALUES ('fixture-office','Synthetic office',0,0,150,1,1,0,0,1,1,1)")
            sqlite.execSQL("INSERT INTO raw_events VALUES ('fixture-enter','fixture-office','ENTER',1741597200000,1741597200000,NULL,'PLAY_SERVICES_GEOFENCE',1)")
            sqlite.execSQL("INSERT INTO corrections VALUES ('fixture-correction','session:fixture-enter',1741597200000,1741600800000,1741640000000,'synthetic')")
            sqlite.execSQL("INSERT INTO manual_sessions VALUES ('fixture-manual','fixture-office',1741604400000,1741606200000,1741640000000,'synthetic')")
            sqlite.execSQL("INSERT INTO excluded_dates VALUES ('2025-03-11','PTO','synthetic',1741640000000)")
            sqlite.execSQL("INSERT INTO day_labels VALUES ('2025-03-12',1,1741640000000)")
        }
        val database = HamsterDatabase.open(context, name)
        try {
            assertEquals(1, database.dao().offices().size)
            assertEquals(1, database.dao().events().size)
            assertEquals(1, database.dao().corrections().size)
            assertEquals(1, database.dao().manualSessions().size)
            assertEquals(1, database.dao().exclusions().size)
            assertEquals(1, database.dao().labels().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun legacyPolicyMigrationPreservesValuesAndRejectsUnknownVersion() = runBlocking {
        val legacy = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("zone")] = "America/Chicago"
            this[intPreferencesKey("target_minutes")] = 390
        }
        assertTrue(LegacyPolicyMigration.shouldMigrate(legacy))
        val migrated = LegacyPolicyMigration.migrate(legacy)
        assertEquals("America/Chicago", migrated[stringPreferencesKey("zone")])
        assertEquals(390, migrated[intPreferencesKey("target_minutes")])
        assertEquals(1, migrated[intPreferencesKey("policy_schema_version")])
        assertFalse(LegacyPolicyMigration.shouldMigrate(migrated))

        val future = emptyPreferences().toMutablePreferences().apply {
            this[intPreferencesKey("policy_schema_version")] = 2
        }
        val name = "policy-future-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(name)
        }
        try {
            preferences.updateData { future }
            var rejected = false
            try { PolicyStore(preferences).settings.first() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("A newer policy schema must be visible as an error", rejected)
            assertEquals(2, preferences.data.first()[intPreferencesKey("policy_schema_version")])
        } finally {
            scope.cancel()
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun corruptDatabaseRemainsForRecovery() = runBlocking {
        val name = "corrupt-${UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        val original = "synthetic broken database".toByteArray()
        dbFile.writeBytes(original)
        val database = HamsterDatabase.open(context, name)
        try {
            var rejected = false
            try { database.dao().offices() } catch (_: Exception) { rejected = true }
            assertTrue("Unreadable source storage must surface a failure", rejected)
            assertTrue("Recovery file must remain", dbFile.exists())
            assertTrue("Unreadable source storage must not be reset", dbFile.readBytes().contentEquals(original))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}

private fun EventRecord.toDomainEvent() = RawEvent(id, officeId, Transition.valueOf(transition), Instant.ofEpochMilli(at))
