package dev.hamstercage.data

import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class HistorySecurityIntegrationTest {
    @Test @SdkSuppress(minSdkVersion = 34)
    fun maliciousEditsAreAtomicAndActualHistoryBytesStayPrivate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "history-security-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val database = HamsterDatabase.open(context, "$name.db")
        val now = Instant.parse("2026-09-23T16:00:00Z")
        val repository = HamsterRepository(database, preferences, TimeSource { now })
        suspend fun snapshot() = (repository.state.first() as StorageState.Ready).snapshot
        suspend fun rejected(block: suspend () -> Unit) {
            val before = snapshot()
            try { block(); fail("Malicious mutation must fail") } catch (_: IllegalArgumentException) { }
            assertEquals(before, snapshot())
        }
        try {
            val office = Office("synthetic", "Synthetic history", 0.125, -0.25)
            val enter = RawEvent("enter", office.id, Transition.ENTER, now.minusSeconds(7200))
            val exit = RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))
            repository.saveOffice(office)
            repository.appendRawEvents(listOf(enter, exit).map { RecordedEvent(it, it.at) })
            val note = "HISTORY_PRIVATE_SENTINEL_28 '); DROP TABLE raw_events;--\n<script>literal</script>\u202e"
            val good = Correction("edit", "session:enter", enter.at, exit.at, now, note, appendSequence = 1)
            for (invalid in listOf(good.copy(sessionId = "session:absent"), good.copy(note = "x".repeat(2001)),
                good.copy(id = " "), good.copy(appendSequence = -1), good.copy(appendSequence = 2),
                good.copy(start = enter.at.plusNanos(1)), good.copy(end = enter.at), good.copy(createdAt = now.plusSeconds(1)))) {
                rejected { repository.appendCorrection(invalid) }
            }
            repository.appendCorrection(good)
            repository.appendCorrection(good) // Exact retry is idempotent.
            rejected { repository.appendCorrection(good.copy(note = "rewrite")) }
            val manual = ManualSession("manual", office.id, enter.at, exit.at, now, note)
            rejected { repository.appendManualSession(manual.copy(officeId = "absent")) }
            rejected { repository.appendManualSession(manual.copy(note = "x".repeat(2001))) }
            rejected { repository.appendManualSession(manual.copy(end = now.plusSeconds(1))) }
            repository.appendManualSession(manual)
            rejected { repository.appendManualSession(manual.copy(note = "rewrite")) }
            val after = snapshot()
            assertEquals(listOf(good), after.corrections)
            assertEquals(listOf(manual), after.manualSessions)
            assertEquals(listOf(enter, exit), after.events)
            assertEquals(note, after.corrections.single().note) // Bound SQL treats input as literal data.
            val descriptors = instrumentation.uiAutomation.executeShellCommandRwe("cat ${context.getDatabasePath("$name.db").absolutePath}")
            descriptors[1].close()
            val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
            val error = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
            assertTrue(output.isEmpty()); assertTrue(error.contains("Permission denied"))
        } finally {
            database.close(); scope.cancel(); scope.coroutineContext[Job]?.join()
            context.deleteDatabase("$name.db"); context.preferencesDataStoreFile(name).delete()
        }
    }
}
