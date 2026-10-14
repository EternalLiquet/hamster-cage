package dev.hamstercage

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.hamstercage.ui.DayShareFiles
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DaySaveViewModelTest {
    @Test fun deniedPickerLaunchClearsDraftAndAllowsRetry() {
        val cache = Files.createTempDirectory("day-picker-denied").toFile()
        val model = DaySaveViewModel()
        try {
            val first = DayShareFiles.create(cache, LocalDate.of(2026, 10, 14), "{}")
            model.stageForPicker(first, 0, "first.json")
            model.launchPicker { throw SecurityException("provider denied") }
            assertFalse(first.exists())
            assertNull(model.pendingSave)
            assertTrue(model.shareError.orEmpty().contains("Nothing was saved"))

            val retry = DayShareFiles.create(cache, LocalDate.of(2026, 10, 14), "{}")
            model.stageForPicker(retry, 0, "retry.json")
            var launched = false
            model.launchPicker { launched = true }
            assertTrue(launched)
            assertEquals(retry.absolutePath, model.pendingSave?.path)
        } finally { cache.deleteRecursively() }
    }

    @Test fun retainedModelKeepsWriteAndResultAcrossActivityRecreation() = runBlocking {
        val cache = Files.createTempDirectory("day-save-recreate").toFile()
        val store = ViewModelStore()
        val factory = ViewModelProvider.NewInstanceFactory()
        val model = ViewModelProvider(store, factory)[DaySaveViewModel::class.java]
        val staged = DayShareFiles.create(cache, LocalDate.of(2026, 10, 14), "{\"reviewed\":true}")
        val providerEntered = CountDownLatch(1)
        val providerRelease = CountDownLatch(1)
        try {
            model.stageForPicker(staged, 0, "day.json")
            model.launchPicker { }
            val write = async(Dispatchers.IO) {
                model.finishPendingSave(Mutex(), { true }, {
                    providerEntered.countDown()
                    check(providerRelease.await(10, TimeUnit.SECONDS))
                    ByteArrayOutputStream()
                }, {})
            }
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS))
            val recreated = ViewModelProvider(store, factory)[DaySaveViewModel::class.java]
            assertSame(model, recreated)
            assertTrue(recreated.saveInFlight)
            providerRelease.countDown()
            write.await()
            assertFalse(recreated.saveInFlight)
            assertTrue(recreated.shareNotice.orEmpty().startsWith("Saved."))
        } finally {
            providerRelease.countDown()
            store.clear()
            cache.deleteRecursively()
        }
    }
}
