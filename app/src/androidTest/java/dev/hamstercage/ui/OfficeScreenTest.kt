package dev.hamstercage.ui

import android.graphics.Bitmap
import android.location.Location
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
import dev.hamstercage.offices.OfficeFix
import dev.hamstercage.offices.validatedOfficeFix
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OfficeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()

    @Test fun savedSmallAndTouchingOfficeAreasShowWarningsWithoutChangingRadii() {
        val first = Office("office-a", "Synthetic A", 0.0, 0.0, 50f)
        val second = Office("office-b", "Synthetic B", 0.0, 0.001, 200f)
        val snapshot = AppSnapshot(listOf(first, second), emptyList(), emptyList(), emptyList(), Policy())
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { 1L }, { _, _ -> }))
        } } }
        compose.onNodeWithText("Small office area may make background boundaries unreliable.").assertExists()
        assertEquals(2, compose.onAllNodesWithText(
            "Touches another enabled attendance office area; location may be ambiguous.",
            useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Edit Synthetic A").performScrollTo().performClick()
        compose.onNodeWithTag("officeRadius").assertExists()
        compose.onNodeWithText("Small office area: background geofencing may fluctuate.", substring = true)
            .assertExists()
    }

    @Test fun providerDiagnosticsNeverAppearInOfficeSetupErrors() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { null }, { _, _ -> },
                search = { throw IOException("private geocoder provider secret") },
                current = { throw SecurityException("private location provider stack") }))
        } } }
        compose.onNode(hasText("Add office") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("officeAddress").performTextReplacement("synthetic address")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(
            "Error: Address search unavailable. Retry or use current location or Advanced coordinates.")
            .fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("private geocoder provider secret", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(
            "Error: Current location unavailable. Retry or search an address.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("private location provider stack", substring = true).fetchSemanticsNodes().size)
    }

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
                        tile = { _, _, _ -> if (tileAttempts.incrementAndGet() == 1) null else tile }))
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
        compose.waitUntil(5_000) { tileAttempts.get() == 1 }
        compose.onNodeWithText("Retry map").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("© OpenStreetMap contributors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals(0.01, saved.get()!!.longitude, 0.000001)
        assertEquals(200f, saved.get()!!.radiusMeters, 0f)
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

    @Test fun explicitCurrentFixShowsAccuracyAndStillNeedsMapConfirmation() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val tile = OfficeMapTile(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888), 16,
            OfficeMapProjection.tileX(0.0, 16), OfficeMapProjection.tileY(0.0, 16))
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "synthetic-current" }, { null },
                        { office, _ -> saved.set(office) },
                        current = { OfficeFix(OfficePlace("Current location", 0.0, 0.0), 12f) },
                        tile = { _, _, _ -> tile }))
                }
            }
        }
        compose.onNode(hasText("Add office") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("officeName").performTextReplacement("Synthetic current office")
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Current fix accuracy: 12 m. Review the pin before saving.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("© OpenStreetMap contributors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals(0.0, saved.get()!!.latitude, 0.000001)
    }

    @Test fun changedRadiusCannotConfirmOldMapBeforeNewViewportLoads() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val secondTile = CompletableDeferred<OfficeMapTile>()
        val loads = AtomicInteger()
        val tile = OfficeMapTile(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), 16,
            OfficeMapProjection.reviewOrigin(0.0, 0.0, 16).first, OfficeMapProjection.reviewOrigin(0.0, 0.0, 16).second)
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { null },
                        { office, _ -> saved.set(office) },
                        search = { listOf(OfficePlace("Synthetic center", 0.0, 0.0)) },
                        tile = { _, _, _ -> if (loads.incrementAndGet() == 1) tile else secondTile.await() }))
                }
            }
        }
        compose.onNode(hasText("Add office") and hasClickAction()).performClick()
        compose.onNodeWithTag("officeName").performTextReplacement("Synthetic office")
        compose.onNodeWithTag("officeAddress").performTextReplacement("synthetic place")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Select Synthetic center").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Select Synthetic center").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("© OpenStreetMap contributors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("officeRadius").performScrollTo().performTextReplacement("5000")
        compose.waitUntil(5_000) { loads.get() >= 2 }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        val wideZoom = OfficeMapProjection.reviewZoom(0.0, 5000f)
        val (wideX, wideY) = OfficeMapProjection.reviewOrigin(0.0, 0.0, wideZoom)
        secondTile.complete(OfficeMapTile(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), wideZoom, wideX, wideY))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("© OpenStreetMap contributors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals(5000f, saved.get()!!.radiusMeters, 0f)
    }

    @Test fun replacementLookupCannotSaveOldConfirmedCenter() {
        val office = Office("office-a", "Synthetic office", 0.0, 0.0)
        val snapshot = AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val pendingSearch = CompletableDeferred<List<OfficePlace>>()
        val pendingFix = CompletableDeferred<OfficeFix>()
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { 1L },
                        { changed, _ -> saved.set(changed) }, search = { pendingSearch.await() }, current = { pendingFix.await() }))
                }
            }
        }
        compose.onNodeWithTag("edit-office-a").performClick()
        compose.onNodeWithTag("officeAddress").performTextReplacement("new synthetic place")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        pendingFix.complete(OfficeFix(OfficePlace("Synthetic current", 1.0, 1.0), 10f))
    }

    @Test fun accessiblePinMoveWaitsForNewCenteredMap() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val secondTile = CompletableDeferred<OfficeMapTile>()
        val loads = AtomicInteger()
        val zoom = OfficeMapProjection.reviewZoom(0.0, 200f)
        val (x, y) = OfficeMapProjection.reviewOrigin(0.0, 0.0, zoom)
        val tile = OfficeMapTile(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), zoom, x, y)
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { null },
                        { office, _ -> saved.set(office) },
                        search = { listOf(OfficePlace("Synthetic center", 0.0, 0.0)) },
                        tile = { _, _, _ -> if (loads.incrementAndGet() == 1) tile else secondTile.await() }))
                }
            }
        }
        compose.onNode(hasText("Add office") and hasClickAction()).performClick()
        compose.onNodeWithTag("officeName").performTextReplacement("Synthetic office")
        compose.onNodeWithTag("officeAddress").performTextReplacement("synthetic place")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Select Synthetic center").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Select Synthetic center").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Move pin east 100 meters").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Move pin east 100 meters").performScrollTo().performClick()
        compose.waitUntil(5_000) { loads.get() >= 2 }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        val moved = OfficeMapProjection.moveByMeters(0.0, 0.0, 0.0, 100.0)
        val (movedX, movedY) = OfficeMapProjection.reviewOrigin(moved.first, moved.second, zoom)
        secondTile.complete(OfficeMapTile(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), zoom, movedX, movedY))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Move pin east 100 meters").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals(moved.second, saved.get()!!.longitude, 0.00001)
    }

    @Test fun providerLabelCannotImpersonateAdvancedManualFallback() {
        val snapshot = AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val tileAttempts = AtomicInteger()
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { null },
                        { office, _ -> saved.set(office) },
                        search = { listOf(OfficePlace("Manual coordinates", 0.0, 0.0)) },
                        tile = { _, _, _ -> tileAttempts.incrementAndGet(); null }))
                }
            }
        }
        compose.onNode(hasText("Add office") and hasClickAction()).performClick()
        compose.onNodeWithTag("officeName").performTextReplacement("Synthetic office")
        compose.onNodeWithTag("officeAddress").performTextReplacement("synthetic place")
        compose.onNodeWithTag("searchAddressButton").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Select Manual coordinates").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Select Manual coordinates").performScrollTo().performClick()
        compose.waitUntil(5_000) { tileAttempts.get() > 0 }
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        compose.onNodeWithText("Advanced: coordinates and walking grace").performScrollTo().performClick()
        compose.onNodeWithText("Arrival walking grace (minutes)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exit/departure grace (minutes)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Used only to estimate when you may leave; it does not add recorded attendance.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review manual coordinates").performScrollTo().performClick()
        compose.onNodeWithText("Confirm pin and radius").performScrollTo().performClick()
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved.get() != null }
    }

    @Test fun poorAndStaleOneShotFixesCannotSaveOldConfirmedOffice() {
        val office = Office("office-a", "Synthetic office", 0.0, 0.0)
        val snapshot = AppSnapshot(listOf(office), emptyList(), emptyList(), emptyList(), Policy())
        val saved = AtomicReference<Office?>(null)
        val attempts = AtomicInteger()
        val now = 100_000_000_000L
        compose.setContent {
            HamsterTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OfficeScreen(StorageState.Ready(snapshot), OfficeActions({ "new" }, { 1L },
                        { changed, _ -> saved.set(changed) }, current = {
                            val attempt = attempts.incrementAndGet()
                            val fix = Location("synthetic").apply {
                                latitude = 0.0; longitude = 0.0
                                accuracy = if (attempt == 1) 150f else 12f
                                elapsedRealtimeNanos = now - if (attempt == 1) 1_000_000_000L else 31_000_000_000L
                            }
                            validatedOfficeFix(fix, now)
                        }))
                }
            }
        }
        compose.onNodeWithTag("edit-office-a").performClick()
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Error: Location is too approximate for an office boundary. Retry outdoors or search an address.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        compose.onNodeWithText("Use my current location").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Error: The location fix is stale. Retry to get a fresh position.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save office").performScrollTo().performClick()
        assertEquals(null, saved.get())
        assertEquals(2, attempts.get())
    }
}
