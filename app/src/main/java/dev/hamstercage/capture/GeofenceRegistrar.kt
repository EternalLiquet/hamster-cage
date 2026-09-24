package dev.hamstercage.capture

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dev.hamstercage.offices.OfficeRegistrationIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Replaces the desired set after office edits, startup, or permission changes. */
class GeofenceRegistrar(
    context: Context,
    private val client: GeofencingClient = LocationServices.getGeofencingClient(context.applicationContext),
    private val operations: FenceOperations = PlayServicesFenceOperations(context.applicationContext, client),
) {
    private val application = context.applicationContext
    private val lock = Mutex()
    private var lastApplied: List<OfficeRegistrationIntent>? = null
    private var lastAppliedGeneration: Long? = null

    suspend fun synchronize(desired: List<OfficeRegistrationIntent>, ready: Boolean, force: Boolean = false,
        generation: Long = 0L): CaptureStatus = lock.withLock {
        require(generation >= 0)
        val sorted = desired.sortedBy { it.officeId }
        // Recheck at the platform call even if the UI most recently reported ready;
        // permission can be revoked between its state read and this reconciliation.
        val permitted = application.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || application.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (!ready || !permitted) {
            // Revocation usually clears platform monitoring, but a failed removal
            // leaves the platform's actual state unknown. Retry on the next sync.
            try { removeCurrentAndPrevious(generation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                return@withLock CaptureStatus(RegistrationStatus.FAILED).also {
                    CaptureHealth.registration(it.registration)
                }
            }
            lastApplied = null
            lastAppliedGeneration = null
            return@withLock CaptureStatus(RegistrationStatus.NEEDS_SETUP).also {
                CaptureHealth.registration(it.registration)
            }
        }
        if (!force && sorted == lastApplied && generation == lastAppliedGeneration) return@withLock CaptureStatus(
            if (sorted.isEmpty()) RegistrationStatus.NO_OFFICES else RegistrationStatus.ACTIVE, sorted.size)
        CaptureHealth.registration(RegistrationStatus.REGISTERING)
        try {
            removeCurrentAndPrevious(generation)
            // Initial trigger zero avoids treating "already inside at registration" as
            // an observed entry. The first credited entry must be a real transition.
            sorted.forEach { office ->
                val boundary = Geofence.Builder()
                    .setRequestId(office.officeId)
                    .setCircularRegion(office.latitude, office.longitude, office.radiusMeters)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .build()
                operations.add(GeofencingRequest.Builder().setInitialTrigger(0)
                    .addGeofence(boundary).build(), pendingIntent(generation))
            }
            lastApplied = sorted
            lastAppliedGeneration = generation
            CaptureStatus(if (sorted.isEmpty()) RegistrationStatus.NO_OFFICES else RegistrationStatus.ACTIVE, sorted.size).also {
                CaptureHealth.registration(it.registration, it.registeredCount)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            lastApplied = null
            lastAppliedGeneration = null
            // A partially added set is not a healthy registration. Clear it if possible.
            try { removeCurrentAndPrevious(generation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Unit }
            CaptureStatus(RegistrationStatus.FAILED).also { CaptureHealth.registration(it.registration) }
        }
    }

    private suspend fun removeCurrentAndPrevious(generation: Long) {
        // A failed removal at an earlier generation must still be retried after
        // another history delete. The journal generation is durable, while the
        // registrar's in-memory last-applied generation is not.
        var prior = 0L
        while (prior < generation) {
            operations.remove(pendingIntent(prior))
            prior++
        }
        operations.remove(pendingIntent(generation))
    }

    internal fun pendingIntent(generation: Long = 0L): PendingIntent {
        require(generation >= 0)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        // Google Play services must fill transition extras on S+, hence mutable. The
        // intent still names one non-exported receiver in this package.
        val intent = Intent(application, GeofenceTransitionReceiver::class.java)
            .setPackage(application.packageName).setAction(action(generation))
        return PendingIntent.getBroadcast(application, 0, intent, flags)
    }

    companion object {
        const val ACTION = "dev.hamstercage.action.GEOFENCE_TRANSITION"
        fun action(generation: Long): String = if (generation == 0L) ACTION else "$ACTION.$generation"
    }
}

/** Narrow platform seam for deterministic failed-remove coverage. */
interface FenceOperations {
    suspend fun remove(intent: PendingIntent)
    suspend fun add(request: GeofencingRequest, intent: PendingIntent)
}

private class PlayServicesFenceOperations(private val context: Context, private val client: GeofencingClient) : FenceOperations {
    override suspend fun remove(intent: PendingIntent) { client.removeGeofences(intent).await() }
    override suspend fun add(request: GeofencingRequest, intent: PendingIntent) {
        // Permission can disappear after the registrar's readiness check.
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            throw SecurityException("Location permission changed before registration")
        }
        try { client.addGeofences(request, intent).await() }
        catch (denied: SecurityException) { throw denied }
    }
}
