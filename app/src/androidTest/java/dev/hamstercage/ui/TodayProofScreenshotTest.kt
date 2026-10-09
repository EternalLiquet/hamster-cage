package dev.hamstercage.ui

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.hamstercage.domain.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/** Temporary proof-only capture of the Today panel for synthetic states; not part of the PR. */
class TodayProofScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun data(events: List<RawEvent>) = AttendanceInput(listOf(office), events, now = now,
        historyStartDate = LocalDate.of(2026, 6, 1))
    private fun enter(at: Instant) = RawEvent("in", "a", Transition.ENTER, at)

    private fun capture(name: String, input: AttendanceInput, ready: Boolean, fontScale: Float) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.5f, fontScale)) {
                HamsterTheme { Column(Modifier.width(360.dp)) { DashboardScreen(input, AttendanceEngine.derive(input), ready) } }
            }
        } }
        compose.waitForIdle()
        val full = compose.onRoot().captureToImage().asAndroidBitmap()
        val cropped = Bitmap.createBitmap(full, 0, 0, minOf(full.width, 540), full.height)
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 55, out)
        val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val chunks = text.chunked(3000)
        chunks.forEachIndexed { i, chunk -> Log.i("TODAYPROOF", "$name|$i|${chunks.size}|$chunk") }
    }

    @Test fun captureTodayStates() {
        capture("1-confirmed", data(listOf(enter(now.minusSeconds(3 * 3600)))), true, 1f)
        capture("2-untracked", data(listOf(enter(now.minusSeconds(3 * 3600)))).copy(historyStartDate = null), true, 1f)
        capture("3-unconfirmed", data(listOf(enter(now.minusSeconds(3 * 3600)))), false, 1f)
        capture("4-arriving", data(listOf(enter(now.minusSeconds(180)))), true, 1f)
        capture("5-untracked-200pct", data(listOf(enter(now.minusSeconds(3 * 3600)))).copy(historyStartDate = null), true, 2f)
    }
}
