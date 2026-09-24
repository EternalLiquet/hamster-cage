package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OfficeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()

    @Test fun userCanSelectEitherOfficeForEditing() {
        val first = Office("office-a", "Synthetic A", 0.0, 0.0)
        val second = Office("office-b", "Synthetic B", 1.0, 1.0)
        val snapshot = AppSnapshot(listOf(first, second), emptyList(), emptyList(), emptyList(), Policy())
        val loadedId = AtomicReference<String?>(null)
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { id -> loadedId.set(id); 1L }, { _, _ -> }))
                }
            }
        }
        compose.onNodeWithTag("edit-office-a").performClick()
        compose.waitUntil(5_000) { loadedId.get() != null }
        assertEquals("office-a", loadedId.get())
        compose.onNodeWithTag("officeName").assertTextContains("Synthetic A")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("edit-office-b").performClick()
        compose.onNodeWithTag("officeName").assertTextContains("Synthetic B")
        compose.onNodeWithText("Office name").assertIsDisplayed()
    }

    @Test fun editVersionReloadsAfterStateRestoration() {
        val office = Office("office-a", "Synthetic A", 0.0, 0.0)
        val snapshot = AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy())
        val firstLoad = CompletableDeferred<Long>()
        val loads = AtomicInteger()
        val savedVersion = AtomicReference<Long?>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, {
                        if (loads.incrementAndGet() == 1) firstLoad.await() else 2L
                    }, { _, version -> savedVersion.set(version) }))
                }
            }
        }
        compose.onNodeWithTag("edit-office-a").performClick()
        compose.waitUntil(5_000) { loads.get() == 1 }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { loads.get() >= 2 }
        compose.onNodeWithTag("officeName").assertTextContains("Synthetic A")
        compose.onNodeWithText("Save office").performClick()
        compose.waitUntil(5_000) { savedVersion.get() != null }
        assertEquals(2L, savedVersion.get())
    }

    @Test fun listShowsFractionalSavedRadius() {
        val office = Office("office-a", "Synthetic A", 0.0, 0.0, radiusMeters = 150.9f)
        val snapshot = AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy())
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { 1L }, { _, _ -> }))
                }
            }
        }
        compose.onNodeWithText("150.9 m").assertIsDisplayed()
    }
}
