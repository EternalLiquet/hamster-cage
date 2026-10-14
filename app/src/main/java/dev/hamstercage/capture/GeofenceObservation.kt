package dev.hamstercage.capture

import android.location.Location
import androidx.core.location.LocationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dev.hamstercage.data.FixMetadata
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.security.MessageDigest
import java.time.Instant

/** Validates the platform payload before it crosses the immutable source-fact boundary. */
internal object GeofenceObservation {
    fun parse(event: GeofencingEvent?, receivedAt: Instant, receiptElapsedNanos: Long? = null): List<RecordedEvent> {
        require(event != null && !event.hasError()) { "Invalid geofence delivery." }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Transition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> Transition.EXIT
            else -> throw IllegalArgumentException("Unsupported geofence transition.")
        }
        val triggers = event.triggeringGeofences?.take(MAX_OFFICES + 1).orEmpty()
        val ids = triggers.map { it.requestId }.distinct()
        require(triggers.size <= MAX_OFFICES && ids.isNotEmpty() && ids.all { it.isNotBlank() && it.length <= 200 }) {
            "Invalid geofence IDs."
        }
        return records(ids, transition, event.triggeringLocation, receivedAt, receiptElapsedNanos)
    }

    internal fun records(
        officeIds: List<String>, transition: Transition, location: Location?, receivedAt: Instant,
        receiptElapsedNanos: Long? = null,
    ): List<RecordedEvent> {
        require(officeIds.isNotEmpty() && officeIds.size <= MAX_OFFICES &&
            officeIds.all { it.isNotBlank() && it.length <= 200 })
        val observedAt = location?.time?.takeIf { it > 0 && it <= receivedAt.toEpochMilli() }?.let(Instant::ofEpochMilli)
        val accuracy = location?.takeIf { it.hasAccuracy() && it.accuracy.isFinite() &&
            it.accuracy in 0f..10000f }?.accuracy
        val eventAt = observedAt ?: receivedAt
        val fix = location?.let {
            fixMetadata(receiptElapsedNanos, it.elapsedRealtimeNanos, it.provider, LocationCompat.isMock(it))
        }
        return officeIds.distinct().map { officeId ->
            // A replay of one Play Services observation retains its first receipt in Room.
            // Without a valid platform timestamp, distinct deliveries cannot be reliably
            // correlated, so use the honest receipt time and let the engine reconcile them.
            val id = digest("$officeId|${transition.name}|${eventAt.toEpochMilli()}")
            RecordedEvent(RawEvent(id, officeId, transition, eventAt), receivedAt, observedAt,
                accuracyMeters = accuracy, fix = fix)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_OFFICES = 100
}

/**
 * Fix metadata from values sampled at receipt. Age uses only the monotonic elapsed-realtime clock:
 * it is null when the receipt sample or the fix timestamp is missing (0) or the fix appears to be
 * from after receipt. Provider names outside the platform's standard set are recorded as "other".
 */
internal fun fixMetadata(receiptElapsedNanos: Long?, fixElapsedNanos: Long, provider: String?, mock: Boolean): FixMetadata {
    val age = if (receiptElapsedNanos == null || receiptElapsedNanos <= 0 || fixElapsedNanos <= 0 ||
        fixElapsedNanos > receiptElapsedNanos) null else (receiptElapsedNanos - fixElapsedNanos) / 1_000_000
    val name = provider?.takeIf { it.isNotBlank() }?.let { if (it in standardProviders) it else "other" }
    return FixMetadata(age, name, mock)
}

private val standardProviders = setOf("fused", "gps", "network", "passive")
