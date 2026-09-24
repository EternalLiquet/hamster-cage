package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.hamstercage.MainActivity
import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.location.LocationSetup
import java.time.Instant
import java.time.ZoneId
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test fun postRecoveryObservationChangesVisibleOfficeState() {
        val now = Instant.parse("2026-09-24T14:00:10Z")
        val boundary = now.minusSeconds(10)
        val zone = ZoneId.of("America/New_York")
        val office = Office("synthetic", "Synthetic office", 39.0, -86.0)
        val ready = CoverageLedger().registrationSucceeded(boundary, zone, hasOffices = true)
        var coverage by mutableStateOf(ready)
        var storage by mutableStateOf<StorageState>(StorageState.Ready(
            AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy(zoneId = zone))))
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterApp(timeSource = TimeSource { now }, storageState = storage,
                    coverage = coverage, captureStatus = CaptureStatus(RegistrationStatus.ACTIVE))
            }
        }
        compose.onNodeWithTag("office_state").assertTextEquals("Office state unknown")
        val observed = boundary.plusSeconds(2)
        val event = RawEvent("fix", office.id, Transition.ENTER, observed)
        compose.activity.runOnUiThread {
            storage = StorageState.Ready(AppSnapshot(listOf(office),
                listOf(RecordedEvent(event, now, observed, "FOREGROUND_LOCATION_RECONCILIATION")),
                emptyList(), emptyList(), Policy(zoneId = zone)))
            coverage = ready.observed(observed)
        }
        compose.onNodeWithTag("office_state").assertTextEquals("In Synthetic office")
    }
}
