package dev.hamstercage.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class CorrectionIntegrationTest {
    @Test fun concurrentPolicySaveCannotEnterDataStoreBetweenPreviewCheckAndAppend() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "correction-race-${UUID.randomUUID()}"
        val now = Instant.parse("2026-09-23T16:00:00Z")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val underlying = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val watchWrites = AtomicBoolean(false)
        val policyWriteEntered = AtomicBoolean(false)
        val preferences = object : DataStore<Preferences> {
            override val data = underlying.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (watchWrites.get()) policyWriteEntered.set(true)
                return underlying.updateData(transform)
            }
        }
        val reachedAppend = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val blockNextClock = AtomicBoolean(false)
        val database = HamsterDatabase.open(context, "$name.db")
        val repository = HamsterRepository(database, preferences, TimeSource {
            if (blockNextClock.compareAndSet(true, false)) {
                reachedAppend.countDown()
                check(releaseAppend.await(10, TimeUnit.SECONDS))
            }
            now
        })
        try {
            val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
            repository.saveOffice(office)
            val event = RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))
            repository.appendRawEvents(listOf(RecordedEvent(event, event.at)))
            val baseline = (repository.state.first() as StorageState.Ready).snapshot.input(now)
            val edit = AttendanceEdit.Correct(baseline, Correction("edit", "session:exit", now.minusSeconds(7200), event.at, now, appendSequence = 1))
            blockNextClock.set(true)
            val commit = async(Dispatchers.IO) { repository.commitAttendanceEdit(edit) }
            assertTrue(reachedAppend.await(10, TimeUnit.SECONDS))
            watchWrites.set(true)
            // UNDISPATCHED runs until its first suspension. The shared policy/edit mutex
            // must suspend before updateData is entered, without scheduler/timing guesses.
            val policySave = async(start = CoroutineStart.UNDISPATCHED) {
                repository.savePolicy(PolicySettings(targetMinutesPerDay = 480))
            }
            assertFalse("Policy must wait until confirmed append completes", policyWriteEntered.get())
            assertFalse(policySave.isCompleted)
            releaseAppend.countDown()
            commit.await(); policySave.await()
            assertTrue(policyWriteEntered.get())
            val after = (repository.state.first() as StorageState.Ready).snapshot
            assertEquals(480, after.policy.targetMinutesPerDay)
            assertEquals(listOf(edit.value), after.corrections)
            assertEquals(listOf(event), after.events)
        } finally {
            releaseAppend.countDown(); database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            context.deleteDatabase("$name.db"); context.preferencesDataStoreFile(name).delete()
        }
    }

    @Test fun previewCommitReopenAndRevertKeepFactsWhileRejectingStaleOrInvalidEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "correction-${UUID.randomUUID()}"
        var now = Instant.parse("2026-09-23T16:00:00Z")
        var database = HamsterDatabase.open(context, "$name.db")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        var repository = HamsterRepository(database, preferences, TimeSource { now })
        suspend fun snapshot() = (withTimeout(10_000) { repository.state.first() } as StorageState.Ready).snapshot
        try {
            val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
            val raw = RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))
            repository.saveOffice(office); repository.appendRawEvents(listOf(RecordedEvent(raw, raw.at)))
            val baseline = snapshot().input(now)
            val original = AttendanceEngine.derive(baseline)
            val correction = Correction("edit", "session:exit", now.minusSeconds(7200), raw.at, now, "Synthetic fix", appendSequence = 1)
            val proposal = AttendanceEdit.Correct(baseline, correction)
            val preview = AttendanceEngine.derive(proposal.proposedInput())
            repository.commitAttendanceEdit(proposal)
            assertEquals(preview, snapshot().derive(now))
            assertEquals(Confidence.MANUAL, preview.sessions.single().confidence)
            assertEquals(listOf(raw), snapshot().events)
            var staleRejected = false
            try { repository.commitAttendanceEdit(proposal.copy(value = correction.copy(id = "stale"))) }
            catch (_: IllegalStateException) { staleRejected = true }
            assertTrue(staleRejected)
            val beforeInvalid = snapshot()
            for (invalid in listOf(correction.copy(id = "reverse", start = raw.at, end = raw.at.minusSeconds(1)),
                correction.copy(id = "future", end = now.plusSeconds(60)))) {
                var rejected = false
                try { repository.appendCorrection(invalid) } catch (_: IllegalArgumentException) { rejected = true }
                assertTrue(rejected); assertEquals(beforeInvalid, snapshot())
            }
            database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
            database = HamsterDatabase.open(context, "$name.db")
            repository = HamsterRepository(database, preferences, TimeSource { now })
            assertEquals(preview, snapshot().derive(now))
            now = now.plusSeconds(1)
            val current = snapshot().input(now)
            val marker = correction.copy(id = "revert", start = raw.at, createdAt = now, revertToOriginal = true, appendSequence = 2)
            val revert = AttendanceEdit.Correct(current, marker)
            repository.commitAttendanceEdit(revert)
            val restored = snapshot().derive(now)
            assertEquals(AttendanceEngine.derive(revert.proposedInput()), restored)
            assertEquals(original.intervals, restored.intervals)
            assertEquals(original.reviews, restored.reviews)
            assertNull(restored.sessions.single().start)
            assertTrue(restored.sessions.single().correctionReverted)
            assertEquals(listOf(correction, marker), snapshot().corrections)
            assertEquals(listOf(raw), snapshot().events)
            now = now.plusSeconds(1)
            val manual = ManualSession("manual", office.id, now.minusSeconds(3600), now.minusSeconds(1800), now)
            val add = AttendanceEdit.AddManual(snapshot().input(now), manual)
            repository.commitAttendanceEdit(add)
            assertEquals(AttendanceEngine.derive(add.proposedInput()), snapshot().derive(now))
            assertEquals(listOf(raw), snapshot().events) // Manual attendance is never a raw ENTER/EXIT.
        } finally {
            database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            context.deleteDatabase("$name.db"); context.preferencesDataStoreFile(name).delete()
        }
    }
}
