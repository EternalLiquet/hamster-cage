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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.Transition
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyStorageState
import dev.hamstercage.privacy.privacyVisibleStorage
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

    @Test fun idleAfterDeleteWaitsForFreshRoomQueryBeforeShowingAttendance() {
        val now = Instant.parse("2025-03-10T20:00:00Z")
        val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
        val entered = now.minusSeconds(60)
        val old = StorageState.Ready(AppSnapshot(listOf(office), listOf(RecordedEvent(
            RawEvent("before-delete", office.id, Transition.ENTER, entered), entered)),
            emptyList(), emptyList(), Policy()))
        val fresh = StorageState.Ready(AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy()))
        val reset = MutableStateFlow<PrivacyResetState>(PrivacyResetState.Idle(0))
        val releaseFreshQuery = CompletableDeferred<Unit>()
        var subscriptions = 0
        val paired = privacyVisibleStorage(reset) {
            flow {
                subscriptions++
                if (subscriptions == 1) emit(old)
                else { releaseFreshQuery.await(); emit(fresh) }
            }
        }
        compose.setContent {
            val presentation by paired.collectAsState(
                initial = PrivacyStorageState(PrivacyResetState.Unavailable, StorageState.Unavailable))
            HamsterApp(TimeSource { now }, storageState = presentation.storage,
                privacyState = presentation.reset, trackingReady = true)
        }
        compose.onNodeWithText("In Synthetic office").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { reset.value = PrivacyResetState.Pending(1) }
        compose.onNodeWithText("History deletion pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In Synthetic office").assertDoesNotExist()
        compose.runOnIdle { reset.value = PrivacyResetState.Idle(1) }
        compose.onNodeWithText("Opening your local record…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In Synthetic office").assertDoesNotExist()
        releaseFreshQuery.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Office state unknown").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Office state unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("In Synthetic office").assertDoesNotExist()
    }
}
