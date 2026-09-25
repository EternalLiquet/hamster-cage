package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class DayTimelineRouteTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val date = LocalDate.parse("2026-09-23")
    private val office = Office("west", "Westerville Office", 0.0, 0.0)
    private val policy = Policy(zoneId = ZoneId.of("UTC"))

    @Test fun dashboardAndHistoryOpenSameTimelineAndReturnToTheirOwnContext() {
        val events = listOf(
            RawEvent("in1", office.id, Transition.ENTER, Instant.parse("2026-09-23T09:47:00Z")),
            RawEvent("out1", office.id, Transition.EXIT, Instant.parse("2026-09-23T11:00:00Z")),
            RawEvent("in2", office.id, Transition.ENTER, Instant.parse("2026-09-23T12:14:00Z")))
        val snapshot = AppSnapshot(listOf(office), events.map { RecordedEvent(it, it.at) }, emptyList(), emptyList(), policy)
        compose.setContent { HamsterApp(TimeSource { now }, storageState = StorageState.Ready(snapshot), trackingReady = true,
            correctionActions = CorrectionActions({ now }, { "edit" }, { error("Navigation test never saves") })) }

        compose.onNodeWithTag("open_today_timeline").performScrollTo().performClick()
        compose.onNodeWithTag("detail_credit").performScrollTo().assertExists()
        compose.onNodeWithText("Arrived in office area · Westerville Office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No active recorded session", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Returned to office area · Westerville Office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ongoing at Westerville Office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail_leave").performScrollTo().assertTextContains("You can leave at", substring = true)
        compose.onNodeWithTag("timeline_edit_session:in2").performScrollTo().performClick()
        compose.onNodeWithText("Correct session").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel correction").performScrollTo().performClick()
        compose.onNodeWithText("Back to Dashboard").performScrollTo().performClick()
        compose.onNodeWithTag("today_credit").performScrollTo().assertExists()

        compose.onNodeWithText("History", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Explain $date").performScrollTo().performClick()
        compose.onNodeWithText("Arrived in office area · Westerville Office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Returned to office area · Westerville Office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail_credit").performScrollTo().assertExists()
        compose.onNodeWithText("Back to History").performScrollTo().performClick()
        compose.onNodeWithText("Explain $date").performScrollTo().assertIsDisplayed()
    }

    @Test fun emptyPretrackingTimelineDoesNotInventAbsenceOrOwedTime() {
        val snapshot = AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), policy)
        compose.setContent { HamsterApp(TimeSource { now }, storageState = StorageState.Ready(snapshot)) }
        compose.onNodeWithTag("open_today_timeline").performScrollTo().performClick()
        compose.onNodeWithTag("day_timeline_empty").performScrollTo()
            .assertTextContains("Tracking had not begun", substring = true)
        compose.onNodeWithTag("detail_required").assertDoesNotExist()
        compose.onNodeWithText("Back to Dashboard").performScrollTo().performClick()
        compose.onNodeWithTag("today_credit").performScrollTo().assertExists()
    }

    @Test fun returningFromOlderHistoryDetailKeepsTheBrowsePage() {
        val old = RawEvent("old", office.id, Transition.EXIT, now.minusSeconds(30L * 86_400))
        val snapshot = AppSnapshot(listOf(office), listOf(RecordedEvent(old, old.at)), emptyList(), emptyList(), policy)
        compose.setContent { HamsterApp(TimeSource { now }, storageState = StorageState.Ready(snapshot)) }
        compose.onNodeWithText("History", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Earlier 14 days").performScrollTo().performClick()
        compose.onNodeWithText("Newer 14 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_browse_before").performScrollTo().performClick()
        compose.onAllNodesWithText("Explain ", substring = true)[0].performScrollTo().performClick()
        compose.onNodeWithText("Back to History").performScrollTo().performClick()
        compose.onNodeWithText("Newer 14 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_before_explanation").performScrollTo().assertIsDisplayed()
    }

    @Test fun uncertainOutsideCheckUsesPlainReviewLanguageInTimeline() {
        val input = AttendanceInput(listOf(office), listOf(
            RawEvent("in", office.id, Transition.ENTER, Instant.parse("2026-09-23T09:00:00Z")),
            RawEvent("outside", office.id, Transition.ABSENCE, Instant.parse("2026-09-23T10:00:00Z"))),
            policy = policy, now = now)
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), date, back = {})
        } } }
        compose.onNodeWithText("Outside Westerville Office at a later check", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This session needs review.", substring = true)
            .performScrollTo().assertTextContains("exit time is unknown", substring = true)
        compose.onNodeWithText("unconfirmed gap", substring = true).assertDoesNotExist()
    }
}
