package dev.hamstercage.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hamstercage.domain.Office
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficeFormTest {
    @get:Rule val compose = createComposeRule()

    @Test fun malformedCoordinatesDoNotSave() {
        var saved: Office? = null
        compose.setContent { HamsterTheme { OfficeDialog(null,{},onSave={saved=it}) } }
        compose.onNodeWithText("Office name").performTextInput("Test office")
        compose.onNodeWithText("Latitude (−90 to 90)").performTextInput("not-a-coordinate")
        compose.onNodeWithText("Longitude (−180 to 180)").performTextInput("0")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.onNodeWithText("Latitude must be a number from −90 to 90.").assertExists()
        assertNull(saved)
    }

    @Test fun validOfficeRetainsConfiguration() {
        var saved: Office? = null
        compose.setContent { HamsterTheme { OfficeDialog(null,{},onSave={saved=it}) } }
        compose.onNodeWithText("Office name").performTextInput("Test office")
        compose.onNodeWithText("Latitude (−90 to 90)").performTextInput("0")
        compose.onNodeWithText("Longitude (−180 to 180)").performTextInput("0")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Test office",saved?.name)
            assertEquals(150f,saved!!.radiusMeters,0f)
            assertTrue(saved!!.enabled)
            assertTrue(saved!!.countsTowardAttendance)
            assertEquals(5,saved!!.entryGraceMinutes)
        }
    }
}
