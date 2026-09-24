package dev.hamstercage.data

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.TargetWindow
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in synthetic fixture for scripts/verify_daily_leave_process_restart.py. */
class DailyLeaveProcessFixture {
    @Test fun seedOrProbeSavedDailyProjection() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("fixtureAction")
        assumeTrue(action == "seed-daily-leave" || action == "probe-daily-leave")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = HamsterRepository.get(context)
        val initial = (repository.state.first { it is StorageState.Ready } as StorageState.Ready).snapshot
        val office = Office("daily-leave-process-fixture", "Synthetic daily leave office", 0.0, 0.0,
            entryGraceMinutes = 0, exitGraceMinutes = 0)
        if (action == "seed-daily-leave") {
            // Never overwrite or clear any pre-existing attendance, even on an emulator.
            check(initial.corrections.isEmpty() && initial.manualSessions.isEmpty() &&
                initial.offices.size <= 1 && initial.events.size <= 1 &&
                initial.offices.all { it == office } && initial.events.all {
                    it.id == "daily-leave-enter" && it.officeId == office.id && it.transition == Transition.ENTER &&
                        it.at <= Instant.now() && it.at >= Instant.now().minusSeconds(30 * 60)
                } &&
                (initial.policy == dev.hamstercage.domain.Policy() ||
                    (initial.policy.targetMinutesPerDay == 20 && initial.policy.zoneId is ZoneOffset &&
                        initial.policy.expectedWeekdays.size == 1 && initial.policy.shortGapMinutes == 10 &&
                        initial.policy.maxOpenSessionHours == 16))) {
                "Synthetic process fixture requires untouched app attendance and policy"
            }
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            if (initial.policy == dev.hamstercage.domain.Policy()) {
                val zone = ZoneOffset.ofHours(12 - now.atZone(ZoneOffset.UTC).hour)
                val day = now.atZone(zone).dayOfWeek
                repository.savePolicy(PolicySettings(zoneId = zone, targetMinutesPerDay = 20, expectedWeekdays = setOf(day)))
            }
            if (initial.offices.isEmpty()) repository.saveOffice(office)
            if (initial.events.isEmpty()) repository.appendRawEvents(listOf(RecordedEvent(RawEvent("daily-leave-enter", office.id,
                Transition.ENTER, now.minusSeconds(5 * 60)), now)))
        }
        val snapshot = (repository.state.first { state ->
            state is StorageState.Ready && state.snapshot.events.size == 1 && state.snapshot.offices.size == 1 &&
                state.snapshot.policy.targetMinutesPerDay == 20
        } as StorageState.Ready).snapshot
        assertEquals(office, snapshot.offices.single())
        assertEquals("daily-leave-enter", snapshot.events.single().id)
        val now = Instant.now().plusSeconds(args.getString("evaluationOffsetSeconds", "0")!!.toLong())
        val input = snapshot.input(now)
        val derived = AttendanceEngine.derive(input)
        val estimate = AttendanceEngine.departure(input, derived, TargetWindow.TODAY)
        assertEquals(DepartureStatus.ESTIMATED, estimate.status)
        assertNotNull(estimate.estimatedExitAt)
        assertFalse(AttendanceEngine.daily(input, derived, now.atZone(input.policy.zoneId).toLocalDate()).hasCompleteHistory)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("fixturePid", Process.myPid().toString())
            putString("fixtureExitEpochSecond", estimate.estimatedExitAt!!.epochSecond.toString())
            putString("fixtureCreditedSeconds", (AttendanceEngine.daily(input, derived,
                now.atZone(input.policy.zoneId).toLocalDate()).creditedMinutes * 60).toLong().toString())
        })
    }
}
