package dev.hamstercage.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Explicit, non-exported Play Services callback. No UI, coordinates, or payload logging. */
class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val application = context.applicationContext
        val receivedAt = Instant.ofEpochMilli(System.currentTimeMillis())
        worker.launch {
            var factsStored = false
            try {
                withCaptureGateTimeout(CaptureWriteGate.mutex, 6_000, action = {
                        val reset = PrivacyResetStore.read(application)
                        if (reset !is PrivacyResetState.Idle) {
                            CaptureHealth.registration(RegistrationStatus.FAILED)
                            return@withCaptureGateTimeout
                        }
                        if (!MonitoringStore.read(application).enabled) return@withCaptureGateTimeout
                        // Old queued callbacks cannot recreate facts after deletion, including
                        // deliveries without a trustworthy location-fix timestamp.
                        if (intent?.action != GeofenceRegistrar.action(reset.generation)) return@withCaptureGateTimeout
                        val observations = GeofenceObservation.parse(GeofencingEvent.fromIntent(intent), receivedAt)
                        val repository = HamsterRepository.get(application)
                        val inserted = repository.appendRawEvents(observations)
                        factsStored = true
                        CoverageStore.change(application) { ledger ->
                            ledger.observed(observations.maxOf { it.event.at })
                        }
                        val snapshot = (repository.state.first() as? StorageState.Ready)?.snapshot
                        val delivery = snapshot?.let { AdaptiveConfirmation.selectDelivery(observations,
                            inserted, it) }
                        CaptureHealthStore.setDeliveryFailure(application, false)
                        CaptureHealth.deliverySucceeded()
                        if (delivery != null) try {
                            val officeIds = delivery.events.map { it.event.officeId }
                            val versions = repository.officeVersions(officeIds)
                            if (versions.size == officeIds.size) AdaptiveConfirmation.schedule(application,
                                delivery.representative, reset.generation,
                                AdaptiveConfirmation.fingerprint(versions), officeIds)
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { recordConfirmationSchedulingFailure(application) }
                }, onFailure = {
                    if (factsStored) recordConfirmationSchedulingFailure(application)
                    else recordDeliveryFailure(application, receivedAt)
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal suspend fun recordConfirmationSchedulingFailure(context: Context) {
    try { MonitoringStore.record(context, "Boundary confirmation unavailable; later checks may recover") }
    catch (_: Exception) { Unit }
}

/** A timeout before obtaining the gate is still a lost observation, not a healthy registration. */
internal suspend fun withCaptureGateTimeout(gate: Mutex, timeoutMillis: Long,
    action: suspend () -> Unit, onFailure: suspend () -> Unit): Boolean {
    try {
        withTimeout(timeoutMillis) { gate.withLock { action() } }
        return true
    } catch (_: TimeoutCancellationException) {
        onFailure()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure()
    }
    return false
}

/** Sanitized, persistent uncertainty survives a later successful registration and process restart. */
internal suspend fun recordDeliveryFailure(context: Context, receivedAt: Instant) {
    CaptureHealth.registration(RegistrationStatus.FAILED)
    CaptureHealth.deliveryFailed()
    try { CaptureHealthStore.setDeliveryFailure(context, true) } catch (_: Exception) { Unit }
    try { CoverageStore.change(context) { it.unlocatedOutage(receivedAt) } } catch (_: Exception) { Unit }
}
