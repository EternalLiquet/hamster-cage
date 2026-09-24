package dev.hamstercage.offices

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    @Test fun datelineWrapKeepsFullBoundaryAndTapCoordinates() {
        for (longitude in listOf(-179.999, 179.999)) {
            val latitude = 0.0
            val radius = 150f
            val zoom = OfficeMapProjection.reviewZoom(latitude, radius)
            assertTrue(OfficeMapProjection.reviewFits(latitude, longitude, radius, zoom))
            val (x, y) = OfficeMapProjection.reviewOrigin(latitude, longitude, zoom)
            val viewport = OfficeMapTile(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), zoom, x, y)
            val (pinX, pinY) = OfficeMapProjection.viewportFractions(viewport, latitude, longitude)
            val edge = OfficeMapProjection.radiusPixels(latitude, radius, zoom) / 512f
            assertTrue(pinX - edge > 0f && pinX + edge < 1f)
            val (mappedLat, mappedLon) = OfficeMapProjection.pointAt(viewport, pinX, pinY)
            assertEquals(latitude, mappedLat, 0.00001)
            assertEquals(longitude, mappedLon, 0.00001)
        }
    }

    @Test fun polarReviewFailsClosedIfWholeCircleCannotFit() {
        assertTrue(OfficeMapProjection.reviewFits(85.0, 10.0, 5000f,
            OfficeMapProjection.reviewZoom(85.0, 5000f)))
        assertFalse(OfficeMapProjection.reviewFits(85.05, 10.0, 5000f,
            OfficeMapProjection.reviewZoom(85.05, 5000f)))
    }

    @Test fun locationProviderFailureNeverShowsRawDiagnostic() {
        val revoked = currentRequestFailure(SecurityException("internal permission stack"))
        assertTrue(revoked.message!!.contains("Grant it in Settings"))
        assertFalse(revoked.message!!.contains("internal permission stack"))
        val service = currentRequestFailure(IllegalStateException("provider account secret"))
        assertTrue(service.message!!.contains("Retry outdoors"))
        assertFalse(service.message!!.contains("provider account secret"))
    }

    @Test fun tileDecoderRejectsOversizedDimensionsBeforeFullDecode() {
        fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        assertNotNull(decodeOfficeTile(png(256, 256)))
        assertNull(decodeOfficeTile(png(512, 512)))
        assertNull(decodeOfficeTile(png(256, 512)))
        assertNull(decodeOfficeTile(ByteArray(300_001)))
    }
}
