package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DaySaveUiTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val day = LocalDate.of(2026, 9, 23)
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    private val input = AttendanceInput(listOf(office), emptyList(), now = now)

    private fun show(saveDay: ((DayDiagnostics) -> Unit)?, shareDay: (DayDiagnostics) -> Unit,
        prepared: () -> DayDiagnostics, notice: String? = null, error: String? = null) {
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, {}, prepareDayShare = prepared,
                shareDay = shareDay, saveDay = saveDay, shareNotice = notice, shareError = error)
        } } }
    }

    @Test fun saveAndShareReceiveTheSameReviewedSnapshot() {
        var preparations = 0
        val shared = mutableListOf<DayDiagnostics>()
        val saved = mutableListOf<DayDiagnostics>()
        show(saveDay = { saved += it }, shareDay = { shared += it }, prepared = {
            preparations++
            DayDiagnostics("{\"schemaVersion\":1,\"draft\":$preparations}", 1, 0, day, day)
        })
        compose.onNodeWithTag("share_day_data").performScrollTo().performClick()
        compose.onNodeWithText("save a copy to a location you choose", substring = true).assertIsDisplayed()
        compose.onNodeWithText("deleting history here won't remove it", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("save_day_data").performClick()
        compose.waitForIdle()
        // Saving uses the previewed draft; it does not rebuild the export after review.
        assertEquals(1, preparations)
        assertEquals(listOf("{\"schemaVersion\":1,\"draft\":1}"), saved.map { it.json })
        assertTrue(shared.isEmpty())
        compose.onNodeWithTag("save_day_data").assertDoesNotExist()

        compose.onNodeWithTag("share_day_data").performScrollTo().performClick()
        compose.onNodeWithTag("share_day_choose_app").performClick()
        compose.waitForIdle()
        assertEquals(2, preparations)
        assertEquals(listOf("{\"schemaVersion\":1,\"draft\":2}"), shared.map { it.json })
        assertEquals(1, saved.size)
    }

    @Test fun cancelWritesNothingAndShareOnlyHostsHideSave() {
        val calls = mutableListOf<String>()
        show(saveDay = null, shareDay = { calls += "share" }, prepared = {
            DayDiagnostics("{}", 0, 0, day, day)
        })
        compose.onNodeWithTag("share_day_data").performScrollTo().performClick()
        compose.onNodeWithTag("save_day_data").assertDoesNotExist()
        compose.onNodeWithText("Choose where to send it next.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun saveOutcomeIsShownOnlyAsReported() {
        show(saveDay = {}, shareDay = {}, prepared = { DayDiagnostics("{}", 0, 0, day, day) },
            notice = "Saved. This copy is outside Hamster Cage, so deleting history here won't remove it.")
        compose.onNodeWithTag("share_day_notice").performScrollTo().assertIsDisplayed()
            .assertTextContains("Saved.", substring = true)
    }
}
