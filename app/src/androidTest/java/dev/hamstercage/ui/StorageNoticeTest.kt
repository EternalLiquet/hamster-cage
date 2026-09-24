package dev.hamstercage.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class StorageNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableStorageHasSanitizedVisibleNotice() {
        compose.setContent {
            HamsterApp(
                timeSource = TimeSource { Instant.parse("2025-03-10T20:00:00Z") },
                storageState = StorageState.Unavailable,
            )
        }
        compose.onNodeWithText("Attendance data unavailable").assertIsDisplayed()
        compose.onNodeWithText("Local attendance data could not be opened. Saved data was kept for recovery.").assertIsDisplayed()
    }
}
