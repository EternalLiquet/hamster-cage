package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
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

    /** Secondary detail is collapsed by default (#119); these open it without changing any asserted value. */
    private fun expandTargets() = compose.onNodeWithTag("departure_targets_toggle").performScrollTo().performClick()
    private fun expandPeriods() = compose.onNodeWithTag("periods_toggle").performScrollTo().performClick()

    @Test fun emptyUnknownStateLinksToOfficeSetupWithoutClaimingAZeroBalance() {
        var opened = false
        show(data().copy(offices = emptyList(), historyStartDate = null), ready = false, openOffices = { opened = true })
        compose.onNodeWithTag("office_state").assertTextEquals("Office state unknown")
        compose.onNodeWithTag("today_balance").assertTextContains("Unknown")
        compose.onNodeWithTag("today_credit_note").assertTextEquals("Estimate · part of today wasn't tracked")
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
        compose.onNodeWithTag("today_credit_note").assertTextEquals("Counted toward today's goal")
        compose.onNodeWithTag("today_balance").assertTextContains("3h 5m")
        compose.onNodeWithTag("arrival_credit_start").assertTextEquals("Counting since 9:05 AM.")
        // Technical detail is one tap away, not in the default view.
        compose.onNodeWithTag("today_observed").assertDoesNotExist()
        compose.onNodeWithTag("today_details_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("today_observed").performScrollTo().assertTextContains("3h 0m")
        compose.onNodeWithTag("WEEK_TO_DATE_required").assertDoesNotExist()
        expandPeriods()
        compose.onNodeWithTag("WEEK_TO_DATE_required").performScrollTo().assertTextContains("18h 0m")
        compose.onNodeWithTag("full_week_required").performScrollTo().assertTextContains("30h 0m")
        compose.onNodeWithTag("TODAY_departure").assertDoesNotExist()
        expandTargets()
        compose.onNodeWithTag("TODAY_departure").performScrollTo().assertTextContains("About 3:00 PM")
    }

    @Test fun confirmedDayGivesAPlainLeaveTimeWithDetailOnRequest() {
        show(data(listOf(enter())))
        compose.onNodeWithTag("today_leave").assertTextEquals("You can leave at 3:00 PM")
        compose.onNodeWithTag("today_leave_boundary").assertDoesNotExist()
        compose.onNodeWithTag("today_details_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("today_leave_boundary").performScrollTo().assertTextContains("office area", substring = true)
    }

    @Test fun freshInstallShowsTheDailyLeaveTimeOnlyAsAnEstimateWhileHistoryIsUnknown() {
        show(data(listOf(enter())).copy(historyStartDate = null))
        compose.onNodeWithTag("today_leave").assertTextEquals("Estimated leave time: about 3:00 PM. Check today's timeline.")
        compose.onNodeWithTag("today_credit_note").assertTextEquals("Estimate · part of today wasn't tracked")
        compose.onNodeWithTag("today_balance").assertTextContains("Unknown")
        compose.onNodeWithTag("today_untracked").assertTextContains("can't tell whether you were at the office", substring = true)
        expandPeriods()
        compose.onNodeWithTag("ROLLING_30_balance").performScrollTo().assertTextContains("Unknown")
        compose.onNodeWithTag("ROLLING_90_balance").performScrollTo().assertTextContains("Unknown")
        // Expanded unknown longer-term history leaves the valid Today estimate in place.
        compose.onNodeWithTag("today_leave").performScrollTo()
            .assertTextEquals("Estimated leave time: about 3:00 PM. Check today's timeline.")
    }

    @Test fun noHistoryRollingCardsExplainUnavailableDaysWithoutARequiredTotalAtLargeText() {
        show(data().copy(historyStartDate = null), scale = 2f)
        expandPeriods()
        listOf("ROLLING_30", "ROLLING_90").forEach { target ->
            compose.onNodeWithTag("${target}_coverage").performScrollTo()
                .assertTextContains("Earlier days predate reliable tracking", substring = true)
            compose.onNodeWithTag("${target}_required").assertDoesNotExist()
            compose.onNodeWithTag("${target}_days").assertDoesNotExist()
            compose.onNodeWithTag("${target}_balance").performScrollTo().assertTextContains("Unknown")
        }
    }

    @Test fun firstTrackedDayRollingRequirementExcludesPriorDays() {
        show(data(listOf(enter())).copy(historyStartDate = null))
        expandPeriods()
        listOf("ROLLING_30", "ROLLING_90").forEach { target ->
            compose.onNodeWithTag("${target}_days").performScrollTo().assertTextContains("1")
            compose.onNodeWithTag("${target}_required").performScrollTo().assertTextContains("6h 0m")
            compose.onNodeWithTag("${target}_pretracking").performScrollTo().assertTextContains("earlier dates outside reliable tracking", substring = true)
            compose.onNodeWithTag("${target}_balance").performScrollTo().assertTextContains("Unknown")
        }
    }

    @Test fun reviewBlockedPriorCreditIsSeparateFromCoveredProgress() {
        val prior = today.minusDays(1).atTime(9, 0).atZone(java.time.ZoneId.of("America/New_York")).toInstant()
        val events = listOf(RawEvent("first", "a", Transition.ENTER, prior),
            RawEvent("out", "a", Transition.EXIT, prior.plusSeconds(6 * 3600))) +
            listOf(enter(), RawEvent("today-out", "a", Transition.EXIT, now))
        val invalid = Correction("bad", "session:first", prior.plusSeconds(3600), prior,
            prior.plusSeconds(7 * 3600))
        show(data(events).copy(historyStartDate = null, corrections = listOf(invalid)))
        expandPeriods()
        compose.onNodeWithTag("ROLLING_30_credit").performScrollTo().assertTextContains("2h 55m")
        compose.onNodeWithTag("ROLLING_30_required").performScrollTo().assertTextContains("6h 0m")
        compose.onNodeWithTag("ROLLING_30_provisional_credit").performScrollTo()
            .assertTextContains("Excluded from covered-day progress", substring = true)
        compose.onNodeWithTag("ROLLING_30_balance").performScrollTo().assertTextContains("Unknown")
    }

    @Test fun observedMondayMakesMissingTuesdayALaterUnknownDay() {
        val zone = java.time.ZoneId.of("America/New_York")
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant()
        val monday = today.minusDays(2)
        val events = listOf(RawEvent("mon-in", "a", Transition.ENTER, at(monday, 9)),
            RawEvent("mon-out", "a", Transition.EXIT, at(monday, 11)),
            RawEvent("wed-in", "a", Transition.ENTER, at(today, 9)),
            RawEvent("wed-out", "a", Transition.EXIT, at(today, 11)))
        show(data(events).copy(historyStartDate = null))
        expandPeriods()
        compose.onNodeWithTag("ROLLING_30_days").performScrollTo().assertTextContains("2")
        compose.onNodeWithTag("ROLLING_30_required").performScrollTo().assertTextContains("12h 0m")
        compose.onNodeWithTag("ROLLING_30_unknown").performScrollTo()
            .assertTextContains("1 later calendar day has unknown coverage (Sep 22)", substring = true)
        compose.onNodeWithTag("ROLLING_30_pretracking").performScrollTo()
            .assertTextContains("Sep 20", substring = true)
        compose.onNodeWithTag("ROLLING_30_balance").performScrollTo().assertTextContains("Unknown")
    }

    @Test fun correctedObservedMondayStillMakesTuesdayALaterUnknownDay() {
        val zone = java.time.ZoneId.of("America/New_York")
        fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant()
        val monday = today.minusDays(2)
        val events = listOf(RawEvent("mon-in", "a", Transition.ENTER, at(monday, 9)),
            RawEvent("mon-out", "a", Transition.EXIT, at(monday, 11)),
            RawEvent("wed-in", "a", Transition.ENTER, at(today, 9)),
            RawEvent("wed-out", "a", Transition.EXIT, at(today, 11)))
        val correction = Correction("adjust-mon", "session:mon-in", at(monday, 9, 30),
            at(monday, 11), now)
        show(data(events).copy(historyStartDate = null, corrections = listOf(correction)))
        expandPeriods()
        compose.onNodeWithTag("ROLLING_30_days").performScrollTo().assertTextContains("2")
        compose.onNodeWithTag("ROLLING_30_required").performScrollTo().assertTextContains("12h 0m")
        compose.onNodeWithTag("ROLLING_30_unknown").performScrollTo()
            .assertTextContains("1 later calendar day has unknown coverage (Sep 22)", substring = true)
        compose.onNodeWithTag("ROLLING_30_balance").performScrollTo().assertTextContains("Unknown")
    }

    @Test fun activeArrivalWindowShowsZeroCreditAndCountdown() {
        val entry = Instant.parse("2026-09-23T13:00:00Z")
        val input = data(listOf(RawEvent("in", "a", Transition.ENTER, entry))).copy(now = entry.plusSeconds(180))
        show(input)
        compose.onNodeWithTag("today_credit").assertTextEquals("0m")
        compose.onNodeWithTag("arrival_credit_start").assertTextEquals("Time starts counting at 9:05 AM.")
        compose.onNodeWithTag("arrival_countdown").assertTextEquals("2m until time starts counting, if you stay.")
        compose.onNodeWithTag("today_details_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("today_observed").performScrollTo().assertTextContains("3m")
    }

    @Test fun trackingLossSuppressesAnOtherwiseAvailableDepartureEstimate() {
        show(data(listOf(enter())), ready = false)
        compose.onNodeWithTag("office_state").assertTextEquals("Office state unknown")
        compose.onNodeWithTag("today_leave").assertTextEquals("No leave time yet: your location isn't confirmed. Check office setup.")
        compose.onNodeWithTag("today_credit_note").assertTextEquals("Estimate · we can't confirm you're still there")
        compose.onNodeWithTag("today_balance").assertTextContains("About 3h 5m")
        expandTargets()
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
        compose.onNodeWithTag("today_balance").assertTextContains("3h 6m")
        update(data(listOf(RawEvent("missing", "a", Transition.EXIT, now))))
        compose.onNodeWithTag("office_state").assertTextEquals("Needs review")
        update(data().copy(policy = Policy(excludedDates = listOf(ExcludedDate(today, ExclusionReason.BANK_HOLIDAY)))))
        compose.onNodeWithTag("today_target").assertTextContains("0m")
        expandTargets()
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
        expandPeriods()
        compose.onNodeWithTag("ROLLING_90_average").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ROLLING_90_days").performScrollTo().assertTextContains("Expected workdays", substring = true)
    }

    @Test fun todayWordingWrapsAt360dpWithTwoHundredPercentText() {
        // Synthetic uncertain day: unknown history and unconfirmed detection produce the longest default copy.
        val input = data(listOf(enter())).copy(historyStartDate = null)
        val density = compose.activity.resources.displayMetrics.density
        compose.activity.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                HamsterTheme { Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                    DashboardScreen(input, AttendanceEngine.derive(input), trackingReady = false)
                } }
            }
        } }
        // Checks the copy this screen owns; the presence label is unchanged by #118.
        for (tag in listOf("today_credit", "today_credit_note", "today_leave", "today_untracked")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$tag has no text layout", layouts.isNotEmpty())
            assertTrue("$tag " + layouts.joinToString { "size=${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}, lines=${it.lineCount}" },
                layouts.none { it.hasVisualOverflow })
        }
        compose.onNodeWithTag("today_balance").performScrollTo().assertIsDisplayed().assertTextContains("Unknown")
        compose.onNodeWithTag("today_details_toggle").performScrollTo().assertHeightIsAtLeast(48.dp)
        // Opt-in synthetic evidence, as in DashboardSecurityUiTest; no production screenshot feature.
        if (InstrumentationRegistry.getArguments().getString("todayLargeTextScreenshot") == "true") {
            compose.onNodeWithTag("office_state").performScrollTo()
            File(compose.activity.cacheDir, "today-360dp-200pct.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun unconfirmedMetGoalAgreesAcrossRemainingTimeAndLeaveGuidance() {
        show(data(listOf(enter().copy(at = now.minusSeconds(7 * 3600)))), ready = false)
        compose.onNodeWithTag("today_balance").assertTextContains("Maybe none (goal may be met)")
        compose.onNodeWithTag("today_leave").assertTextEquals("Goal may be met. Check today's timeline before you leave.")
        // Confirmed control: both agree the goal is met.
        show(data(listOf(enter().copy(at = now.minusSeconds(7 * 3600)))), ready = true)
        compose.onNodeWithTag("today_balance").assertTextContains("None, goal met", substring = true)
        compose.onNodeWithTag("today_leave").assertTextEquals("Goal met. You can leave now.")
    }

    @Test fun secondarySectionsStartCollapsedAndKeepTheirValuesAcrossToggles() {
        show(data(listOf(enter())))
        val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
        for ((toggle, content) in listOf("departure_targets_toggle" to "departure_targets", "periods_toggle" to "periods")) {
            compose.onNodeWithTag(toggle).performScrollTo().assert(collapsed).assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag(content).assertDoesNotExist()
        }
        repeat(2) {
            expandTargets()
            compose.onNodeWithTag("departure_targets_toggle").assert(expanded)
            compose.onNodeWithTag("TODAY_departure").performScrollTo().assertTextContains("About 3:00 PM")
            compose.onNodeWithTag("ROLLING_90_departure").performScrollTo().assertExists()
            expandTargets()
            compose.onNodeWithTag("departure_targets_toggle").assert(collapsed)
            compose.onNodeWithTag("TODAY_departure").assertDoesNotExist()
        }
        repeat(2) {
            expandPeriods()
            compose.onNodeWithTag("periods_toggle").assert(expanded)
            compose.onNodeWithTag("WEEK_TO_DATE_required").performScrollTo().assertTextContains("18h 0m")
            compose.onNodeWithTag("full_week_required").performScrollTo().assertTextContains("30h 0m")
            compose.onNodeWithTag("ROLLING_90_balance").performScrollTo().assertExists()
            expandPeriods()
            compose.onNodeWithTag("periods_toggle").assert(collapsed)
            compose.onNodeWithTag("WEEK_TO_DATE_required").assertDoesNotExist()
        }
        // Today's primary group is unchanged by toggling secondary detail.
        compose.onNodeWithTag("today_credit").assertTextEquals("2h 55m")
        compose.onNodeWithTag("today_leave").assertTextEquals("You can leave at 3:00 PM")
    }

    @Test fun todayLeadsAt360dpAndAccessibilityOrderMatchesVisualOrder() {
        val input = data(listOf(enter()))
        compose.activity.runOnUiThread { compose.activity.setContent {
            HamsterTheme { Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                DashboardScreen(input, AttendanceEngine.derive(input), trackingReady = true)
            } }
        } }
        val expected = listOf("today_credit", "today_credit_note", "today_balance", "today_target", "today_leave",
            "office_state", "arrival_credit_start", "open_today_timeline", "today_details_toggle",
            "departure_targets_toggle", "periods_toggle")
        val nodes = compose.onAllNodes(SemanticsMatcher("dashboard order tags") {
            it.config.getOrNull(SemanticsProperties.TestTag) in expected
        }).fetchSemanticsNodes()
        val tags = nodes.map { it.config[SemanticsProperties.TestTag] }
        // Accessibility: semantics traversal order (what TalkBack reads) must equal the intended order.
        assertEquals(expected, tags)
        // Visual: layout positions must increase down the screen in the same order. boundsInRoot is clipped to the
        // scroll viewport (offscreen sections report 0), so this uses each node's unclipped position in root.
        val tops = nodes.map { it.positionInRoot.y }
        assertTrue("Visual order should follow reading order: ${expected.zip(tops)}", tops.zipWithNext().all { (a, b) -> a < b })
        // The collapsed sections start below the fold; scroll each into view and recheck its place on screen.
        fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        for ((above, below) in listOf("today_details_toggle" to "departure_targets_toggle", "departure_targets_toggle" to "periods_toggle")) {
            compose.onNodeWithTag(below).performScrollTo().assertIsDisplayed()
            assertTrue("$above should sit above $below once scrolled into view", top(above) < top(below))
        }
        // One action in the Today group when no setup/review notice applies.
        compose.onNodeWithText("View today's timeline").assertExists()
        compose.onNodeWithText("Open office setup").assertDoesNotExist()
        compose.onNodeWithText("Review history").assertDoesNotExist()
    }

    @Test fun expandedSectionsWrapWithoutClippingControlsAt360dpAndTwoHundredPercentText() {
        val input = data(listOf(enter())).copy(historyStartDate = null)
        val density = compose.activity.resources.displayMetrics.density
        compose.activity.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                HamsterTheme { Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                    DashboardScreen(input, AttendanceEngine.derive(input), trackingReady = true)
                } }
            }
        } }
        fun assertNoOverflow(label: String) {
            val text = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label has no text layout", layouts.isNotEmpty())
            for (layout in layouts) {
                val detail = "size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, " +
                    "lines=${layout.lineCount}, maxIntrinsic=${layout.multiParagraph.maxIntrinsicWidth}"
                assertFalse("$label is clipped vertically: $detail", layout.didOverflowHeight)
                // A button label is measured with loose width constraints. When it fits, Compose lays it out at its
                // max intrinsic width (finalMaxWidth), but GetTextLayoutResult rebuilds the paragraph at the full max
                // width (slowCreateTextLayoutResultOrNull: prevConstraints.copyMaxDimensions()), so didOverflowWidth
                // is reported for a label that fits. Real horizontal clipping means the content needs more width
                // than the label was given; a word longer than the button still fails here.
                assertTrue("$label is clipped horizontally: $detail",
                    !layout.didOverflowWidth || layout.multiParagraph.maxIntrinsicWidth <= layout.size.width)
            }
            // The label's whole unclipped box must also sit inside its control.
            val labelNode = text.fetchSemanticsNode()
            val control = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode()
            val inner = Rect(labelNode.positionInRoot,
                Size(labelNode.size.width.toFloat(), labelNode.size.height.toFloat()))
            val outer = Rect(control.positionInRoot,
                Size(control.size.width.toFloat(), control.size.height.toFloat()))
            assertTrue("$label $inner should fit inside its control $outer",
                inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f &&
                inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f)
        }
        compose.onNodeWithTag("open_today_timeline").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertNoOverflow("View today's timeline")
        assertNoOverflow("Show all departure targets")
        assertNoOverflow("Show week, 30-day and 90-day details")
        expandTargets()
        expandPeriods()
        for (toggle in listOf("departure_targets_toggle", "periods_toggle"))
            compose.onNodeWithTag(toggle).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertNoOverflow("Hide all departure targets")
        assertNoOverflow("Hide week, 30-day and 90-day details")
        compose.onNodeWithTag("TODAY_departure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ROLLING_90_departure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ROLLING_90_pretracking").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ROLLING_90_balance").performScrollTo().assertIsDisplayed().assertTextContains("Unknown")
        // Unknown longer-term history still leaves a Today estimate.
        compose.onNodeWithTag("today_leave").performScrollTo()
            .assertTextEquals("Estimated leave time: about 3:00 PM. Check today's timeline.")
    }

    /** Share of an action's rendered pixels in the filled-button colour (Amber); a filled primary button is mostly Amber. */
    private fun amberShare(node: SemanticsNodeInteraction): Float {
        val bitmap = node.performScrollTo().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val amber = CageStyle.Amber
        fun near(channel: Int, target: Float) = kotlin.math.abs(channel - (target * 255).toInt()) <= 24
        return pixels.count { near(it shr 16 and 0xFF, amber.red) && near(it shr 8 and 0xFF, amber.green) &&
            near(it and 0xFF, amber.blue) }.toFloat() / pixels.size
    }

    @Test fun overlappingSetupAndReviewNoticesKeepExactlyOnePrimaryAction() {
        // Synthetic fixtures: an unmatched EXIT needs review; trackingReady=false needs setup.
        val review = data(listOf(RawEvent("missing", "a", Transition.EXIT, now)))
        val clean = data(listOf(enter()))
        data class Case(val name: String, val input: AttendanceInput, val ready: Boolean, val setup: Boolean,
            val needsReview: Boolean, val primary: String)
        val cases = listOf(
            Case("overlap", review, ready = false, setup = true, needsReview = true, primary = "Open office setup"),
            Case("setup only", clean, ready = false, setup = true, needsReview = false, primary = "Open office setup"),
            Case("review only", review, ready = true, setup = false, needsReview = true, primary = "Review history"),
            Case("neither", clean, ready = true, setup = false, needsReview = false, primary = "View today's timeline"))
        for (case in cases) {
            var setupOpened = false
            var historyOpened = false
            compose.activity.runOnUiThread { compose.activity.setContent {
                HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                    DashboardScreen(case.input, AttendanceEngine.derive(case.input), case.ready,
                        openOffices = { setupOpened = true }, openHistory = { historyOpened = true })
                } }
            } }
            // Both warnings are preserved exactly when they apply.
            if (case.setup) compose.onNodeWithText("Detection is not confirmed").performScrollTo().assertIsDisplayed()
            else compose.onNodeWithText("Detection is not confirmed").assertDoesNotExist()
            if (case.needsReview) compose.onNodeWithText("A session needs review").performScrollTo().assertIsDisplayed()
            else compose.onNodeWithText("A session needs review").assertDoesNotExist()

            val actions = buildList {
                add("View today's timeline")
                if (case.setup) add("Open office setup")
                if (case.needsReview) add("Review history")
            }
            val shares = actions.associateWith { label ->
                val node = compose.onNode(hasText(label) and hasClickAction())
                node.performScrollTo().assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                amberShare(node)
            }
            // Exactly one action is rendered as the filled primary; the others are visibly secondary.
            val filled = shares.filterValues { it > 0.5f }.keys
            assertEquals("${case.name}: filled actions $shares", setOf(case.primary), filled)
            shares.filterKeys { it != case.primary }.forEach { (label, share) ->
                assertTrue("${case.name}: $label should be secondary ($share)", share < 0.3f)
            }
            // Secondary actions stay actionable.
            if (case.setup) {
                compose.onNode(hasText("Open office setup") and hasClickAction()).performScrollTo().performClick()
                compose.runOnIdle { assertTrue("${case.name}: setup action", setupOpened) }
            }
            if (case.needsReview) {
                compose.onNode(hasText("Review history") and hasClickAction()).performScrollTo().performClick()
                compose.runOnIdle { assertTrue("${case.name}: review action", historyOpened) }
            }
        }
    }
}
