package dev.hamstercage.capture

import android.content.Context
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.offices.registrationIntents
import dev.hamstercage.location.LocationPermissions
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-scoped reconciliation: restart and office edits both reapply desired fences. */
class CaptureController private constructor(context: Context) {
    private data class Prerequisites(val ready: Boolean, val revision: Long)
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readiness = MutableStateFlow<Prerequisites?>(null)
    private val repository = HamsterRepository.get(application)
    private val registrar = GeofenceRegistrar(application)
    private val reconcileLock = Mutex()
    private var processStarted = false

    init {
        scope.launch {
            CaptureHealthStore.deliveryFailure(application).collect { failed ->
                if (failed) CaptureHealth.deliveryFailed() else CaptureHealth.deliverySucceeded()
            }
        }
        scope.launch {
            var lastRevision = -1L
            combine(repository.state, readiness) { state, prerequisites -> state to prerequisites }
                .collect { (state, prerequisites) ->
                    if (prerequisites == null || state == StorageState.Loading) return@collect
                    val force = prerequisites.revision != lastRevision
                    lastRevision = prerequisites.revision
                    try { reconcile(state, prerequisites.ready, force) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { CaptureHealth.registration(RegistrationStatus.FAILED) }
                }
        }
    }

    /** Used by a bounded system receiver; its coroutine remains attached to goAsync. */
    suspend fun refreshFromSystem() {
        val ready = LocationPermissions.read(application).prerequisitesReady
        reconcile(repository.state.first(), ready, force = true)
    }

    private suspend fun reconcile(state: StorageState, ready: Boolean, force: Boolean) = reconcileLock.withLock {
        val now = Instant.now()
        val zone = (state as? StorageState.Ready)?.snapshot?.policy?.zoneId ?: ZoneId.systemDefault()
        if (!processStarted) {
            CoverageStore.change(application) { it.processStarted(now, zone) }
            processStarted = true
        }
        val status = when (state) {
            is StorageState.Ready -> registrar.synchronize(state.snapshot.registrationIntents(), ready, force)
            StorageState.Unavailable -> {
                registrar.synchronize(emptyList(), false)
                CaptureHealth.registration(RegistrationStatus.FAILED)
                CaptureStatus(RegistrationStatus.FAILED)
            }
            StorageState.Loading -> return@withLock
        }
        CoverageStore.change(application) { ledger ->
            if (status.registration == RegistrationStatus.ACTIVE || status.registration == RegistrationStatus.NO_OFFICES)
                ledger.registrationSucceeded(now, zone, status.registration == RegistrationStatus.ACTIVE)
            else ledger.outage(now, zone).copy(registration = status.registration)
        }
    }

    /** Call after each foreground return so platform-cleared fences are restored. */
    fun updatePermissionReady(value: Boolean) {
        readiness.value = Prerequisites(value, (readiness.value?.revision ?: 0L) + 1L)
    }

    companion object {
        @Volatile private var instance: CaptureController? = null
        fun get(context: Context): CaptureController = instance ?: synchronized(this) {
            instance ?: CaptureController(context).also { instance = it }
        }
    }
}
