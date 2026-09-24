package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import dev.hamstercage.MainActivity
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OfficeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun userCanSelectEitherOfficeForEditing() {
        val first = Office("office-a", "Synthetic A", 0.0, 0.0)
        val second = Office("office-b", "Synthetic B", 1.0, 1.0)
        val snapshot = AppSnapshot(listOf(first, second), emptyList(), emptyList(), emptyList(), Policy())
        val loadedId = AtomicReference<String?>(null)
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                HamsterTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { id -> loadedId.set(id); 1L }, { _, _ -> }))
                    }
                }
            }
        }
        compose.onNodeWithTag("edit-office-a").performClick()
        compose.waitUntil { loadedId.get() != null }
        assertEquals("office-a", loadedId.get())
        compose.onNodeWithTag("officeName").assertTextContains("Synthetic A")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("edit-office-b").performClick()
        compose.onNodeWithTag("officeName").assertTextContains("Synthetic B")
        compose.onNodeWithText("Office name").assertIsDisplayed()
    }
}
