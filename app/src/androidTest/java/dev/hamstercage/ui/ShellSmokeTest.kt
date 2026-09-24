package dev.hamstercage.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.hamstercage.MainActivity
import org.junit.Rule
import org.junit.Test

class ShellSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun eachOfflineDestinationOpens() {
        compose.onNodeWithText("Hamster Cage").assertIsDisplayed()
        val destinations = listOf(
            "Offices" to "No offices yet",
            "History" to "Every total comes from your local record.",
            "Settings" to "Attendance policy and privacy controls are coming in the settings features.",
            "Dashboard" to "Office state unknown",
        )
        destinations.forEach { (label, description) ->
            compose.onNode(hasText(label) and hasClickAction()).performClick()
            compose.onNodeWithText(description).assertIsDisplayed()
        }
    }

    @Test fun selectedDestinationSurvivesActivityRecreation() {
        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Every total comes from your local record.").assertIsDisplayed()
    }
}
