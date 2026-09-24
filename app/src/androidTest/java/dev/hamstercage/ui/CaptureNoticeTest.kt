package dev.hamstercage.ui

import androidx.activity.compose.setContent
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
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.location.LocationSetup
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class CaptureNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun failuresHaveVisibleSanitizedNotices() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterApp(timeSource = TimeSource { Instant.parse("2025-03-10T20:00:00Z") },
                    captureStatus = CaptureStatus(RegistrationStatus.FAILED, deliveryFailure = true))
            }
        }
        compose.onNodeWithText("Office detection unavailable").assertIsDisplayed()
        compose.onNodeWithText("Attendance capture needs attention").assertIsDisplayed()
        compose.onNodeWithText("A boundary update could not be saved locally. Saved attendance data was kept for review.").assertIsDisplayed()
    }

    @Test fun registrationSuccessStatesOnlyBoundaryRequestNotObservedPresence() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterApp(timeSource = TimeSource { Instant.parse("2025-03-10T20:00:00Z") },
                    locationSetup = LocationSetup(true, true, true, true, true),
                    captureStatus = CaptureStatus(RegistrationStatus.ACTIVE, registeredCount = 2))
            }
        }
        compose.onNode(hasText("Offices") and hasClickAction()).performClick()
        compose.onNodeWithText("Office boundary requests are registered on this device. Background delivery may be delayed.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Permissions are ready. Automatic detection is not running: office registration has not been configured.")
            .assertDoesNotExist()
    }
}
