package dev.hamstercage.capture

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.offices.registrationIntents
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
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
import kotlinx.coroutines.sync.withLock

/** Process-scoped reconciliation: restart and office edits both reapply desired fences. */
class CaptureController private constructor(context: Context) {
    private data class Prerequisites(val ready: Boolean, val revision: Long)
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readiness = MutableStateFlow<Prerequisites?>(null)
    private val repository = HamsterRepository.get(application)
    private val registrar = GeofenceRegistrar(application)
    @Volatile private var processStarted = false

    init {
        scope.launch {
            CaptureHealthStore.deliveryFailure(application).collect { failed ->
                if (failed) CaptureHealth.deliveryFailed() else CaptureHealth.deliverySucceeded()
            }
        }
        scope.launch { MonitoringStore.state(application).collect(CaptureHealth::monitoring) }
        scope.launch {
            var lastRevision = -1L
            combine(repository.state, readiness, MonitoringStore.state(application)) { state, prerequisites, monitoring ->
                Triple(state, prerequisites, monitoring)
            }.collect { (state, prerequisites, monitoring) ->
                    if (prerequisites == null || state == StorageState.Loading) return@collect
                    val force = prerequisites.revision != lastRevision
                    lastRevision = prerequisites.revision
                    try { reconcile(state, prerequisites.ready, monitoring.enabled, force) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { CaptureHealth.registration(RegistrationStatus.FAILED) }
                }
        }
    }

    /** Used by a bounded system receiver; its coroutine remains attached to goAsync. */
    suspend fun refreshFromSystem(backgroundStart: Boolean = false) {
        val ready = LocationPermissions.read(application).prerequisitesReady
        reconcile(repository.state.first(), ready, MonitoringStore.read(application).enabled, force = true,
            backgroundStart = backgroundStart)
    }

    /**
     * Worker process starts must not trust a registration from a previous process without
     * evidence. A background job is itself evidence that the app was not force-stopped.
     */
    suspend fun refreshIfNeededForProcess() {
        if (!processStarted) refreshFromSystem(backgroundStart = true)
    }

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        MonitoringStore.setEnabled(application, enabled)
        refreshFromSystem()
    }

    private suspend fun reconcile(state: StorageState, ready: Boolean, enabled: Boolean, force: Boolean,
        backgroundStart: Boolean = false) = CaptureWriteGate.mutex.withLock {
        val reset = PrivacyResetStore.read(application)
        if (reset !is PrivacyResetState.Idle) {
            WeekdayReconciliation.cancel(application)
            AdaptiveConfirmation.cancelAll(application)
            CaptureHealth.registration(RegistrationStatus.FAILED)
            return@withLock
        }
        val now = Instant.now()
        val zone = (state as? StorageState.Ready)?.snapshot?.policy?.zoneId ?: ZoneId.systemDefault()
        val identity = processIdentity()
        if (!processStarted) {
            // Gathered before synchronize() recreates the token for this process.
            val ledger = CoverageStore.state(application).first()
            val continuity = processContinuity(
                tokenPresent = registrar.registrationTokenPresent(reset.generation),
                rebooted = ledger.rebootedSince(identity),
                packageReplaced = ledger.packageReplacedSince(identity),
                previousExit = previousExit(ledger.lastHealthyAt),
                forceStopReported = forceStopReported(),
                backgroundStart = backgroundStart,
                tokensCancelledOnStop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
            CoverageStore.change(application) { it.processStarted(now, zone, continuity) }
            processStarted = true
        }
        val status = when (state) {
            is StorageState.Ready -> registrar.synchronize(state.snapshot.registrationIntents(), ready && enabled, force,
                reset.generation)
            StorageState.Unavailable -> {
                registrar.synchronize(emptyList(), false, generation = reset.generation)
                CaptureHealth.registration(RegistrationStatus.FAILED)
                CaptureStatus(RegistrationStatus.FAILED)
            }
            StorageState.Loading -> return@withLock
        }
        if (!enabled && status.registration == RegistrationStatus.NEEDS_SETUP) {
            CaptureHealth.registration(RegistrationStatus.DISABLED)
        }
        CoverageStore.change(application) { ledger ->
            if (status.registration == RegistrationStatus.ACTIVE || status.registration == RegistrationStatus.NO_OFFICES)
                ledger.registrationSucceeded(now, zone, status.registration == RegistrationStatus.ACTIVE, identity)
            else ledger.outage(now, zone).copy(registration = if (!enabled &&
                status.registration == RegistrationStatus.NEEDS_SETUP) RegistrationStatus.DISABLED else status.registration)
        }
        if (enabled && status.registration == RegistrationStatus.ACTIVE && state is StorageState.Ready)
            updateReconciliationAfterObservation(application, state.snapshot, now)
        else {
            WeekdayReconciliation.cancel(application)
            AdaptiveConfirmation.cancelAll(application)
        }
    }

    /** Re-arms the platform's inside state for offices whose phantom EXIT was just rejected. */
    suspend fun resyncAfterRejectedExit(officeIds: List<String>): Boolean = CaptureWriteGate.mutex.withLock {
        val reset = PrivacyResetStore.read(application)
        if (reset !is PrivacyResetState.Idle || !MonitoringStore.read(application).enabled ||
            !LocationPermissions.read(application).prerequisitesReady) return@withLock false
        val state = repository.state.first() as? StorageState.Ready ?: return@withLock false
        val intents = state.snapshot.registrationIntents().filter { it.officeId in officeIds }
        registrar.resyncInside(intents, reset.generation)
    }

    private fun processIdentity(): ProcessIdentity {
        val boots = try { Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT) }
            catch (_: Exception) { null }
        val updated = try { application.packageManager.getPackageInfo(application.packageName, 0).lastUpdateTime }
            catch (_: Exception) { null }
        return ProcessIdentity(boots, updated)
    }

    /**
     * Android 11+ records why the previous process ended. Only a record written after the last
     * confirmed capture health describes the process that held it.
     */
    private fun previousExit(lastHealthyAt: Instant?): PreviousExit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PreviousExit.UNKNOWN
        return try {
            val manager = application.getSystemService(ActivityManager::class.java) ?: return PreviousExit.UNKNOWN
            val info = manager.getHistoricalProcessExitReasons(application.packageName, 0, 1).firstOrNull()
                ?: return PreviousExit.UNKNOWN
            if (lastHealthyAt != null && info.timestamp < lastHealthyAt.toEpochMilli()) return PreviousExit.UNKNOWN
            when (info.reason) {
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
                ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> PreviousExit.STOPPED_OR_CHANGED
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_EXIT_SELF,
                ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> PreviousExit.RECLAIMED
                else -> PreviousExit.UNKNOWN
            }
        } catch (_: RuntimeException) { PreviousExit.UNKNOWN }
    }

    /** Android 15+ reports whether this start followed a force-stop. */
    private fun forceStopReported(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return try {
            application.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessStartReasons(1)?.firstOrNull()?.wasForceStopped()
        } catch (_: RuntimeException) { null }
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
