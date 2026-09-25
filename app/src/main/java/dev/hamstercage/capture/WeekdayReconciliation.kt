package dev.hamstercage.capture

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Transition
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.offices.OfficeLocationServices
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** The single periodic path is inexact; only work inside the policy window requests location. */
internal object WeekdayReconciliation {
    internal const val NAME = "weekday-office-state-reconciliation"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WeekdayReconciliationWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
}

internal fun insideReconciliationWindow(now: Instant, zone: ZoneId): Boolean {
    val local = now.atZone(zone)
    return local.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY &&
        local.hour in 7..18
}

internal fun canRequestReconciliationFix(now: Instant, zone: ZoneId, enabled: Boolean,
    setup: LocationSetup, coverage: CoverageLedger, privacyIdle: Boolean): Boolean =
    insideReconciliationWindow(now, zone) && enabled && privacyIdle && setup.prerequisitesReady &&
        coverage.registration == RegistrationStatus.ACTIVE && coverage.outageStartedAt == null &&
        coverage.policyZoneId == zone

/** Current-state evidence only. ABSENCE leaves an old, unobserved span for review. */
internal fun reconciliationFacts(snapshot: AppSnapshot, coverage: CoverageLedger, now: Instant,
    fixAt: Instant, officeId: String?, accuracyMeters: Float? = null): List<RecordedEvent> {
    val open = snapshot.derive(now).sessions.filter { it.isOpen && it.manualSessionId == null }
    val absent = open.filter { it.officeId != officeId }.map { it.officeId }.distinct()
    val facts = absent.map { id -> RecordedEvent(RawEvent(HamsterRepository.newId(), id,
        Transition.ABSENCE, now), now, fixAt, "BACKGROUND_LOCATION_RECONCILIATION", accuracyMeters) }.toMutableList()
    if (officeId != null && canOpenFromObservation(snapshot, officeId, coverage, now, now) !=
        ReconcileOutcome.ALREADY_PRESENT) {
        facts += RecordedEvent(RawEvent(HamsterRepository.newId(), officeId,
            Transition.PRESENCE, now), now, fixAt, "BACKGROUND_LOCATION_RECONCILIATION", accuracyMeters)
    }
    return facts
}

/** A one-shot fix per eligible run. Never retains coordinates or starts a service. */
class WeekdayReconciliationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = HamsterRepository.get(context)
        val initial = repository.state.first() as? StorageState.Ready ?: return Result.success()
        if (!insideReconciliationWindow(Instant.now(), initial.snapshot.policy.zoneId)) return Result.success()
        if (!MonitoringStore.read(context).enabled || PrivacyResetStore.read(context) !is PrivacyResetState.Idle)
            return Result.success()
        val setup = LocationPermissions.read(context)
        if (!setup.prerequisitesReady) { MonitoringStore.record(context, "Location permission or service unavailable"); return Result.success() }
        try { CaptureController.get(context).refreshIfNeededForProcess() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MonitoringStore.record(context, "Office recovery unavailable"); return Result.success() }
        val coverage = CoverageStore.state(context).first()
        if (!canRequestReconciliationFix(Instant.now(), initial.snapshot.policy.zoneId, true, setup,
                coverage, true)) {
            MonitoringStore.record(context, "Office registration unavailable"); return Result.success()
        }
        val requestedAt = Instant.now()
        val fix = try { OfficeLocationServices(context).captureFix(freshAfterRequest = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MonitoringStore.record(context, "Location check unavailable; open app to retry"); return Result.success() }
        try { withTimeout(8_000) { CaptureWriteGate.mutex.withLock {
            val now = Instant.now()
            val state = repository.state.first() as? StorageState.Ready ?: return@withLock
            val snapshot = state.snapshot
            val currentSetup = LocationPermissions.read(context)
            if (!currentSetup.prerequisitesReady) {
                MonitoringStore.record(context, "Location permission or service unavailable"); return@withLock
            }
            val currentCoverage = CoverageStore.state(context).first()
            if (!canRequestReconciliationFix(now, snapshot.policy.zoneId, MonitoringStore.read(context).enabled,
                    currentSetup, currentCoverage, PrivacyResetStore.read(context) is PrivacyResetState.Idle)) {
                MonitoringStore.record(context, "Office registration unavailable"); return@withLock
            }
            val age = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
            val wallAge = now.toEpochMilli() - fix.time
            if (fix.time < requestedAt.toEpochMilli() || age !in 0..30_000_000_000L ||
                wallAge !in 0..30_000L) { MonitoringStore.record(context, "Stale location; retry later"); return@withLock }
            if (!fix.hasAccuracy()) { MonitoringStore.record(context, "Location not precise enough"); return@withLock }
            if (snapshot.events.any { it.at > requestedAt }) {
                MonitoringStore.record(context, "Newer boundary event; retry later"); return@withLock
            }
            val (outcome, office) = decidePresence(snapshot.offices, fix.latitude, fix.longitude,
                fix.accuracy, wallAge)
            if (outcome !in setOf(ReconcileOutcome.CONFIRMED, ReconcileOutcome.OUTSIDE)) {
                MonitoringStore.record(context, when (outcome) {
                    ReconcileOutcome.UNCERTAIN_BOUNDARY, ReconcileOutcome.OVERLAPPING_OFFICES -> "Office boundary uncertain"
                    else -> "Location not precise enough"
                }); return@withLock
            }
            val events = reconciliationFacts(snapshot, currentCoverage, now, Instant.ofEpochMilli(fix.time), office?.id, fix.accuracy)
            if (events.isNotEmpty()) repository.appendRawEvents(events)
            CoverageStore.change(context) { it.observed(now) }
            MonitoringStore.record(context, if (office == null) "Outside offices" else "Inside office", now)
        } } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MonitoringStore.record(context, "Check unavailable; open app to retry") }
        return Result.success()
    }
}
