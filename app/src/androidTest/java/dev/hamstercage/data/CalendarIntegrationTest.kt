package dev.hamstercage.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CalendarIntegrationTest {
    @Test fun calendarCrudReopenAndRecomputationKeepOfficeCreditAndIndependentLabels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "calendar-${UUID.randomUUID()}"
        val now = Instant.parse("2026-09-23T16:00:00Z")
        val day = LocalDate.of(2026, 9, 23)
        val future = day.plusYears(1)
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        var database = HamsterDatabase.open(context, "$name.db")
        var repository = HamsterRepository(database, preferences, TimeSource { now })
        suspend fun snapshot() = (withTimeout(10_000) { repository.state.first() } as StorageState.Ready).snapshot
        fun assertDaily(snapshot: AppSnapshot, expected: Int) {
            val daily = AttendanceEngine.daily(snapshot.input(now), snapshot.derive(now), day)
            assertEquals(expected, daily.requiredMinutes)
            assertEquals(60.0, daily.creditedMinutes, 0.001)
        }
        try {
            assertTrue(snapshot().policy.excludedDates.isEmpty()) // No invented employer feed.
            assertTrue(snapshot().policy.wfhDates.isEmpty())
            val office = Office("synthetic", "Synthetic office", 0.0, 0.0, entryGraceMinutes = 0, exitGraceMinutes = 0)
            val manual = ManualSession("manual", office.id, now.minusSeconds(3600), now, now)
            repository.saveOffice(office); repository.appendManualSession(manual)
            assertDaily(snapshot(), 360)
            repository.setWfh(day, true); repository.setWfh(day, true)
            assertDaily(snapshot(), 360)
            repository.saveExclusion(ExcludedDate(day, ExclusionReason.BANK_HOLIDAY, "Synthetic note"))
            repository.saveExclusion(ExcludedDate(day, ExclusionReason.PTO, "Revised synthetic note"))
            repository.saveExclusion(ExcludedDate(future, ExclusionReason.COMPANY_CLOSURE))
            repository.setWfh(future, true)
            val before = snapshot()
            assertDaily(before, 0)
            assertEquals(2, before.policy.excludedDates.size)
            assertEquals(ExclusionReason.PTO, before.policy.excludedDates.single { it.date == day }.reason)
            assertEquals(setOf(day, future), before.policy.wfhDates)
            database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
            database = HamsterDatabase.open(context, "$name.db")
            repository = HamsterRepository(database, preferences, TimeSource { now })
            assertEquals(before, snapshot())
            repository.removeExclusion(day)
            assertDaily(snapshot(), 360)
            assertTrue(day in snapshot().policy.wfhDates)
            repository.setWfh(future, false)
            assertTrue(snapshot().policy.excludedDates.any { it.date == future })
            repository.setWfh(day, false); repository.removeExclusion(future)
            val after = snapshot()
            assertTrue(after.policy.excludedDates.isEmpty()); assertTrue(after.policy.wfhDates.isEmpty())
            assertEquals(listOf(manual), after.manualSessions)
            assertDaily(after, 360)
        } finally {
            database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            context.deleteDatabase("$name.db"); context.preferencesDataStoreFile(name).delete()
        }
    }
}
