package dev.hamstercage.capture

import android.location.Location
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import java.security.MessageDigest
import java.time.Instant

/** Validates the platform payload before it crosses the immutable source-fact boundary. */
internal object GeofenceObservation {
    fun parse(event: GeofencingEvent?, receivedAt: Instant): List<RecordedEvent> {
        require(event != null && !event.hasError()) { "Invalid geofence delivery." }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> Transition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> Transition.EXIT
            else -> throw IllegalArgumentException("Unsupported geofence transition.")
        }
        val ids = event.triggeringGeofences?.map { it.requestId }?.distinct().orEmpty()
        require(ids.isNotEmpty() && ids.all { it.isNotBlank() && it.length <= 200 }) { "Invalid geofence IDs." }
        return records(ids, transition, event.triggeringLocation, receivedAt)
    }

    internal fun records(
        officeIds: List<String>, transition: Transition, location: Location?, receivedAt: Instant,
    ): List<RecordedEvent> {
        require(officeIds.isNotEmpty() && officeIds.all { it.isNotBlank() && it.length <= 200 })
        val observedAt = location?.time?.takeIf { it > 0 && it <= receivedAt.toEpochMilli() }?.let(Instant::ofEpochMilli)
        val eventAt = observedAt ?: receivedAt
        return officeIds.distinct().map { officeId ->
            // A replay of one Play Services observation retains its first receipt in Room.
            // Without a valid platform timestamp, distinct deliveries cannot be reliably
            // correlated, so use the honest receipt time and let the engine reconcile them.
            val id = digest("$officeId|${transition.name}|${eventAt.toEpochMilli()}")
            RecordedEvent(RawEvent(id, officeId, transition, eventAt), receivedAt, observedAt)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
