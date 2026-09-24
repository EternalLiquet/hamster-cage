package dev.hamstercage.offices

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeMapProjectionTest {
    @Test fun reviewedBoundaryFitsAroundPinNearTileEdge() {
        // This public landmark exposed a clipped circle when review showed only its containing tile.
        val latitude = 48.85837
        val longitude = 2.29448
        for (radius in listOf(50f, 150f, 5000f)) {
            val zoom = OfficeMapProjection.reviewZoom(latitude, radius)
            val (x, y) = OfficeMapProjection.reviewOrigin(latitude, longitude, zoom)
            val viewport = OfficeMapTile(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), zoom, x, y)
            val (pinX, pinY) = OfficeMapProjection.viewportFractions(viewport, latitude, longitude)
            val boundary = OfficeMapProjection.radiusPixels(latitude, radius, zoom) / 512f
            assertTrue("left boundary", pinX - boundary > 0f)
            assertTrue("right boundary", pinX + boundary < 1f)
            assertTrue("top boundary", pinY - boundary > 0f)
            assertTrue("bottom boundary", pinY + boundary < 1f)
            val (roundTripLat, roundTripLon) = OfficeMapProjection.pointAt(viewport, pinX, pinY)
            assertEquals(latitude, roundTripLat, 0.00001)
            assertEquals(longitude, roundTripLon, 0.00001)
        }
    }
}
