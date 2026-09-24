package dev.hamstercage.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host fixture, not an acceptance test. scripts/verify_dashboard_restart.py checks the behavior. */
class DashboardRestartFixture {
    @Test fun prepareSyntheticSourceForHostProcessRestartCheck() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureAction") == "seed-dashboard-restart")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val office = Office("dashboard-restart-fixture", "Synthetic restart office", 0.0, 0.0,
            entryGraceMinutes = 0, exitGraceMinutes = 0)
        val database = HamsterDatabase.open(context)
        var existingStart: Instant? = null
        try {
            // Never replace an existing attendance record, even on the test emulator.
            val dao = database.dao()
            check(dao.corrections().isEmpty() && dao.manualSessions().isEmpty() && dao.exclusions().isEmpty() && dao.labels().isEmpty())
            val offices = dao.offices()
            val events = dao.events()
            if (offices.isNotEmpty() || events.isNotEmpty()) {
                // A retry may reuse only the exact recent synthetic source, without changing it.
                val saved = offices.single()
                val event = events.single()
                check(saved.id == office.id && saved.name == office.name && saved.latitude == 0.0 && saved.longitude == 0.0 &&
                    saved.radiusMeters == office.radiusMeters && saved.entryGraceMinutes == 0 && saved.exitGraceMinutes == 0 &&
                    saved.enabled && saved.countsTowardAttendance)
                check(event.id == "dashboard-restart-enter" && event.officeId == office.id && event.transition == "ENTER" &&
                    event.source == "PLAY_SERVICES_GEOFENCE" && event.observedLocationAt == null && event.payloadVersion == 1)
                existingStart = Instant.ofEpochMilli(event.at)
                check(existingStart!! <= now && existingStart!! >= now.minusSeconds(30 * 60))
            }
        } finally { database.close() }
        val repository = HamsterRepository.get(context)
        val snapshot = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
        check(snapshot.policy == Policy())
        val start = existingStart ?: now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(5 * 60)
            .coerceAtLeast(now.atZone(snapshot.policy.zoneId).toLocalDate().atStartOfDay(snapshot.policy.zoneId).toInstant())
        if (existingStart == null) {
            repository.saveOffice(office)
            repository.appendRawEvents(listOf(RecordedEvent(RawEvent("dashboard-restart-enter", office.id, Transition.ENTER, start), now)))
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("fixtureStartEpochSecond", start.epochSecond.toString()) })
    }
}
