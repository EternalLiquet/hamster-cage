package dev.hamstercage.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherResourcesTest {
    @Test fun installedPackageUsesAdaptiveSelectedArtworkAndThemedLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, info.icon)
        val icon = context.packageManager.getApplicationIcon(info)
        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull("Themed launcher needs its monochrome layer", icon.monochrome)
    }

    @Test fun packagedArtworkAndMonochromeStayInsideAdaptiveSafeZone() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for (resource in listOf(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)) {
            val bitmap = BitmapFactory.decodeResource(resources, resource)
            assertEquals(432, bitmap.width)
            assertEquals(432, bitmap.height)
            var visible = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    visible++
                    // Android's 66dp safe box is centered in a 108dp layer.
                    assertTrue("Artwork is outside the mask-safe zone", x in 84..347 && y in 84..347)
                }
            }
            assertTrue("The icon must contain recognizable visible content", visible > 10000)
            bitmap.recycle()
        }
    }
}
