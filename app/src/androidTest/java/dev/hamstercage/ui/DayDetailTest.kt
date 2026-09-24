package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class DayDetailTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val day = LocalDate.of(2026, 9, 23)
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    @Test fun historyNavigationShowsCorrectedTotalAndRetainedRawEvidenceAtLargeText() {
        val input = AttendanceInput(listOf(office), listOf(
            RawEvent("in", office.id, Transition.ENTER, now.minusSeconds(7200)),
            RawEvent("out", office.id, Transition.EXIT, now.minusSeconds(3600))),
            corrections = listOf(Correction("edit", "session:in", now.minusSeconds(5400), now.minusSeconds(3600), now, "Synthetic correction")),
            policy = Policy(zoneId = ZoneId.of("UTC")), now = now)
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
            HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { HistoryScreen(input, AttendanceEngine.derive(input)) } }
        } }
        compose.onNodeWithTag("history_credit_$day").performScrollTo().assertTextContains("25m")
        compose.onNodeWithText("Explain $day").performScrollTo().performClick()
        compose.onNodeWithTag("detail_credit").performScrollTo().assertTextEquals("Recorded credit: 25m")
        compose.onNodeWithTag("detail_observed").performScrollTo().assertTextEquals("Device-observed time: 1h 0m")
        compose.onNodeWithText("Original bounds: 2026-09-23T14:00:00Z → 2026-09-23T15:00:00Z").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Effective bounds: 2026-09-23T14:30:00Z → 2026-09-23T15:00:00Z").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Device-observed in-zone time: 1h 0m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Effective session duration: 30m after correction.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source event: in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source event: out").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic correction").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to daily history").performScrollTo().performClick()
        compose.onNodeWithTag("history_credit_$day").performScrollTo().assertTextContains("25m")
    }

    @Test fun missingBoundaryReviewExplainsWhyNoCreditWasInvented() {
        val input = AttendanceInput(listOf(office), listOf(RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))), now = now)
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, back = {})
        } } }
        compose.onNodeWithTag("detail_credit").assertTextEquals("Recorded credit: 0m")
        compose.onAllNodesWithText(reviewExplanation(ReviewReason.MISSING_ENTER))[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source event: exit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unknown coverage").performScrollTo().assertIsDisplayed()
    }
}
