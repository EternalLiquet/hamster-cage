package dev.hamstercage.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.Modifier
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.Policy
import dev.hamstercage.offices.OfficeMapProjection
import dev.hamstercage.offices.OfficeMapTile
import dev.hamstercage.offices.OfficePlace
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
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("edit-office-b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("edit-office-b").performScrollTo().performClick()
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
        compose.waitForIdle()
        compose.onNodeWithTag("officeName").assertTextContains("Synthetic A")
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        if (savedVersion.get() == null) {
            // A save while the restored version is still loading must fail closed, then retry.
            compose.waitForIdle()
            compose.onNodeWithText("Save office").performScrollTo().performClick()
        }
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

    @Test fun addressResultNeedsExplicitSelectionAndMapConfirmationBeforeSave() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val tileAttempts = AtomicInteger()
        val tile = OfficeMapTile(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888), 16,
            OfficeMapProjection.tileX(0.0, 16), OfficeMapProjection.tileY(0.0, 16))
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "synthetic-new" }, { null },
                        { office, _ -> saved.set(office) },
                        search = { listOf(OfficePlace("Synthetic A", 0.0, 0.0), OfficePlace("Synthetic B", 0.0, 0.01)) },
                        tile = { _, _ -> if (tileAttempts.incrementAndGet() == 1) null else tile }))
                }
            }
        }
        compose.onNode(hasText("Add office") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("officeName").performTextReplacement("Test workplace")
        compose.onNodeWithTag("officeAddress").performTextReplacement("synthetic address")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Select Synthetic B").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        compose.onNodeWithText("Select Synthetic B").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        compose.onNodeWithText("Retry map").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("© OpenStreetMap contributors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals(0.01, saved.get()!!.longitude, 0.000001)
        assertEquals(150f, saved.get()!!.radiusMeters, 0f)
    }

    @Test fun changedQueryCannotShowOrSelectOlderAddressResults() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val pending = CompletableDeferred<List<OfficePlace>>()
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { null }, { _, _ -> },
                        search = { pending.await() }))
                }
            }
        }
        compose.onNode(hasText("Add office") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("officeAddress").performTextReplacement("old address")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.onNodeWithTag("officeAddress").performTextReplacement("new address")
        pending.complete(listOf(OfficePlace("Old result", 0.0, 0.0)))
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Select Old result").fetchSemanticsNodes().size)
        compose.onNodeWithTag("searchAddressButton").assertIsDisplayed()
    }
}
