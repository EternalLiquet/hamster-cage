package dev.hamstercage.capture

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.HamsterDatabase
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Check facts must persist on devices whose `Instant.now()` has sub-millisecond precision. */
@RunWith(AndroidJUnit4::class)
class CaptureClockStorageTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixedNow = Instant.parse("2025-03-10T20:00:00Z")

    @Test fun checkFactsWithCaptureTimePersistWhileMicrosecondReceiptIsRejected() = runBlocking {
        val name = "clock-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val office = Office("clock-office", "Synthetic office", 0.0, 0.0)
        val observed = fixedNow.minusSeconds(5)
        val database = HamsterDatabase.open(context, "$name.db")
        try {
            val repository = HamsterRepository(database, preferences, TimeSource { fixedNow })
            repository.saveOffice(office)
            // The receipt time a microsecond-precision clock produced before the fix.
            val micro = fixedNow.plusNanos(1_116_000)
            var rejected = false
            try { repository.appendRawEvents(exitVerificationFacts(listOf(office.id), null, observed, micro, 10f,
                newId = { "micro" })) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Stored times stay whole milliseconds", rejected)
            val received = captureNow { micro.toEpochMilli() }
            val inserted = repository.appendRawEvents(exitVerificationFacts(listOf(office.id), null, observed,
                received, 10f, newId = { "milli" }))
            assertEquals(setOf("milli"), inserted)
        } finally {
            database.close()
            scope.cancel()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }
}
