package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.PolicySettings
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Policy
import java.io.IOException
import java.time.DayOfWeek
import java.time.ZoneId
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PolicyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val state = StorageState.Ready(AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy()))
    private fun show(save: suspend (PolicySettings, PolicySettings) -> Unit) {
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PolicyScreen(state, save) } } }
        compose.onNodeWithText("Edit policy").performClick()
    }

    @Test fun validSaveExplainsRecomputationAndCarriesTheOriginalSnapshot() {
        var saved: PolicySettings? = null
        var expected: PolicySettings? = null
        show { next, previous -> saved = next; expected = previous }
        compose.onNodeWithTag("policy_target").performScrollTo().performTextReplacement("420")
        compose.onNodeWithTag("policy_zone").performTextReplacement("UTC")
        compose.onNodeWithText("Saving changes recalculates past and current totals, required days and departure estimates. Original events, corrections, holidays and WFH labels remain intact.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save and recalculate").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals(420, saved!!.targetMinutesPerDay)
        assertEquals(ZoneId.of("UTC"), saved!!.zoneId)
        assertEquals(PolicySettings(), expected)
    }

    @Test fun invalidTargetAndEmptyWeekdaysNeverReachPersistence() {
        var saves = 0
        show { _, _ -> saves++ }
        compose.onNodeWithTag("policy_target").performTextReplacement("0")
        compose.onNodeWithText("Save and recalculate").performScrollTo().performClick()
        compose.onNodeWithText("Error: Target must be 1 to 1440 whole minutes.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("policy_target").performScrollTo().performTextReplacement("360")
        (1..5).map(DayOfWeek::of).forEach { compose.onNodeWithTag("policy_day_${it.name}").performScrollTo().performClick() }
        compose.onNodeWithText("Save and recalculate").performScrollTo().performClick()
        compose.onNodeWithText("Error: Choose at least one expected weekday.").performScrollTo().assertIsDisplayed()
        assertEquals(0, saves)
    }

    @Test fun failedStorageKeepsTheDraftAndShowsASanitizedRetryMessage() {
        show { _, _ -> throw IOException("private-path-secret-must-not-display") }
        compose.onNodeWithTag("policy_target").performTextReplacement("480")
        compose.onNodeWithText("Save and recalculate").performScrollTo().performClick()
        compose.onNodeWithText("Error: Policy could not be saved. Your recorded attendance was kept. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("policy_target").performScrollTo().assertTextContains("480")
        compose.onNodeWithText("private-path-secret-must-not-display", substring = true).assertDoesNotExist()
    }

    @Test fun restoredDraftDoesNotRemainLockedByALostSavingCoroutine() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PolicyScreen(state) { _, _ -> awaitCancellation() }
        } } }
        compose.onNodeWithText("Edit policy").performClick()
        compose.onNodeWithTag("policy_target").performTextReplacement("480")
        compose.onNodeWithText("Save and recalculate").performScrollTo().performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save and recalculate").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("policy_target").performScrollTo().assertTextContains("480")
    }
}
