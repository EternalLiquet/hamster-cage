package dev.hamstercage.ui

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaySaveOperationTest {
    @Test fun slowDocumentProviderDoesNotHoldCaptureGate() = runBlocking {
        val cache = Files.createTempDirectory("day-save-gate").toFile()
        val staged = DayShareFiles.create(cache, LocalDate.of(2026, 10, 14), "{\"reviewed\":true}")
        val gate = Mutex()
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val destination = ByteArrayOutputStream()
        try {
            val operation = async(Dispatchers.IO) {
                completeStagedDaySave(staged, gate, { true }, {
                    providerEntered.countDown()
                    check(providerRelease.await(10, TimeUnit.SECONDS))
                    destination
                }, {})
            }
            assertTrue("Provider did not open", providerEntered.await(10, TimeUnit.SECONDS))
            assertTrue("Geofence delivery must be able to take the gate", gate.tryLock())
            gate.unlock()
            providerRelease.countDown()
            assertEquals(DaySaveOutcome.SAVED, operation.await())
            assertEquals("{\"reviewed\":true}", destination.toString(Charsets.UTF_8.name()))
        } finally {
            providerRelease.countDown()
            cache.deleteRecursively()
        }
    }

    @Test fun providerFailuresNeverReportSavedAndRemoveCreatedDocument() = runBlocking {
        fun stagedFile() = Files.createTempDirectory("day-save-provider").toFile().let { cache ->
            cache to DayShareFiles.create(cache, LocalDate.of(2026, 10, 14), "{}")
        }
        val providers = listOf<() -> OutputStream?>(
            { null },
            { object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("write failed")
                override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("write failed")
            } },
            { object : ByteArrayOutputStream() {
                override fun close(): Unit = throw IOException("close failed")
            } },
        )
        providers.forEach { open ->
            val (cache, staged) = stagedFile()
            var deleted = false
            try {
                assertEquals(DaySaveOutcome.FAILED, completeStagedDaySave(staged, Mutex(),
                    { true }, open, { deleted = true }))
                assertTrue(deleted)
                assertFalse(staged.exists())
            } finally { cache.deleteRecursively() }
        }
    }

    @Test fun deletionDuringProviderOpenInvalidatesSaveBeforeWriting() = runBlocking {
        val cache = Files.createTempDirectory("day-save-privacy").toFile()
        val staged = DayShareFiles.create(cache, LocalDate.of(2026, 10, 14), "{\"private\":true}")
        val gate = Mutex()
        val current = AtomicBoolean(true)
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        val destination = ByteArrayOutputStream()
        var deleted = false
        try {
            val operation = async(Dispatchers.IO) {
                completeStagedDaySave(staged, gate, { current.get() }, {
                    providerEntered.countDown()
                    check(providerRelease.await(10, TimeUnit.SECONDS))
                    destination
                }, { deleted = true })
            }
            assertTrue("Provider did not open", providerEntered.await(10, TimeUnit.SECONDS))
            gate.lock()
            current.set(false)
            gate.unlock()
            providerRelease.countDown()
            assertEquals(DaySaveOutcome.CHANGED, operation.await())
            assertTrue(deleted)
            assertEquals(0, destination.size())
            assertFalse(staged.exists())
        } finally {
            providerRelease.countDown()
            cache.deleteRecursively()
        }
    }
}
