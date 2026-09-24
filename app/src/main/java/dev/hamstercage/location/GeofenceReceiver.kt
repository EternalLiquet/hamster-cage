package dev.hamstercage.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.domain.Transition
import kotlinx.coroutines.*
import java.time.Instant

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceRegistrar.ACTION) return
        val received = Instant.now()
        val event = runCatching { GeofencingEvent.fromIntent(intent) }.getOrNull() ?: return
        val pending = goAsync()
        receiverScope.launch {
            try {
                withTimeout(8_000) {
                    val repository = HamsterRepository(context)
                    if (event.hasError()) {
                        repository.recordRegistration(0, null, "Office detection reported an error (code ${event.errorCode}). Reopen the app to recover.")
                        RecoveryWorker.enqueue(context)
                        return@withTimeout
                    }
                    val transition = when (event.geofenceTransition) {
                        Geofence.GEOFENCE_TRANSITION_ENTER -> Transition.ENTER
                        Geofence.GEOFENCE_TRANSITION_EXIT -> Transition.EXIT
                        else -> return@withTimeout
                    }
                    val ids = event.triggeringGeofences?.map { it.requestId }?.take(100).orEmpty()
                    // Location.time describes the fix, not the boundary crossing. Do not persist coordinates.
                    val fixTime = event.triggeringLocation?.time?.takeIf { it > 0 && it <= received.toEpochMilli() }?.let(Instant::ofEpochMilli)
                    repository.recordPlatformEvents(ids, transition, received, fixTime)
                }
            } catch (_: Exception) {
                // No exception payload logging: providers may include sensitive location details.
                // Surface failed ingestion on next app launch; cannot claim a transition was saved.
                context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("capture_failed", true).putLong("capture_failed_at", received.toEpochMilli()).commit()
            } finally {
                pending.finish()
            }
        }
    }
    companion object { private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
