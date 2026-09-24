package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.hamstercage.MainActivity
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class CoverageNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun uncertainDateOffersHistoryReviewWithoutClaimingPresence() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterApp(timeSource = TimeSource { Instant.parse("2025-03-10T20:00:00Z") },
                    storageState = StorageState.Ready(AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())),
                    coverage = CoverageLedger(historyStartDate = LocalDate.parse("2025-03-10"),
                        unknownDates = setOf(LocalDate.parse("2025-03-10"))))
            }
        }
        compose.onNodeWithText("Attendance coverage needs review").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("office_state").performScrollTo().assertTextEquals("Office state unknown")
    }

    @Test fun currentRegistrationFailureOverridesStaleConfirmedCoverage() {
        val now = Instant.parse("2025-03-10T20:00:00Z")
        val entered = now.minusSeconds(60)
        val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
        val snapshot = AppSnapshot(listOf(office), listOf(RecordedEvent(
            RawEvent("synthetic-enter", office.id, Transition.ENTER, entered), entered)),
            emptyList(), emptyList(), Policy(zoneId = ZoneId.of("UTC")))
        val stale = CoverageLedger(historyStartDate = LocalDate.parse("2025-03-10"),
            lastObservationAt = entered, recoveryBoundaryAt = entered.minusSeconds(1),
            registration = RegistrationStatus.ACTIVE, policyZoneId = ZoneId.of("UTC"))
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterApp(timeSource = TimeSource { now }, storageState = StorageState.Ready(snapshot),
                    coverage = stale, captureStatus = CaptureStatus(RegistrationStatus.FAILED))
            }
        }
        compose.onNodeWithTag("office_state").performScrollTo().assertTextEquals("Office state unknown")
        compose.onNodeWithText("Office detection unavailable").performScrollTo().assertIsDisplayed()
    }

    @Test fun deliveryFailureOverridesStaleConfirmedCoverageEvenWithActiveRegistration() {
        val now = Instant.parse("2025-03-10T20:00:00Z")
        val entered = now.minusSeconds(60)
        val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
        val snapshot = AppSnapshot(listOf(office), listOf(RecordedEvent(
            RawEvent("synthetic-enter", office.id, Transition.ENTER, entered), entered)),
            emptyList(), emptyList(), Policy(zoneId = ZoneId.of("UTC")))
        val stale = CoverageLedger(historyStartDate = LocalDate.parse("2025-03-10"),
            lastObservationAt = entered, recoveryBoundaryAt = entered.minusSeconds(1),
            registration = RegistrationStatus.ACTIVE, policyZoneId = ZoneId.of("UTC"))
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterApp(timeSource = TimeSource { now }, storageState = StorageState.Ready(snapshot),
                    coverage = stale, captureStatus = CaptureStatus(RegistrationStatus.ACTIVE, deliveryFailure = true))
            }
        }
        compose.onNodeWithTag("office_state").performScrollTo().assertTextEquals("Office state unknown")
        compose.onNodeWithText("Attendance capture needs attention").performScrollTo().assertIsDisplayed()
    }
}
