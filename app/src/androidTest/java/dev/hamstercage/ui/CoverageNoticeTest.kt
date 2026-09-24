package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.hamstercage.MainActivity
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import java.time.LocalDate
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
        compose.onNodeWithText("Office state unknown").performScrollTo().assertIsDisplayed()
    }
}
