package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.hamstercage.MainActivity
import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.location.LocationSetup
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WeekdayMonitoringUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun disclosureLastVerifiedAndDisableControlAreVisible() {
        var toggle: Boolean? = null
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                LocationSetupPanel(LocationSetup(true, true, true, true, true), "Allow all the time",
                    {}, {}, {}, {}, RegistrationStatus.ACTIVE, captureStatus = CaptureStatus(
                        RegistrationStatus.ACTIVE, monitoringEnabled = true,
                        lastVerifiedAt = Instant.parse("2026-09-28T19:30:00Z"),
                        lastCheckResult = "Inside office"), policyZone = ZoneId.of("America/New_York"),
                    setMonitoringEnabled = { toggle = it })
                }
            }
        }
        compose.onNodeWithText("Monitoring on").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last verified Mon 3:30 PM · weekday checks 7 AM–7 PM")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Latest check: Inside office").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("With monitoring on", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Disable attendance monitoring") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, toggle) }
    }

    @Test fun disabledStateIsDistinctFromRegistrationFailure() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                LocationSetupPanel(LocationSetup(true, true, true, true, true), "Allow all the time",
                    {}, {}, {}, {}, RegistrationStatus.DISABLED, captureStatus = CaptureStatus(
                        RegistrationStatus.DISABLED, monitoringEnabled = false))
                }
            }
        }
        compose.onNodeWithText("Monitoring disabled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Attendance monitoring is disabled.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enable attendance monitoring").performScrollTo().assertIsDisplayed()
    }
}
