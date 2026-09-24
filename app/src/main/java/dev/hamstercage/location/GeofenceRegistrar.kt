package dev.hamstercage.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import dev.hamstercage.data.HamsterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class GeofenceRegistrar(private val context: Context, private val repository: HamsterRepository) {
    @SuppressLint("MissingPermission") // Checked immediately before add; revoked-in-flight permission is caught.
    suspend fun register(force: Boolean = false) = registrationMutex.withLock {
        val health = LocationPermissions.health(context)
        if (!health.canTrack) {
            repository.recordRegistration(0, null, health.message)
            return@withLock
        }
        val offices = repository.officeList().filter { it.enabled }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(offices.sortedBy { it.id }.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}"
        }.toByteArray()).joinToString("") { "%02x".format(it) }
        val state = repository.registrationState()
        if (!force && state.first == fingerprint && state.third == null) return@withLock
        val client = LocationServices.getGeofencingClient(context)
        try {
            // Persist invalidation BEFORE removing registrations. Process death/cancellation must
            // never leave a stale healthy fingerprint that causes future recovery to be skipped.
            repository.recordRegistration(0, null, "Office registration is being refreshed.")
            // Replacing all zones avoids orphaned registrations after disabling/changing an office.
            client.removeGeofences(pendingIntent(context)).await()
            if (offices.isNotEmpty()) {
                val request = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(offices.map { office ->
                        Geofence.Builder().setRequestId(office.id)
                            .setCircularRegion(office.latitude, office.longitude, office.radiusMeters)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setNotificationResponsiveness(120_000)
                            .build()
                    }).build()
                client.addGeofences(request, pendingIntent(context)).await()
            }
            repository.recordRegistration(offices.size, fingerprint, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            repository.recordRegistration(0, null, "Location permission changed. Restore precise and background access, then retry.")
        } catch (error: ApiException) {
            val explanation = when (error.statusCode) {
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> "Office detection is temporarily unavailable. Check device location and retry."
                GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> "The device geofence limit was reached. Disable unused offices."
                else -> "Office registration failed (code ${error.statusCode}). Reopen the app to retry."
            }
            repository.recordRegistration(0, null, explanation)
        } catch (_: Exception) {
            repository.recordRegistration(0, null, "Office registration failed. Reopen the app to retry.")
        }
    }
    companion object {
        internal const val ACTION = "dev.hamstercage.GEOFENCE_TRANSITION"
        private val registrationMutex = Mutex()
        internal fun pendingIntent(context: Context): PendingIntent {
            // Play services fills transition extras: immutable PendingIntents cannot carry these.
            // Explicit component + nonexported receiver confine this necessary mutable capability.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(context, GeofenceReceiver::class.java).setAction(ACTION)
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }
    }
}
