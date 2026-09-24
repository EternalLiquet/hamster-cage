package dev.hamstercage.offices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OfficePlace(val label: String, val latitude: Double, val longitude: Double)
data class OfficeFix(val place: OfficePlace, val accuracyMeters: Float)
data class OfficeMapTile(val bitmap: Bitmap, val zoom: Int, val x: Int, val y: Int)

internal fun currentRequestFailure(failure: Exception): IllegalStateException = if (failure is SecurityException)
    IllegalStateException("Precise foreground location was revoked. Grant it in Settings, then retry; or search an address.")
else IllegalStateException("Current location service is unavailable. Retry outdoors, check Play Services, or search an address.")

/** OSM standard tiles are 256×256; inspect headers before allowing bitmap allocation. */
internal fun decodeOfficeTile(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > 300_000) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth != 256 || bounds.outHeight != 256) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.width == 256 && it.height == 256 }
}

/** Network access is limited to a user-submitted address or a selected map viewport. */
class OfficeLocationServices(private val context: Context) {
    private val legacySearchLock = Mutex()
    private val tileRequestLock = Mutex()
    private val activeSearch = AtomicLong(0)
    private val nextSearch = AtomicLong(1)

    suspend fun search(query: String): List<OfficePlace> {
        val text = query.trim().take(160)
        require(text.length >= 3) { "Enter at least three address characters." }
        if (!Geocoder.isPresent()) throw IllegalStateException("Address search is unavailable on this device. Use current location or Advanced coordinates.")
        fun places(matches: List<Address>): List<OfficePlace> = matches.mapIndexedNotNull { index, address ->
            val lat = address.latitude; val lon = address.longitude
            if (!lat.isFinite() || !lon.isFinite() || lat !in -85.0511..85.0511 || lon !in -180.0..180.0) null
            else {
                val label = address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(address.featureName, address.locality, address.adminArea, address.countryName)
                        .joinToString(", ").takeIf { it.isNotBlank() }
                label?.let { OfficePlace(if (matches.size > 1) "${it.take(170)} (result ${index + 1})" else it.take(200), lat, lon) }
            }
        }.distinctBy { it.latitude to it.longitude }
        val requestId = if (Build.VERSION.SDK_INT >= 33) nextSearch.getAndIncrement() else 0L
        try {
            return withTimeout(8_000) {
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= 33) {
                    check(activeSearch.compareAndSet(0, requestId)) { "An address search is still pending. Retry shortly or use current location or Advanced coordinates." }
                    places(suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocationName(text, 5, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (activeSearch.compareAndSet(requestId, 0) && continuation.isActive) continuation.resume(addresses)
                            }
                            override fun onError(errorMessage: String?) {
                                if (activeSearch.compareAndSet(requestId, 0) && continuation.isActive)
                                    continuation.resumeWithException(IllegalStateException("Address search unavailable. Retry or use another location method."))
                            }
                        })
                    })
                } else legacySearchLock.withLock {
                    @Suppress("DEPRECATION")
                    places(withContext(Dispatchers.IO) { geocoder.getFromLocationName(text, 5).orEmpty() })
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("Address search timed out. Retry later, or use current location or Advanced coordinates.")
        } finally { if (requestId != 0L) activeSearch.compareAndSet(requestId, 0) }
    }

    suspend fun current(): OfficeFix {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw IllegalStateException("Precise foreground location is needed. Grant it below, then retry; background access is not needed to set up an office.")
        if (!LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager))
            throw IllegalStateException("Device location is off. Turn it on in Settings, then retry.")
        val cancellation = CancellationTokenSource()
        try {
            val fix = try {
                withTimeout(15_000) {
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token).await()
                }
            } catch (_: TimeoutCancellationException) {
                throw IllegalStateException("Current location timed out. Retry outdoors or search an address.")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw currentRequestFailure(failure)
            } ?: throw IllegalStateException("No current fix. Move into open sky and retry, or search an address.")
            if (!fix.hasAccuracy() || !fix.accuracy.isFinite() || fix.accuracy > 100f)
                throw IllegalStateException("Location is too approximate for an office boundary. Retry outdoors or search an address.")
            if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -85.0511..85.0511 || fix.longitude !in -180.0..180.0)
                throw IllegalStateException("The fix has no usable map center. Retry or search an address.")
            if (fix.elapsedRealtimeNanos <= 0 || SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos !in 0..30_000_000_000L)
                throw IllegalStateException("The location fix is stale. Retry to get a fresh position.")
            return OfficeFix(OfficePlace("Current location", fix.latitude, fix.longitude), fix.accuracy)
        } finally { cancellation.cancel() }
    }

    suspend fun tile(latitude: Double, longitude: Double, radiusMeters: Float): OfficeMapTile? = tileRequestLock.withLock { withContext(Dispatchers.IO) {
        val zoom = OfficeMapProjection.reviewZoom(latitude, radiusMeters)
        val (x, y) = OfficeMapProjection.reviewOrigin(latitude, longitude, zoom)
        if (!OfficeMapProjection.reviewFits(latitude, longitude, radiusMeters, zoom)) return@withContext null
        val map = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(map)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (row in 0..1) for (column in 0..1) {
            currentCoroutineContext().ensureActive()
            val tileX = Math.floorMod(x + column, 1 shl zoom)
            val tileY = y + row
            val bitmap = loadTile(zoom, tileX, tileY) ?: run { map.recycle(); return@withContext null }
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(column * 256, row * 256, (column + 1) * 256, (row + 1) * 256), paint)
        }
        OfficeMapTile(map, zoom, x, y)
    } }

    private fun loadTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val directory = File(context.cacheDir, "office-map-tiles").apply { mkdirs() }
        val file = File(directory, "$zoom-$x-$y.png")
        val cached = if (file.isFile && file.length() in 1..300_000)
            runCatching { decodeOfficeTile(file.readBytes()) }.getOrNull() else null
        if (cached != null && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 60 * 60 * 1000)
            return cached
        return try {
            val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "HamsterCagePreview/0.1 (Android office map review)")
            connection.useCaches = true
            if (cached != null) connection.ifModifiedSince = file.lastModified()
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null) {
                    file.setLastModified(System.currentTimeMillis())
                    return cached
                }
                if (connection.responseCode != 200) return cached
                val bytes = connection.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (output.size() <= 300_000) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 300_000) return cached
                val bitmap = decodeOfficeTile(bytes) ?: return cached
                file.writeBytes(bytes)
                bitmap
            } finally { connection.disconnect() }
        } catch (_: Exception) { cached }
    }
}

