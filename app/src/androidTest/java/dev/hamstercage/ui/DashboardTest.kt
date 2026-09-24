package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.hamstercage.MainActivity
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val today = LocalDate.of(2026, 9, 23)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun data(events: List<RawEvent> = emptyList()) = AttendanceInput(listOf(office), events,
        now = now, historyStartDate = today.minusDays(100))
    private fun enter() = RawEvent("in", "a", Transition.ENTER, now.minusSeconds(3 * 3600))
    private fun show(input: AttendanceInput, ready: Boolean = true, scale: Float = 1f, openOffices: () -> Unit = {}) {
        val density = compose.activity.resources.displayMetrics.density
        compose.activity.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    DashboardScreen(input, AttendanceEngine.derive(input), ready, openOffices)
                } }
            }
        } }
    }

    @Test fun emptyUnknownStateLinksToOfficeSetupWithoutClaimingAZeroBalance() {
        var opened = false
        show(data().copy(offices = emptyList(), historyStartDate = null), ready = false, openOffices = { opened = true })
        compose.onNodeWithTag("office_state").assertTextEquals("Office state unknown")
        compose.onNodeWithTag("today_balance").assertTextContains("Unknown")
        compose.onNodeWithText("Provisional credit · coverage incomplete").assertIsDisplayed()
        compose.onNodeWithText("Open office setup").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test fun noOfficeDashboardActionReachesTheIntegratedOfficeForm() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val actions = OfficeActions({ "synthetic-new" }, { null }, { _, _ -> error("This navigation check never saves") })
        compose.activity.runOnUiThread { compose.activity.setContent {
            HamsterApp(TimeSource { now }, storageState = StorageState.Ready(snapshot), officeActions = actions)
        } }
        compose.onNodeWithText("Open office setup").performScrollTo().performClick()
        compose.onNode(hasText("Add office") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("officeName").performScrollTo().assertIsDisplayed()
    }

    @Test fun activeDashboardSeparatesObservedCreditAndEachWeekRequirement() {
        show(data(listOf(enter())))
        compose.onNodeWithTag("office_state").assertTextEquals("In Synthetic office")
        compose.onNodeWithTag("today_credit").assertTextEquals("2h 55m")
        compose.onNodeWithTag("today_observed").assertTextContains("3h 0m")
        compose.onNodeWithTag("arrival_credit_start").assertTextContains("Credit starts at 9:05 AM", substring = true)
        compose.onNodeWithTag("WEEK_TO_DATE_required").performScrollTo().assertTextContains("18h 0m")
        compose.onNodeWithTag("full_week_required").performScrollTo().assertTextContains("30h 0m")
        compose.onNodeWithTag("TODAY_departure").performScrollTo().assertTextContains("About 3:00 PM")
    }

    @Test fun activeArrivalWindowShowsZeroCreditAndCountdown() {
        val entry = Instant.parse("2026-09-23T13:00:00Z")
        val input = data(listOf(RawEvent("in", "a", Transition.ENTER, entry))).copy(now = entry.plusSeconds(180))
        show(input)
        compose.onNodeWithTag("today_observed").assertTextContains("3m")
        compose.onNodeWithTag("today_credit").assertTextEquals("0m")
        compose.onNodeWithTag("arrival_credit_start").assertTextContains("Credit starts at 9:05 AM", substring = true)
        compose.onNodeWithTag("arrival_countdown").assertTextContains("2m until credit starts", substring = true)
    }

    @Test fun trackingLossSuppressesAnOtherwiseAvailableDepartureEstimate() {
        show(data(listOf(enter())), ready = false)
        compose.onNodeWithTag("office_state").assertTextEquals("Office state unknown")
        compose.onNodeWithTag("TODAY_departure").performScrollTo().assertTextContains("Detection unconfirmed")
    }

    @Test fun outsideAmbiguousHolidaySatisfiedAndDeficitFixturesStayExplicit() {
        val fixture = mutableStateOf(data())
        compose.activity.runOnUiThread { compose.activity.setContent { HamsterTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { DashboardScreen(fixture.value, AttendanceEngine.derive(fixture.value), true) }
        } } }
        fun update(input: AttendanceInput) { compose.runOnIdle { fixture.value = input } }
        update(data(listOf(enter(), RawEvent("out", "a", Transition.EXIT, now.minusSeconds(60)))))
        compose.onNodeWithTag("office_state").assertTextEquals("Outside office")
        compose.onNodeWithTag("today_balance").assertTextContains("−3h 6m")
        update(data(listOf(RawEvent("missing", "a", Transition.EXIT, now))))
        compose.onNodeWithTag("office_state").assertTextEquals("Needs review")
        update(data().copy(policy = Policy(excludedDates = listOf(ExcludedDate(today, ExclusionReason.BANK_HOLIDAY)))))
        compose.onNodeWithTag("today_target").assertTextContains("0m")
        compose.onNodeWithTag("TODAY_departure").performScrollTo().assertTextContains("Already satisfied")
        update(data(listOf(enter().copy(at = now.minusSeconds(7 * 3600 + 5 * 60)), RawEvent("out", "a", Transition.EXIT, now.minusSeconds(3600)))))
        compose.onNodeWithTag("TODAY_departure").assertTextContains("Already satisfied")
    }

    @Test fun twoHundredPercentTextRetainsCreditAndTrendLabelsWithoutOverflow() {
        show(data(listOf(enter())), scale = 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("today_credit").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.joinToString { "size=${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}, lines=${it.lineCount}" }, layouts.none { it.hasVisualOverflow })
        compose.onNodeWithTag("ROLLING_90_average").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ROLLING_90_days").performScrollTo().assertTextContains("Expected workdays")
    }
}
