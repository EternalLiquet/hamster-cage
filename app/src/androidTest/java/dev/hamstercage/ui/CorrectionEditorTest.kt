package dev.hamstercage.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import dev.hamstercage.data.AttendanceEdit
import dev.hamstercage.domain.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CorrectionEditorTest {
    @get:Rule val compose = createAndroidComposeRule<OfficeTestActivity>()
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    private val input = AttendanceInput(listOf(office), listOf(RawEvent("exit", office.id, Transition.EXIT, now.minusSeconds(3600))), now = now)
    private val session get() = AttendanceEngine.derive(input).sessions.single()
    private fun show(save: suspend (AttendanceEdit) -> Unit) {
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CorrectionEditor(input, session, CorrectionActions({ now }, { "edit" }, save)) {}
        } } }
    }
    private fun validDraft() {
        compose.onNodeWithTag("correction_start").performScrollTo().performTextReplacement("2026-09-23T14:30Z")
        compose.onNodeWithTag("correction_end").performScrollTo().performTextReplacement("2026-09-23T15:00Z")
    }
    @Test fun invalidBoundsAreBlockedAndPreviewNeedsExplicitConfirmationWithoutLocationPermission() {
        var saved: AttendanceEdit? = null
        show { saved = it }
        compose.onNodeWithText("Original observations stay unchanged. This adds a visible manual audit entry. Location permission is not required.").assertIsDisplayed()
        compose.onNodeWithTag("correction_start").performScrollTo().performTextReplacement("2026-09-23T15:30Z")
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Error: End must follow start.").performScrollTo().assertIsDisplayed()
        assertNull(saved)
        validDraft()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithTag("correction_preview_total").performScrollTo().assertTextEquals("All recorded credited time: 0m → 40m")
        assertNull(saved)
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Attendance change saved").assertIsDisplayed()
        assertTrue(saved is AttendanceEdit.Correct)
        assertEquals(input.events, saved!!.proposedInput().events)
    }
    @Test fun failedSaveReturnsEditableDraftWithoutSensitiveErrorDetails() {
        show { throw IOException("private-correction-secret") }
        validDraft()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Error: Change could not be saved, or the record changed. Your facts were kept. Review a fresh preview and try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("correction_start").performScrollTo().assertTextContains("2026-09-23T14:30Z")
        compose.onNodeWithText("private-correction-secret", substring = true).assertDoesNotExist()
    }
    @Test fun restorationDropsUncommittedPreviewAndUnlocksThePreservedDraft() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CorrectionEditor(input, session, CorrectionActions({ now }, { "edit" }, { awaitCancellation() })) {}
        } } }
        validDraft()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Preview attendance change").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("correction_start").performScrollTo().assertTextContains("2026-09-23T14:30Z")
    }
}
