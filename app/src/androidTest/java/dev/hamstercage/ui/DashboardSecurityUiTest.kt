package dev.hamstercage.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class DashboardSecurityUiTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    @Test fun coordinatesSourceIdsAndPrivateNotesDoNotReachDashboardSemanticsOrScreenshot() {
        val office = Office("OFFICE_ID_SENTINEL_24", "Synthetic\u202e\u0000 office", 0.125, -0.25)
        val today = LocalDate.of(2026, 9, 23)
        val input = AttendanceInput(listOf(office), listOf(RawEvent("RAW_ID_SENTINEL_24", office.id, Transition.ENTER, now.minusSeconds(1800))),
            policy = Policy(excludedDates = listOf(ExcludedDate(today.minusDays(1), ExclusionReason.PTO, "PRIVATE_NOTE_SENTINEL_24"))),
            now = now, historyStartDate = today.minusDays(100), unknownDates = setOf(today))
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            DashboardScreen(input, AttendanceEngine.derive(input), trackingReady = true)
        } } }
        compose.onNodeWithTag("office_state").assertTextEquals("In Synthetic office")
        compose.onNodeWithTag("today_balance").assertTextContains("Unknown")
        compose.onNodeWithTag("TODAY_departure").performScrollTo().assertTextContains("History incomplete")
        listOf("0.125", "-0.25", "OFFICE_ID_SENTINEL_24", "RAW_ID_SENTINEL_24", "PRIVATE_NOTE_SENTINEL_24", "\u202e").forEach {
            compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("office_state").performScrollTo()
        // Opt-in synthetic evidence stays in app-private test cache and is extracted by the
        // host before uninstall. No production screenshot/export feature is introduced.
        if (InstrumentationRegistry.getArguments().getString("dashboardSecurityScreenshot") == "true") {
            val file = File(compose.activity.cacheDir, "dashboard-security-24.png")
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun storageFailureDisplaysOnlySanitizedUnavailableState() {
        compose.setContent { HamsterApp(TimeSource { now }, storageState = StorageState.Unavailable) }
        compose.onNodeWithText("Attendance data unavailable").assertIsDisplayed()
        compose.onNodeWithTag("today_credit").assertDoesNotExist()
        compose.onNodeWithText("SQLite", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/data/", substring = true).assertDoesNotExist()
    }
}
