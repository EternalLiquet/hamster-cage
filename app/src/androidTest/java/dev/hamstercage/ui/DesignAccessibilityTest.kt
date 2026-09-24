package dev.hamstercage.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.hamstercage.MainActivity
import dev.hamstercage.domain.TimeSource
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class DesignAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun twoHundredPercentTextKeepsAllDestinationsReadableAndTouchable() {
        val density = compose.activity.resources.displayMetrics.density
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    HamsterApp(TimeSource { Instant.parse("2026-09-23T12:00:00Z") }, ZoneOffset.UTC)
                }
            }
        }
        listOf("Offices", "History", "Settings", "Dashboard").forEach { label ->
            val target = compose.onNode(hasText(label) and hasClickAction())
            target.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            val textResults = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasClickAction()), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                action(textResults)
            }
            textResults.forEach { assertFalse("Navigation text must not truncate", it.hasVisualOverflow) }
        }
        compose.onNodeWithText("Opening your local record…")
            .performScrollTo().assertIsDisplayed()
    }
}
