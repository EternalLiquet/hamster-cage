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
import dev.hamstercage.data.RecordedEvent
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
    @Test fun foregroundPresenceIsAuditedAsCurrentFixWithoutClaimingPhysicalEntry() {
        val raw = RawEvent("fix", office.id, Transition.PRESENCE, now.minusSeconds(600))
        val input = AttendanceInput(listOf(office), listOf(raw), now = now)
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, {},
                eventEvidence = listOf(RecordedEvent(raw, raw.at, raw.at, "FOREGROUND_LOCATION_RECONCILIATION")))
        } } }
        compose.onNodeWithText("Current-location presence · Synthetic office", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-shot precise fix. The session starts at this observation, not at a guessed arrival.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ENTER · Synthetic office", substring = true).assertDoesNotExist()
    }
    @Test fun crossOfficeGapShowsElapsedSpanWithoutClaimingContinuousInZoneTime() {
        val evaluated = Instant.parse("2026-09-23T14:40:00Z")
        val second = office.copy(id = "second", name = "Second office")
        val a = RawEvent("a-in", office.id, Transition.ENTER, Instant.parse("2026-09-23T14:00:00Z"))
        val bIn = RawEvent("b-in", second.id, Transition.ENTER, Instant.parse("2026-09-23T14:10:00Z"))
        val bOut = RawEvent("b-out", second.id, Transition.EXIT, Instant.parse("2026-09-23T14:20:00Z"))
        val fix = RawEvent("a-fix", office.id, Transition.PRESENCE, Instant.parse("2026-09-23T14:30:00Z"))
        val input = AttendanceInput(listOf(office, second), listOf(a, bIn, bOut, fix),
            policy = Policy(zoneId = ZoneId.of("UTC")), now = evaluated,
            recoveryPresenceIds = setOf("a-fix"))
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, {})
        } } }
        compose.onNodeWithText("Original elapsed span between opening and later current-location check (continuity unconfirmed): 30m")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Effective elapsed span (continuity unconfirmed): 30m.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Device-observed in-zone time: 30m").assertDoesNotExist()
        compose.onNodeWithText("Effective session duration: 30m.").assertDoesNotExist()
        compose.onAllNodesWithText("Device-observed in-zone time: 10m")[0]
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The listed end is a later current-location check, not an observed EXIT. The exit time is unknown, and this earlier segment earns no credit until corrected.")
            .performScrollTo().assertIsDisplayed()
    }
    @Test fun missedExitOutsideCheckExplainsUnknownTimeAndZeroCredit() {
        val entered = RawEvent("enter", office.id, Transition.ENTER, now.minusSeconds(3600))
        val checked = RawEvent("outside", office.id, Transition.ABSENCE, now.minusSeconds(60))
        val input = AttendanceInput(listOf(office), listOf(entered, checked),
            policy = Policy(zoneId = ZoneId.of("UTC")), now = now)
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, {}, eventEvidence = listOf(
                RecordedEvent(entered, entered.at),
                RecordedEvent(checked, checked.at, checked.at, "BACKGROUND_LOCATION_RECONCILIATION")))
        } } }
        compose.onNodeWithTag("detail_credit").performScrollTo().assertTextEquals("Recorded credit: 0m")
        compose.onNodeWithTag("detail_observed").performScrollTo()
            .assertTextEquals("Confirmed in-zone time: 0m")
        compose.onNodeWithText("Current-location outside · Synthetic office", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-shot precise fix establishes outside at this check. The exit time is unknown; the old span earns no credit until reviewed.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The listed end is a later current-location check, not an observed EXIT. The exit time is unknown, and this earlier segment earns no credit until corrected.")
            .performScrollTo().assertIsDisplayed()
    }
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
        compose.onNodeWithTag("detail_before_tracking").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail_required").assertDoesNotExist()
    }
}
