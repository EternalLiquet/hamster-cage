package dev.hamstercage

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaySaveLifecycleTest {
    private val day = LocalDate.of(2026, 10, 14)

    @Test fun savedPickerStateCannotResurrectDraftDuringRetainedWrite() = runBlocking {
        val cache = Files.createTempDirectory("day-save-rotation").toFile()
        val staged = DayShareFiles.create(cache, day, "{\"reviewed\":true}")
        val model = DaySaveViewModel()
        val saved = Bundle()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            model.stageForPicker(staged, 0, "day.json")
            model.launchPicker { }
            model.save(saved)
            val write = async(Dispatchers.IO) {
                model.finishPendingSave(Mutex(), { true }, {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    ByteArrayOutputStream()
                }, {})
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            model.restore(saved) // MainActivity.onCreate on the retained ViewModel after rotation.
            assertNull(model.pendingSave)
            release.countDown()
            write.await()
            assertNull(model.pendingSave)
            val retry = DayShareFiles.create(cache, day, "{}")
            model.stageForPicker(retry, 0, "retry.json")
            var launched: String? = null
            model.launchPicker { launched = it }
            assertEquals("retry.json", launched)
        } finally {
            release.countDown()
            cache.deleteRecursively()
        }
    }

    @Test fun processRecreationBeforePickerLaunchRestoresLaunchRequest() {
        val cache = Files.createTempDirectory("day-save-picker-restart").toFile()
        try {
            val staged = DayShareFiles.create(cache, day, "{\"reviewed\":true}")
            val first = DaySaveViewModel()
            first.stageForPicker(staged, 0, "hamster-day-2026-10-14.json")
            val saved = Bundle().also(first::save)

            val recreated = DaySaveViewModel()
            recreated.restore(saved)
            var launched: String? = null
            recreated.launchPicker { launched = it }
            assertEquals("hamster-day-2026-10-14.json", launched)
            assertEquals(staged.absolutePath, recreated.pendingSave?.path)
        } finally { cache.deleteRecursively() }
    }
}
