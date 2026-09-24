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
        compose.onNodeWithTag("correction_preview_total").performScrollTo().assertTextEquals("All recorded credited time: 0m → 25m")
        assertNull(saved)
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Attendance change saved").assertIsDisplayed()
        assertTrue(saved is AttendanceEdit.Correct)
        assertEquals(input.events, saved!!.proposedInput().events)
        // Corrected 14:30–15:00 bounds earn 25m after the 5m arrival delay.
        assertEquals(25.0, AttendanceEngine.derive(saved!!.proposedInput()).intervals.sumOf { it.minutes }, 0.0)
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
    @Test fun sameMillisecondRevertWithSmallerIdWinsAndPreviewMatchesSavedMarker() {
        val existing = Correction("z", session.id, now.minusSeconds(5400), now.minusSeconds(3600), now, appendSequence = 1)
        val corrected = input.copy(corrections = listOf(existing))
        assertEquals(25.0, AttendanceEngine.derive(corrected).intervals.sumOf { it.minutes }, 0.0)
        var saved: AttendanceEdit? = null
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CorrectionEditor(corrected, AttendanceEngine.derive(corrected).sessions.single(),
                CorrectionActions({ now }, { "a" }, { saved = it })) {}
        } } }
        compose.onNodeWithText("Preview revert to original").performScrollTo().performClick()
        compose.onNodeWithTag("correction_preview_total").performScrollTo().assertTextEquals("All recorded credited time: 25m → 0m")
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Attendance change saved").assertIsDisplayed()
        val marker = (saved as AttendanceEdit.Correct).value
        assertEquals(2L, marker.appendSequence)
        val restored = AttendanceEngine.derive(saved!!.proposedInput())
        assertEquals("a", restored.sessions.single().correctionId)
        assertTrue(restored.sessions.single().correctionReverted)
        assertTrue(restored.intervals.isEmpty())
    }
}
