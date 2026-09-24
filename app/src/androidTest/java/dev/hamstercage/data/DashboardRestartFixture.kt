package dev.hamstercage.data

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in host fixture, not an acceptance test. scripts/verify_dashboard_restart.py checks the behavior. */
class DashboardRestartFixture {
    @Test fun prepareSyntheticSourceForHostProcessRestartCheck() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureAction") == "seed-dashboard-restart")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = HamsterDatabase.open(context)
        try {
            // Never replace an existing attendance record, even on the test emulator.
            val dao = database.dao()
            check(dao.offices().isEmpty() && dao.events().isEmpty() && dao.corrections().isEmpty() &&
                dao.manualSessions().isEmpty() && dao.exclusions().isEmpty() && dao.labels().isEmpty())
        } finally { database.close() }
        val repository = HamsterRepository.get(context)
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val start = now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(5 * 60)
        val office = Office("dashboard-restart-fixture", "Synthetic restart office", 0.0, 0.0,
            entryGraceMinutes = 0, exitGraceMinutes = 0)
        repository.saveOffice(office)
        repository.appendRawEvents(listOf(RecordedEvent(RawEvent("dashboard-restart-enter", office.id, Transition.ENTER, start), now)))
        instrumentation.sendStatus(0, Bundle().apply { putString("fixtureStartEpochSecond", start.epochSecond.toString()) })
    }
}
