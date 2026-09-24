package dev.hamstercage.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test fun readableTextMeetsNormalTextContrastAcrossEveryCardSurface() {
        val surfaces = listOf(CageStyle.Background, CageStyle.Surface, CageStyle.Raised, CageStyle.Warm)
        val inks = listOf(CageStyle.Text, CageStyle.Secondary, CageStyle.Muted, CageStyle.Amber, CageStyle.Peach, CageStyle.Pink, CageStyle.Danger)
        surfaces.forEach { surface -> inks.forEach { ink ->
            assertTrue("Text contrast ${ratio(ink, surface)} is below 4.5:1", ratio(ink, surface) >= 4.5f)
        } }
    }

    @Test fun filledActionTextMeetsNormalTextContrast() {
        assertTrue(ratio(CageStyle.Background, CageStyle.Amber) >= 4.5f)
        assertTrue(ratio(CageStyle.Background, CageStyle.Peach) >= 4.5f)
    }
}
