package dev.hamstercage.ui

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistorySecurityUiTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val office = Office("synthetic", "Synthetic history", 0.125, -0.25)
    @Test fun auditIdentifiersCannotSpoofLayoutAndCoordinatesAreAbsent() {
        val raw = RawEvent("raw\u202e\n-id", office.id, Transition.EXIT, now.minusSeconds(3600))
        val input = AttendanceInput(listOf(office), listOf(raw), now = now)
        compose.setContent { HamsterTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), now.atZone(input.policy.zoneId).toLocalDate(), {})
        } } } }
        compose.onNodeWithText("EXIT · Synthetic history", substring = true).performScrollTo().assertIsDisplayed()
        listOf("raw", "Source event:", "\u202e", "0.125", "-0.25").forEach { compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertDoesNotExist() }
        if (InstrumentationRegistry.getArguments().getString("historySecurityScreenshot") == "true") {
            File(compose.activity.cacheDir, "history-security-28.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertEquals("raw\u202e\n-id", input.events.single().id)
    }
    @Test fun exhaustedOrderFailsSafelyAndCancelNeverCommits() {
        val event = RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))
        val correction = Correction("existing", "session:exit", now.minusSeconds(7200), event.at, now, appendSequence = Long.MAX_VALUE)
        val input = AttendanceInput(listOf(office), listOf(event), corrections = listOf(correction), now = now)
        var saves = 0; var cancelled = false
        compose.setContent { HamsterTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            CorrectionEditor(input, AttendanceEngine.derive(input).sessions.single(), CorrectionActions({ now }, { "new" }, { saves++ })) { cancelled = true }
        } } } }
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Error: Correction order is unavailable. Your existing facts were kept.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm attendance change").assertDoesNotExist()
        compose.onNodeWithText("Cancel correction").performScrollTo().performClick()
        assertTrue(cancelled); assertEquals(0, saves)
        assertEquals(listOf(event), input.events)
        assertEquals(listOf(correction), input.corrections)
    }
}
