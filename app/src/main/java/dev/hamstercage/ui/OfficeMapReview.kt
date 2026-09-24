package dev.hamstercage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hamstercage.offices.OfficeMapProjection
import dev.hamstercage.offices.OfficeMapTile

/** One viewed OSM tile, a tappable center and the actual saved boundary radius. */
@Composable
fun OfficeMapReview(tile: OfficeMapTile?, latitude: Double, longitude: Double, radiusMeters: Float,
    onMovePin: (Double, Double) -> Unit) {
    if (tile == null) {
        Text("Map unavailable. Retry on a connection or use Advanced coordinates. No office has been saved.",
            style = MaterialTheme.typography.bodyMedium)
        return
    }
    Box(Modifier.size(280.dp).semantics {
        contentDescription = "Office map. Tap to move the pin; the circle shows the saved boundary radius."
    }) {
        Image(tile.bitmap.asImageBitmap(), contentDescription = "OpenStreetMap office area", contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize().pointerInput(tile) {
            detectTapGestures { point ->
                val (lat, lon) = OfficeMapProjection.pointAt(tile, point.x / size.width, point.y / size.height)
                onMovePin(lat, lon)
            }
        }) {
            val (x, y) = OfficeMapProjection.fractions(latitude, longitude, tile.zoom)
            val center = Offset(x * size.width, y * size.height)
            val radius = OfficeMapProjection.radiusPixels(latitude, radiusMeters, tile.zoom) * size.width / 256f
            drawCircle(Color(0x8835674D), radius, center)
            drawCircle(Color(0xFF174B37), radius, center, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color(0xFF9A2C24), 8.dp.toPx(), center)
            drawCircle(Color.White, 8.dp.toPx(), center, style = Stroke(width = 2.dp.toPx()))
        }
        Text("© OpenStreetMap contributors", modifier = Modifier.align(Alignment.BottomEnd).background(Color.White),
            color = Color.Black, style = MaterialTheme.typography.labelSmall)
    }
}
