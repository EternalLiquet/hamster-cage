package dev.hamstercage.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PolicyIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyAndVersionedEmptyWeekdaysFailClosedWithoutReplacingPersistedBytes() = runBlocking {
        for (version in listOf<Int?>(null, 1)) {
            val name = "empty-weekdays-${UUID.randomUUID()}"
            val file = context.preferencesDataStoreFile(name)
            var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
            val database = HamsterDatabase.open(context, "$name.db")
            try {
                preferences.edit {
                    it[stringSetPreferencesKey("expected_weekdays")] = emptySet()
                    version?.let { value -> it[intPreferencesKey("policy_schema_version")] = value }
                }
                scope.cancel(); scope.coroutineContext[Job]?.join()
                val original = file.readBytes()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                preferences = PreferenceDataStoreFactory.create(
                    migrations = listOf(LegacyPolicyMigration), scope = scope,
                ) { file }
                val repository = HamsterRepository(database, preferences, TimeSource { Instant.parse("2026-09-23T16:00:00Z") })
                assertEquals(StorageState.Unavailable, withTimeout(10_000) { repository.state.first() })
                var rejected = false
                try { repository.savePolicy(PolicySettings()) } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue("Invalid saved policy must not be overwritten", rejected)
                scope.cancel(); scope.coroutineContext[Job]?.join()
                assertArrayEquals(original, file.readBytes())
            } finally {
                database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
                context.deleteDatabase("$name.db"); file.delete()
            }
        }
    }

    @Test fun validationAndStaleEditsAreAtomicAndRestartKeepsExplicitZone() = runBlocking {
        val name = "policy-${UUID.randomUUID()}"
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val originalZone = TimeZone.getDefault()
        try {
            val store = PolicyStore(preferences)
            assertEquals(PolicySettings(), store.settings.first())
            val saved = PolicySettings(targetMinutesPerDay = 420, shortGapMinutes = 7, expectedWeekdays = setOf(DayOfWeek.SUNDAY))
            store.save(saved, PolicySettings())
            suspend fun rejected(value: PolicySettings, expected: PolicySettings? = null) {
                var failed = false
                try { store.save(value, expected) } catch (_: IllegalArgumentException) { failed = true } catch (_: IllegalStateException) { failed = true }
                assertTrue(failed)
                assertEquals(saved, store.settings.first())
            }
            rejected(saved.copy(targetMinutesPerDay = 0))
            rejected(saved.copy(shortGapMinutes = 121))
            rejected(saved.copy(expectedWeekdays = emptySet()))
            rejected(saved.copy(targetMinutesPerDay = 480), PolicySettings())
            scope.cancel(); scope.coroutineContext[Job]?.join()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
            assertEquals(saved, PolicyStore(preferences).settings.first())
            assertEquals(ZoneId.of("America/New_York"), PolicyStore(preferences).settings.first().zoneId)
        } finally {
            TimeZone.setDefault(originalZone)
            scope.cancel(); scope.coroutineContext[Job]?.join()
            context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun policyChangesRecomputeDstHistoryWithoutChangingRawEvents() = runBlocking {
        val name = "policy-history-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = HamsterDatabase.open(context, "$name.db")
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val now = Instant.parse("2025-03-10T12:00:00Z")
        val repository = HamsterRepository(database, preferences, TimeSource { now })
        try {
            val office = Office("synthetic", "Synthetic office", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
            repository.saveOffice(office)
            val raw = listOf(RawEvent("in", office.id, Transition.ENTER, Instant.parse("2025-03-09T04:30:00Z")),
                RawEvent("out", office.id, Transition.EXIT, Instant.parse("2025-03-09T07:30:00Z")))
            repository.appendRawEvents(raw.map { RecordedEvent(it, it.at) })
            val settings = PolicySettings(expectedWeekdays = setOf(DayOfWeek.SUNDAY))
            repository.savePolicy(settings)
            val before = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
            val date = LocalDate.of(2025, 3, 9)
            assertEquals(150.0, AttendanceEngine.daily(before.input(now), before.derive(now), date).creditedMinutes, 0.001)
            repository.savePolicy(settings.copy(zoneId = ZoneId.of("UTC"), targetMinutesPerDay = 420), settings)
            val after = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
            val daily = AttendanceEngine.daily(after.input(now), after.derive(now), date)
            assertEquals(180.0, daily.creditedMinutes, 0.001)
            assertEquals(420, daily.requiredMinutes)
            assertEquals(raw, after.events)
            assertEquals(before.eventEvidence, after.eventEvidence)
        } finally {
            database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            context.deleteDatabase("$name.db"); context.preferencesDataStoreFile(name).delete()
        }
    }
}
