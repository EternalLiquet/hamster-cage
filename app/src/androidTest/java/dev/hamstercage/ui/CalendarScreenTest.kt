package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.*
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalendarScreenTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val today = LocalDate.of(2026, 9, 23)
    private fun state(policy: Policy) = StorageState.Ready(AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), policy))
    @Test fun addEditAndRemoveKeepExclusionAndWfhIndependent() {
        var policy by mutableStateOf(Policy())
        val actions = CalendarActions(
            saveExclusion = { value -> policy = policy.copy(excludedDates = policy.excludedDates.filterNot { it.date == value.date } + value) },
            removeExclusion = { day -> policy = policy.copy(excludedDates = policy.excludedDates.filterNot { it.date == day }) },
            setWfh = { day, enabled -> policy = policy.copy(wfhDates = if (enabled) policy.wfhDates + day else policy.wfhDates - day) },
        )
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CalendarScreen(state(policy), today, actions) } } }
        compose.onNodeWithText("No employer holiday feed is preloaded. Add only the dates that apply to your policy.").assertIsDisplayed()
        compose.onNodeWithText("Add excluded date").performClick()
        compose.onNodeWithText("PTO").performScrollTo().performClick()
        compose.onNodeWithTag("calendar_note").performScrollTo().performTextInput("Synthetic note")
        compose.onNodeWithText("Save exclusion").performScrollTo().performClick()
        compose.waitUntil(5_000) { policy.excludedDates.size == 1 }
        compose.onNodeWithText("Label WFH date").performScrollTo().performClick()
        compose.waitUntil(5_000) { today in policy.wfhDates }
        compose.onNodeWithText("Edit exclusion for $today").performScrollTo().performClick()
        compose.onNodeWithTag("calendar_date").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Company closure").performScrollTo().performClick()
        compose.onNodeWithText("Save exclusion").performScrollTo().performClick()
        compose.waitUntil(5_000) { policy.excludedDates.single().reason == ExclusionReason.COMPANY_CLOSURE }
        assertTrue(today in policy.wfhDates)
        compose.onNodeWithText("Remove exclusion for $today").performScrollTo().performClick()
        compose.waitUntil(5_000) { policy.excludedDates.isEmpty() }
        compose.onNodeWithText("WFH", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove WFH for $today").performScrollTo().performClick()
        compose.waitUntil(5_000) { policy.wfhDates.isEmpty() }
        compose.onNodeWithText("No calendar dates yet").performScrollTo().assertIsDisplayed()
    }

    @Test fun invalidInputAndFailedSaveKeepTheDraftAndHideSensitiveDetails() {
        var writes = 0
        val actions = CalendarActions({ writes++; throw IOException("private-calendar-secret") }, {}, { _, _ -> })
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CalendarScreen(state(Policy()), today, actions) } } }
        compose.onNodeWithText("Add excluded date").performClick()
        compose.onNodeWithTag("calendar_date").performTextReplacement("2026-02-30")
        compose.onNodeWithText("Save exclusion").performScrollTo().performClick()
        compose.onNodeWithText("Error: Enter a valid date using YYYY-MM-DD.").performScrollTo().assertIsDisplayed()
        assertEquals(0, writes)
        compose.onNodeWithTag("calendar_date").performScrollTo().performTextReplacement(today.toString())
        compose.onNodeWithTag("calendar_note").performScrollTo().performTextInput("Kept synthetic note")
        compose.onNodeWithText("Save exclusion").performScrollTo().performClick()
        compose.onNodeWithText("Error: Calendar change could not be saved. Your existing records were kept. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("calendar_note").performScrollTo().assertTextContains("Kept synthetic note")
        compose.onNodeWithText("private-calendar-secret", substring = true).assertDoesNotExist()
        assertEquals(1, writes)
    }

    @Test fun pendingSaveRestoresAnEditableDraft() {
        val restoration = StateRestorationTester(compose)
        val actions = CalendarActions({ awaitCancellation() }, {}, { _, _ -> })
        restoration.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CalendarScreen(state(Policy()), today, actions) } } }
        compose.onNodeWithText("Add excluded date").performClick()
        compose.onNodeWithTag("calendar_note").performScrollTo().performTextInput("Restored note")
        compose.onNodeWithText("Save exclusion").performScrollTo().performClick()
        compose.onNodeWithText("Save exclusion").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save exclusion").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("calendar_note").performScrollTo().assertTextContains("Restored note")
    }
}
