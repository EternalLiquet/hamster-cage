package dev.hamstercage.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import dev.hamstercage.data.HamsterRepository
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Explicit, non-exported Play Services callback. No UI, coordinates, or payload logging. */
class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val application = context.applicationContext
        val receivedAt = Instant.ofEpochMilli(System.currentTimeMillis())
        worker.launch {
            try {
                withTimeout(9_000) {
                    require(intent?.action == GeofenceRegistrar.ACTION) { "Unexpected capture action." }
                    val observations = GeofenceObservation.parse(GeofencingEvent.fromIntent(intent), receivedAt)
                    HamsterRepository.get(application).appendRawEvents(observations)
                    CaptureHealthStore.setDeliveryFailure(application, false)
                }
                CaptureHealth.deliverySucceeded()
            } catch (_: Exception) {
                // Do not expose payloads, coordinates, identifiers, or exception text.
                try { CaptureHealthStore.setDeliveryFailure(application, true) }
                catch (_: Exception) { Unit }
                CaptureHealth.deliveryFailed()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