object OfficeMapProjection {
    fun moveByMeters(latitude: Double, longitude: Double, northMeters: Double, eastMeters: Double): Pair<Double, Double> {
        val movedLatitude = (latitude + northMeters / 111_320.0).coerceIn(-85.0511, 85.0511)
        val eastDegrees = eastMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.08))
        val movedLongitude = ((longitude + eastDegrees + 180) % 360 + 360) % 360 - 180
        return movedLatitude to movedLongitude
    }
    fun world(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
        val scale = (1 shl zoom).toDouble()
        val radians = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (lon + 180) / 360 * scale to
            (1 - kotlin.math.ln(kotlin.math.tan(radians) + 1 / kotlin.math.cos(radians)) / Math.PI) / 2 * scale
    }
    fun reviewZoom(latitude: Double, radiusMeters: Float): Int = (17 downTo 2).first { zoom ->
        radiusPixels(latitude, radiusMeters.coerceIn(50f, 5000f), zoom) <= 100f
    }
    fun reviewOrigin(latitude: Double, longitude: Double, zoom: Int): Pair<Int, Int> {
        val (worldX, worldY) = world(latitude, longitude, zoom)
        val largestOrigin = (1 shl zoom) - 2
        return kotlin.math.floor(worldX - 0.5).toInt() to
            kotlin.math.floor(worldY - 0.5).toInt().coerceIn(0, largestOrigin)
    }
    fun reviewFits(latitude: Double, longitude: Double, radiusMeters: Float, zoom: Int): Boolean {
        val (originX, originY) = reviewOrigin(latitude, longitude, zoom)
        val (worldX, worldY) = world(latitude, longitude, zoom)
        val x = (worldX - originX) / 2; val y = (worldY - originY) / 2
        val radius = radiusPixels(latitude, radiusMeters, zoom) / 512
        return x - radius > 0 && x + radius < 1 && y - radius > 0 && y + radius < 1
    }
    fun tileX(lon: Double, zoom: Int) = (((lon + 180) / 360 * (1 shl zoom)).toInt()).coerceIn(0, (1 shl zoom) - 1)
    fun tileY(lat: Double, zoom: Int): Int {
        val radians = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (((1 - kotlin.math.ln(kotlin.math.tan(radians) + 1 / kotlin.math.cos(radians)) / Math.PI) / 2) * (1 shl zoom)).toInt()
            .coerceIn(0, (1 shl zoom) - 1)
    }
    fun fractions(lat: Double, lon: Double, zoom: Int): Pair<Float, Float> {
        val worldX = (lon + 180) / 360 * (1 shl zoom)
        val radians = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val worldY = (1 - kotlin.math.ln(kotlin.math.tan(radians) + 1 / kotlin.math.cos(radians)) / Math.PI) / 2 * (1 shl zoom)
        return (worldX - kotlin.math.floor(worldX)).toFloat() to (worldY - kotlin.math.floor(worldY)).toFloat()
    }
    fun pointAt(tile: OfficeMapTile, xFraction: Float, yFraction: Float): Pair<Double, Double> {
        val scale = (1 shl tile.zoom).toDouble()
        val tilesAcross = tile.bitmap.width / 256.0
        val worldX = (tile.x + xFraction.coerceIn(0f, 1f) * tilesAcross) % scale
        val lon = ((worldX + scale) % scale) / scale * 360 - 180
        val n = Math.PI * (1 - 2 * (tile.y + yFraction.coerceIn(0f, 1f) * tilesAcross) / scale)
        val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(n)))
        return lat to lon
    }
    fun viewportFractions(tile: OfficeMapTile, latitude: Double, longitude: Double): Pair<Float, Float> {
        val (worldX, worldY) = world(latitude, longitude, tile.zoom)
        val tilesAcross = tile.bitmap.width / 256.0
        return ((worldX - tile.x) / tilesAcross).toFloat() to ((worldY - tile.y) / tilesAcross).toFloat()
    }
    fun radiusPixels(latitude: Double, radiusMeters: Float, zoom: Int): Float =
        (radiusMeters / (kotlin.math.cos(Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))).coerceAtLeast(0.08) *
            2 * Math.PI * 6_378_137 / (256 * (1 shl zoom)))).toFloat()
}
