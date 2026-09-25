package dev.hamstercage.ui

import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
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
    private fun pickTime(tag: String, hour: Int, minute: Int) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        onView(isAssignableFrom(TimePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "Select local correction time"
            override fun perform(uiController: UiController, view: View) {
                (view as TimePicker).hour = hour
                view.minute = minute
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
    }
    private fun pickDate(tag: String, year: Int, month: Int, day: Int) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "Select local correction date"
            override fun perform(uiController: UiController, view: View) {
                (view as DatePicker).updateDate(year, month - 1, day)
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
    }
    private fun validDraft() {
        pickTime("correction_start_time", 10, 30)
        pickTime("correction_end_time", 11, 0)
    }
    @Test fun invalidBoundsAreBlockedAndPreviewNeedsExplicitConfirmationWithoutLocationPermission() {
        var saved: AttendanceEdit? = null
        show { saved = it }
        compose.onNodeWithText("Original observations stay unchanged. This adds a visible manual audit entry. Location permission is not required.").assertIsDisplayed()
        compose.onNodeWithText("Eastern Time", substring = true).performScrollTo().assertIsDisplayed()
        pickTime("correction_start_time", 11, 30)
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Error: End must follow start.").performScrollTo().assertIsDisplayed()
        assertNull(saved)
        validDraft()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithTag("correction_preview_total").performScrollTo().assertTextEquals("All recorded credited time: 0m → 25m")
        compose.onNodeWithTag("correction_preview_bounds").performScrollTo()
            .assert(hasText("10:30 AM", substring = true))
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
        compose.onNodeWithTag("correction_start_time").performScrollTo().assert(hasText("10:30", substring = true))
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
        compose.onNodeWithTag("correction_start_time").performScrollTo().assert(hasText("10:30", substring = true))
    }
    @Test fun localDateControlsAndOpenEndRemainExplicitInPreview() {
        var saved: AttendanceEdit? = null
        show { saved = it }
        pickDate("correction_start_date", 2026, 9, 22)
        pickTime("correction_start_time", 10, 30)
        compose.onNodeWithTag("correction_has_end").performScrollTo().performClick()
        compose.onNodeWithText("No end selected · this creates an open session.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithTag("correction_preview_bounds").performScrollTo()
            .assert(hasText("Open session", substring = true))
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Attendance change saved").assertIsDisplayed()
        val correction = (saved as AttendanceEdit.Correct).value
        assertEquals(Instant.parse("2026-09-22T14:30:00Z"), correction.start)
        assertNull(correction.end)
    }
    @Test fun repeatedHourRequiresAnExplicitOccurrenceBeforeManualSave() {
        val dstNow = Instant.parse("2026-11-02T12:00:00Z")
        val dstInput = AttendanceInput(listOf(office), emptyList(), now = dstNow)
        var saved: AttendanceEdit? = null
        compose.setContent { HamsterTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CorrectionEditor(dstInput, null, CorrectionActions({ dstNow }, { "manual" }, { saved = it })) {}
        } } }
        pickDate("correction_start_date", 2026, 11, 1)
        pickTime("correction_start_time", 1, 30)
        compose.onNodeWithText("Clocks repeat this time.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithText("Error: Choose the first or second occurrence", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("correction_start_overlap_1").performScrollTo().performClick()
        compose.onNodeWithText("Preview attendance change").performScrollTo().performClick()
        compose.onNodeWithTag("correction_preview_bounds").performScrollTo().assert(hasText("Open session", substring = true))
        compose.onNodeWithText("Confirm attendance change").performScrollTo().performClick()
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), (saved as AttendanceEdit.AddManual).value.start)
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
