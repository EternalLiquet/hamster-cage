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
import dev.hamstercage.capture.ReconcileOutcome
import dev.hamstercage.capture.RegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocationSetupPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun show(setup: LocationSetup, foreground: () -> Unit = {}, background: () -> Unit = {},
        offices: Int = 0, reconcile: (suspend () -> ReconcileOutcome)? = null,
        registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
        setMonitoringEnabled: suspend (Boolean) -> Unit = {}) {
        rule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                HamsterTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocationSetupPanel(setup, "Allow all the time", foreground, background, {}, {},
                            registration, offices, reconcile,
                            setMonitoringEnabled = setMonitoringEnabled)
                    }
                }
            }
        }
    }

    @Test fun foregroundAndBackgroundActionsAreStagedAndDeclineRemainsPossible() {
        var foregroundCalls = 0
        var backgroundCalls = 0
        var monitoringEnabled: Boolean? = null
        show(LocationSetup(), { foregroundCalls++ }, { backgroundCalls++ },
            setMonitoringEnabled = { monitoringEnabled = it })
        rule.onNodeWithText("Set up background location").assertDoesNotExist()
        rule.onNodeWithText("Allow precise location").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, foregroundCalls); assertEquals(0, backgroundCalls) }
        rule.onNodeWithText("Continue without detection").performScrollTo().performClick()
        rule.onNodeWithText("Attendance monitoring disabled").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(false, monitoringEnabled) }
        rule.onNodeWithText("Review and re-enable detection").performScrollTo().performClick()
        rule.onNodeWithText("Precise location not allowed").performScrollTo().assertIsDisplayed()
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

    @Test fun explicitOneShotActionExplainsConfirmedAndUnknownResults() {
        var calls = 0
        var outcome = ReconcileOutcome.CONFIRMED
        show(LocationSetup(true, true, true, true, true), offices = 1,
            registration = RegistrationStatus.ACTIVE,
            reconcile = { calls++; outcome })
        rule.onNodeWithText("Check current office").performScrollTo().performClick()
        rule.onNodeWithText("Inside an office. A session starts at the current observation; walking grace is uncredited.")
            .performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, calls) }
        outcome = ReconcileOutcome.OUTSIDE
        rule.onNodeWithText("Check current office").performScrollTo().performClick()
        rule.onNodeWithText("Outside saved office boundaries. Office state remains unknown; retry when inside.")
            .performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, calls) }
    }
}
