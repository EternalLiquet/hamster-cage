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
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.RegistrationAttempt
import dev.hamstercage.data.RegistrationCycle
import dev.hamstercage.data.RegistrationKind
import dev.hamstercage.data.RegistrationLog
import dev.hamstercage.data.RegistrationOutcome
import dev.hamstercage.data.RegistrationReason
import dev.hamstercage.data.RegistrationStep
import dev.hamstercage.data.registrationErrorCode
import dev.hamstercage.offices.OfficeRegistrationIntent
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Replaces the desired set after office edits, startup, or permission changes. */
class GeofenceRegistrar(
    context: Context,
    private val client: GeofencingClient = LocationServices.getGeofencingClient(context.applicationContext),
    private val operations: FenceOperations = PlayServicesFenceOperations(context.applicationContext, client),
    private val retirement: FenceRetirementLedger = PrivacyResetStore.fenceRetirement(context.applicationContext),
    private val log: RegistrationLog = HamsterRepository.get(context.applicationContext).registrationLog,
    private val clock: () -> Instant = Instant::now,
) {
    private val application = context.applicationContext
    private val lock = Mutex()
    private var lastApplied: List<OfficeRegistrationIntent>? = null
    private var lastAppliedGeneration: Long? = null
    private var earlierPendingResolved = false

    /** Read only: a shutdown cannot certify a fence set after an office edit awaiting reconciliation. */
    suspend fun appliedSetMatches(desired: List<OfficeRegistrationIntent>, generation: Long): Boolean = lock.withLock {
        val applied = lastApplied ?: return@withLock false
        applied.isNotEmpty() && lastAppliedGeneration == generation && applied == desired.sortedBy { it.officeId }
    }

    /**
     * [reason] and [versions] only describe the attempt in diagnostics. Logging never changes what
     * is registered: a diagnostic write failure is ignored and registration proceeds as before.
     */
    suspend fun synchronize(desired: List<OfficeRegistrationIntent>, ready: Boolean, force: Boolean = false,
        generation: Long = 0L, reason: RegistrationReason = RegistrationReason.UNKNOWN,
        versions: Map<String, Long> = emptyMap()): CaptureStatus = lock.withLock {
        require(generation >= 0)
        resolveEarlierPending()
        val sorted = desired.sortedBy { it.officeId }
        // Recheck at the platform call even if the UI most recently reported ready;
        // permission can be revoked between its state read and this reconciliation.
        val permitted = application.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || application.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (!ready || !permitted) {
            // Only a removal that can invalidate fences this process applied, or an explicitly
            // forced check, is recorded. Repeated not-ready checks would otherwise flood the log.
            val cycle = if (lastApplied != null || force) startCycle(RegistrationKind.REMOVE, reason, generation, 0) else null
            // Revocation usually clears platform monitoring, but a failed removal
            // leaves the platform's actual state unknown. Retry on the next sync.
            try { removeCurrentAndPrevious(generation) }
            catch (cancelled: CancellationException) { cycle?.let { finishInterrupted(it) }; throw cancelled }
            catch (failure: Exception) {
                cycle?.let { finishCycle(it, RegistrationOutcome.FAILED, RegistrationStep.FAILED, null, failure) }
                return@withLock CaptureStatus(RegistrationStatus.FAILED).also {
                    CaptureHealth.registration(it.registration)
                }
            }
            cycle?.let { finishCycle(it, RegistrationOutcome.SUCCEEDED, RegistrationStep.REMOVED, null, null) }
            lastApplied = null
            lastAppliedGeneration = null
            return@withLock CaptureStatus(RegistrationStatus.NEEDS_SETUP).also {
                CaptureHealth.registration(it.registration)
            }
        }
        if (!force && sorted == lastApplied && generation == lastAppliedGeneration) return@withLock CaptureStatus(
            if (sorted.isEmpty()) RegistrationStatus.NO_OFFICES else RegistrationStatus.ACTIVE, sorted.size)
        CaptureHealth.registration(RegistrationStatus.REGISTERING)
        val cycle = startCycle(RegistrationKind.SYNC, reason, generation, sorted.size)
        var removal: RegistrationStep? = null
        var finished = false
        try {
            try { removeCurrentAndPrevious(generation); removal = RegistrationStep.REMOVED }
            catch (failure: Exception) {
                if (failure !is CancellationException) removal = RegistrationStep.FAILED
                throw failure
            }
            // Initial trigger zero avoids treating "already inside at registration" as
            // an observed entry. The first credited entry must be a real transition.
            sorted.forEach { office ->
                addLogged(cycle, office, 0, generation, versions)
            }
            lastApplied = sorted
            lastAppliedGeneration = generation
            finished = true
            finishCycle(cycle, RegistrationOutcome.SUCCEEDED, removal, null, null)
            CaptureStatus(if (sorted.isEmpty()) RegistrationStatus.NO_OFFICES else RegistrationStatus.ACTIVE, sorted.size).also {
                CaptureHealth.registration(it.registration, it.registeredCount)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            lastApplied = null
            lastAppliedGeneration = null
            // A partially added set is not a healthy registration. Clear it if possible. The cycle's
            // cleanup step records whether earlier successful adds in this cycle were removed.
            val cleanup = try { removeCurrentAndPrevious(generation); RegistrationStep.REMOVED }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RegistrationStep.FAILED }
            finished = true
            finishCycle(cycle, RegistrationOutcome.FAILED, removal, cleanup, failure)
            CaptureStatus(RegistrationStatus.FAILED).also { CaptureHealth.registration(it.registration) }
        } finally {
            if (!finished) finishInterrupted(cycle)
        }
    }

    /**
     * Whether this generation's registration token still exists in the system. The token
     * outlives a reclaimed app process but not a force-stop, reboot, update or data clear,
     * after which Play services has also discarded the geofences. Null if it cannot be read.
     */
    fun registrationTokenPresent(generation: Long): Boolean? = try {
        val flags = PendingIntent.FLAG_NO_CREATE or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(application, 0, Intent(application, GeofenceTransitionReceiver::class.java)
            .setPackage(application.packageName).setAction(action(generation)), flags) != null
    } catch (_: RuntimeException) { null }

    /**
     * After a phantom EXIT is rejected the platform may still treat the device as outside and
     * report no EXIT for a real departure. Re-adding the same request IDs with an initial
     * ENTER trigger asks Play services to report ENTER only if the device is inside now, which
     * restores its inside state. It reports nothing if the device is outside, and it never
     * creates an earlier arrival: any ENTER carries its own observation time.
     */
    suspend fun resyncInside(offices: List<OfficeRegistrationIntent>, generation: Long,
        versions: Map<String, Long> = emptyMap()): Boolean = lock.withLock {
        require(generation >= 0)
        resolveEarlierPending()
        if (offices.isEmpty() || lastApplied == null || lastAppliedGeneration != generation) return@withLock false
        val active = lastApplied.orEmpty().associateBy { it.officeId }
        val targets = offices.filter { active[it.officeId] == it }
        if (targets.isEmpty()) return@withLock false
        val cycle = startCycle(RegistrationKind.RESYNC, RegistrationReason.REJECTED_EXIT_RESYNC, generation, targets.size)
        var finished = false
        try {
            targets.forEach { office ->
                addLogged(cycle, office, GeofencingRequest.INITIAL_TRIGGER_ENTER, generation, versions)
            }
            finished = true
            finishCycle(cycle, RegistrationOutcome.SUCCEEDED, null, null, null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Behaviour is unchanged: the caller still sees the failure. No cleanup is attempted.
            finished = true
            finishCycle(cycle, RegistrationOutcome.FAILED, null, null, failure)
            throw failure
        } finally {
            if (!finished) finishInterrupted(cycle)
        }
        true
    }

    /** Persists the attempt before calling Play services; a lost or cancelled call is INTERRUPTED, never success. */
    private suspend fun addLogged(cycleId: String, office: OfficeRegistrationIntent, initialTrigger: Int,
        generation: Long, versions: Map<String, Long>) {
        val id = UUID.randomUUID().toString()
        record { log.attemptStarted(RegistrationAttempt(id, cycleId, office.officeId, versions[office.officeId],
            initialTrigger, clock(), null, RegistrationOutcome.PENDING, null)) }
        var finished = false
        try {
            operations.add(GeofencingRequest.Builder().setInitialTrigger(initialTrigger)
                .addGeofence(fence(office)).build(), pendingIntent(generation))
            finished = true
            record { log.attemptFinished(id, clock(), RegistrationOutcome.SUCCEEDED, null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            finished = true
            record { log.attemptFinished(id, clock(), RegistrationOutcome.FAILED, registrationErrorCode(failure)) }
            throw failure
        } finally {
            if (!finished) withContext(NonCancellable) {
                record { log.attemptFinished(id, clock(), RegistrationOutcome.INTERRUPTED, null) }
            }
        }
    }

    private suspend fun startCycle(kind: RegistrationKind, reason: RegistrationReason, generation: Long, planned: Int): String {
        val id = UUID.randomUUID().toString()
        record { log.cycleStarted(RegistrationCycle(id, kind, reason, generation, planned, clock(), null,
            RegistrationOutcome.PENDING, null, null, null)) }
        return id
    }

    private suspend fun finishCycle(id: String, outcome: RegistrationOutcome, removal: RegistrationStep?,
        cleanup: RegistrationStep?, failure: Throwable?) {
        record { log.cycleFinished(id, clock(), outcome, removal, cleanup, failure?.let(::registrationErrorCode)) }
    }

    private suspend fun finishInterrupted(id: String) = withContext(NonCancellable) {
        record { log.cycleFinished(id, clock(), RegistrationOutcome.INTERRUPTED, null, null, null) }
    }

    /** Rows still PENDING from an earlier process lost their callbacks; they are interrupted, not successful. */
    private suspend fun resolveEarlierPending() {
        if (earlierPendingResolved) return
        record { log.markInterrupted() }
        earlierPendingResolved = true
    }

    /** Diagnostics are best effort and never alter registration control flow. */
    private suspend fun record(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Unit }
    }

    private fun fence(office: OfficeRegistrationIntent): Geofence = Geofence.Builder()
        .setRequestId(office.officeId)
        .setCircularRegion(office.latitude, office.longitude, office.radiusMeters)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .build()

    private suspend fun removeCurrentAndPrevious(generation: Long) {
        // The durable cursor bounds the healthy path while retaining every
        // failed older removal for retry, including after process restart.
        var prior = retirement.retiredThrough() + 1
        while (prior < generation) {
            operations.remove(pendingIntent(prior))
            retirement.markRetiredThrough(prior)
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

/** Persisted after each successful prior-generation removal, never for the live generation. */
interface FenceRetirementLedger {
    suspend fun retiredThrough(): Long
    suspend fun markRetiredThrough(generation: Long)
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
