package dev.hamstercage.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.*
import dev.hamstercage.privacy.PrivacyResetState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsSecurityUiTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    @Test fun unreadablePrivacyJournalHidesPreviouslyReadyFactsAcrossNavigation() {
        val office = Office("synthetic", "PRIVATE_OFFICE_SENTINEL_32", 0.125, -0.25)
        val manual = ManualSession("manual", office.id, now.minusSeconds(3600), now.minusSeconds(1800), now, "PRIVATE_NOTE_SENTINEL_32")
        val state = StorageState.Ready(AppSnapshot(listOf(office), emptyList(), emptyList(), listOf(manual), Policy()))
        compose.setContent { HamsterApp(TimeSource { now }, storageState = state,
            privacyState = PrivacyResetState.Unavailable,
            officeActions = OfficeActions({ "unused" }, { null }, { _, _ -> error("Must not edit unavailable data") }),
            savePolicy = { _, _ -> error("Must not edit unavailable data") },
            privacyActions = PrivacyActions({ error("Must not delete unreadable history") }, {}, { false })) }
        for (page in listOf("Dashboard", "History", "Offices", "Settings")) {
            compose.onNode(hasText(page) and hasClickAction()).performClick()
            listOf(office.name, manual.note, "0.125", "-0.25").forEach {
                compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertDoesNotExist()
            }
            compose.onNodeWithTag("today_credit").assertDoesNotExist()
            compose.onNodeWithTag("privacy_delete_history").assertDoesNotExist()
        }
        compose.onNodeWithTag("privacy_reset_all").performScrollTo().assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("settingsSecurityScreenshot") == "true") {
            File(compose.activity.cacheDir, "settings-security-32.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun calendarNoteControlsDoNotChangeItsStoredPrivateValue() {
        val today = LocalDate.of(2026, 9, 23)
        val note = "\u202eSynthetic <literal> note\u0000"
        val policy = Policy(excludedDates = listOf(ExcludedDate(today, ExclusionReason.PTO, note)))
        val state = StorageState.Ready(AppSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), policy))
        compose.setContent { HamsterTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
            CalendarScreen(state, today, CalendarActions({}, {}, { _, _ -> }))
        } } } }
        compose.onNodeWithText("Synthetic <literal> note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("\u202e", substring = true, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(note, state.snapshot.policy.excludedDates.single().note)
    }
}
