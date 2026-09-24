package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.privacy.PrivacyResetState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrivacyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val ready = StorageState.Ready(AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy()))

    @Test fun cancelPreservesBothScopesAndHistoryConfirmCallsOnlyHistoryDeletion() {
        var historyCalls = 0
        var fullCalls = 0
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PrivacyScreen(ready, PrivacyResetState.Idle(0), PrivacyActions(
                deleteHistory = { historyCalls++ }, retryPending = {}, resetAllAppData = { fullCalls++; false }))
        } } }
        compose.onNodeWithTag("privacy_delete_history").performScrollTo().performClick()
        compose.onNodeWithText("This removes all raw transitions, manual sessions, corrections, excluded dates and notes, WFH labels, coverage and active attendance totals. Offices and base policy settings stay. New observations can be recorded after setup recovers.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("privacy_cancel").performScrollTo().performClick()
        assertEquals(0, historyCalls)
        assertEquals(0, fullCalls)
        compose.onNodeWithTag("privacy_delete_history").performScrollTo().performClick()
        compose.onNodeWithTag("privacy_confirm").performScrollTo().performClick()
        compose.waitUntil(5_000) { historyCalls == 1 }
        assertEquals(0, fullCalls)
        compose.onNodeWithText("Attendance and calendar history deleted.").performScrollTo().assertIsDisplayed()
    }

    @Test fun fullResetRequiresSeparatePreviewAndPendingDeletionOnlyOffersRetry() {
        var historyCalls = 0
        var fullCalls = 0
        var retries = 0
        val actions = PrivacyActions(deleteHistory = { historyCalls++ }, retryPending = { retries++ },
            resetAllAppData = { fullCalls++; false })
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PrivacyScreen(ready, PrivacyResetState.Idle(0), actions)
        } } }
        compose.onNodeWithTag("privacy_reset_all").performScrollTo().performClick()
        compose.onNodeWithText("Android will clear every app-private database and preference, including attendance, calendar, offices, policy and capture health, and revoke app permissions. The app may close; reopen it to start fresh. This cannot be undone.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("privacy_confirm").performScrollTo().performClick()
        assertEquals(1, fullCalls)
        assertEquals(0, historyCalls)
        compose.onNodeWithText("Android could not reset app data. Your saved data was kept.").performScrollTo().assertIsDisplayed()

        compose.activity.runOnUiThread { compose.activity.setContent {
            HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                PrivacyScreen(StorageState.Unavailable, PrivacyResetState.Pending(1), actions)
            } }
        } }
        compose.onNodeWithTag("privacy_delete_history").assertDoesNotExist()
        compose.onNodeWithTag("privacy_retry").performScrollTo().performClick()
        compose.waitUntil(5_000) { retries == 1 }
    }

    @Test fun pendingOrRequestedResetHidesPreviouslyReadyAttendance() {
        compose.setContent {
            HamsterApp(TimeSource { Instant.parse("2025-03-10T20:00:00Z") }, storageState = ready,
                privacyState = PrivacyResetState.Pending(1))
        }
        compose.onNodeWithText("History deletion pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Office state unknown").assertDoesNotExist()
    }
}
