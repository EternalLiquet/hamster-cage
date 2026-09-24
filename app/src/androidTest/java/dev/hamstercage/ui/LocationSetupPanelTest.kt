package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.hamstercage.MainActivity
import dev.hamstercage.location.LocationSetup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocationSetupPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun show(setup: LocationSetup, foreground: () -> Unit = {}, background: () -> Unit = {}) {
        rule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HamsterTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocationSetupPanel(setup, "Allow all the time", foreground, background, {}, {})
                    }
                }
            }
        }
    }

    @Test fun foregroundAndBackgroundActionsAreStagedAndDeclineRemainsPossible() {
        var foregroundCalls = 0
        var backgroundCalls = 0
        show(LocationSetup(), { foregroundCalls++ }, { backgroundCalls++ })
        rule.onNodeWithText("Set up background location").assertDoesNotExist()
        rule.onNodeWithText("Allow precise location").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, foregroundCalls); assertEquals(0, backgroundCalls) }
        rule.onNodeWithText("Continue without detection").performScrollTo().performClick()
        rule.onNodeWithText("Detection setup skipped").assertIsDisplayed()
        rule.onNodeWithText("Review location setup").performClick()
        rule.onNodeWithText("Precise location not allowed").assertIsDisplayed()
    }

    @Test fun approximateAndMissingServicesCannotClaimMonitoring() {
        show(LocationSetup(coarseLocation = true))
        rule.onNodeWithText("Approximate location only").assertIsDisplayed()
        rule.onNodeWithText("Set up background location").assertDoesNotExist()
        show(LocationSetup(true, true, true, true, false))
        rule.onNodeWithText("Google Play services unavailable").assertIsDisplayed()
    }

    @Test fun foregroundGrantShowsBackgroundStepAndAllGrantsStillDoNotClaimCapture() {
        var backgroundCalls = 0
        show(LocationSetup(true, true, false, true, true), background = { backgroundCalls++ })
        rule.onNodeWithText("Allow precise location").assertDoesNotExist()
        rule.onNodeWithText("Set up background location").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, backgroundCalls) }
        show(LocationSetup(true, true, true, true, true))
        rule.onNodeWithText("Location prerequisites ready").assertIsDisplayed()
        rule.onNodeWithText("Permissions are ready. Automatic detection is not running: office registration has not been configured.").performScrollTo().assertIsDisplayed()
    }
}
