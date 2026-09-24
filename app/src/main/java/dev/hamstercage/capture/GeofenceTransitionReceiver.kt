package dev.hamstercage.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
                    CaptureWriteGate.mutex.withLock {
                        val reset = PrivacyResetStore.read(application)
                        if (reset !is PrivacyResetState.Idle) {
                            CaptureHealth.registration(RegistrationStatus.FAILED)
                            return@withLock
                        }
                        // Old queued callbacks cannot recreate facts after deletion, including
                        // deliveries without a trustworthy location-fix timestamp.
                        if (intent?.action != GeofenceRegistrar.action(reset.generation)) return@withLock
                        try {
                            val observations = GeofenceObservation.parse(GeofencingEvent.fromIntent(intent), receivedAt)
                            HamsterRepository.get(application).appendRawEvents(observations)
                            CoverageStore.change(application) { ledger ->
                                ledger.observed(observations.maxOf { it.event.at })
                            }
                            CaptureHealthStore.setDeliveryFailure(application, false)
                            CaptureHealth.deliverySucceeded()
                        } catch (_: Exception) {
                            // Failure details and payloads never reach logs or UI.
                            try { CoverageStore.change(application) { it.unlocatedOutage(receivedAt) } }
                            catch (_: Exception) { Unit }
                            try { CaptureHealthStore.setDeliveryFailure(application, true) }
                            catch (_: Exception) { Unit }
                            CaptureHealth.deliveryFailed()
                        }
                    }
                }
            } catch (_: Exception) {
                // A reset/journal failure or timeout cannot be mistaken for healthy capture.
                CaptureHealth.registration(RegistrationStatus.FAILED)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
