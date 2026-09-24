package dev.hamstercage.ui

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
import org.junit.Rule
import org.junit.Test

class ShellSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun eachOfflineDestinationOpens() {
        compose.onNodeWithText("Hamster Cage").assertIsDisplayed()
        val destinations = listOf(
            "Offices" to "Saved offices work offline.",
            "History" to "Every total comes from your local record.",
            "Settings" to "Attendance policy",
            "Dashboard" to "Office state unknown",
        )
        destinations.forEach { (label, description) ->
            compose.onNode(hasText(label) and hasClickAction()).performClick()
            if (label == "Dashboard")
                compose.onNodeWithTag("office_state").performScrollTo().assertTextEquals(description).assertIsDisplayed()
            else compose.onNodeWithText(description, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun selectedDestinationSurvivesActivityRecreation() {
        compose.onNode(hasText("History") and hasClickAction()).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Every total comes from your local record.").assertIsDisplayed()
    }
}
