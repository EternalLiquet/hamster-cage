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
        compose.onNodeWithText("Location check: inside · Synthetic office", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-time location check. The visit starts at this check; no earlier arrival time is guessed.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Entered office area · Synthetic office", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ENTER", substring = true).assertDoesNotExist()
    }
    @Test fun corroboratingPresenceDoesNotClaimASecondSessionStart() {
        val entered = RawEvent("in", office.id, Transition.ENTER, now.minusSeconds(3600))
        val fix = RawEvent("fix", office.id, Transition.PRESENCE, now.minusSeconds(600))
        val input = AttendanceInput(listOf(office), listOf(entered, fix), now = now)
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, {}, eventEvidence = listOf(
                RecordedEvent(entered, entered.at),
                RecordedEvent(fix, fix.at, fix.at, "BACKGROUND_LOCATION_RECONCILIATION")))
        } } }
        compose.onNodeWithText("One-time location check confirming the visit already recorded. It does not restart the arrival grace.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-time location check. The visit starts at this check; no earlier arrival time is guessed.")
            .assertDoesNotExist()
    }
    @Test fun adaptiveConfirmationIsExplainedAsCurrentLocationEvidence() {
        val entered = RawEvent("in", office.id, Transition.ENTER, now.minusSeconds(3600))
        val exit = RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(120))
        val fix = RawEvent("fix", office.id, Transition.PRESENCE, now.minusSeconds(60))
        val input = AttendanceInput(listOf(office), listOf(entered, exit, fix), now = now)
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayDetailScreen(input, AttendanceEngine.derive(input), day, {}, eventEvidence = listOf(
                RecordedEvent(fix, fix.at, fix.at, "ADAPTIVE_LOCATION_CONFIRMATION")))
        } } }
        compose.onNodeWithText("Location check: inside · Synthetic office", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One-time location check", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source event: fix").assertDoesNotExist()
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
        compose.onNodeWithText("Recorded span from arrival to a later location check (not confirmed continuous): 30m")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Counted span (not confirmed continuous): 30m.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phone-recorded time in office area: 30m").assertDoesNotExist()
        compose.onNodeWithText("Visit length: 30m.").assertDoesNotExist()
        compose.onAllNodesWithText("Phone-recorded time in office area: 10m")[0]
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirmed time before the gap may count. The uncertain time needs review; see Credited intervals for exact times.")
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
            .assertTextEquals("Confirmed time in office area: 0m")
        compose.onNodeWithText("Location check: outside · Synthetic office", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A one-time location check found you outside the office area. The exact time you left is unknown.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirmed time before the gap may count. The uncertain time needs review; see Credited intervals for exact times.")
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
        compose.onNodeWithTag("detail_observed").performScrollTo().assertTextEquals("Time your phone recorded: 1h 0m")
        compose.onNodeWithText("As recorded: 2026-09-23T14:00:00Z → 2026-09-23T15:00:00Z").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("As counted: 2026-09-23T14:30:00Z → 2026-09-23T15:00:00Z").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phone-recorded time in office area: 1h 0m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Visit length: 30m after correction.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Entered office area · Synthetic office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Left office area · Synthetic office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confidence: Manual. 2 location records kept.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source event: in").assertDoesNotExist()
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
        compose.onNodeWithText("Left office area · Synthetic office", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exit without an arrival").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MISSING ENTER").assertDoesNotExist()
        compose.onNodeWithText("Source event: exit").assertDoesNotExist()
        compose.onNodeWithTag("detail_before_tracking").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail_required").assertDoesNotExist()
    }
}
