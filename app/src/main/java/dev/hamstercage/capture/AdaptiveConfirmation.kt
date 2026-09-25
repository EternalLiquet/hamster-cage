package dev.hamstercage.capture

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.offices.OfficeLocationServices
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** One inexact, one-shot check after a boundary signal. Newer signals replace stale checks. */
internal object AdaptiveConfirmation {
    const val TAG = "office-boundary-confirmation"
    private const val DELAY_SECONDS = 45L
    private const val ID = "event-id"
    private const val OFFICE = "office-id"
    private const val GENERATION = "generation"
    private const val VERSION = "office-version"

    data class Candidate(val eventId: String, val officeId: String, val generation: Long, val officeVersion: Long)

    fun schedule(context: Context, event: RecordedEvent, generation: Long, officeVersion: Long) {
        val input = Data.Builder().putString(ID, event.event.id).putString(OFFICE, event.event.officeId)
            .putLong(GENERATION, generation).putLong(VERSION, officeVersion).build()
        val request = OneTimeWorkRequestBuilder<AdaptiveConfirmationWorker>()
            .setInputData(input).setInitialDelay(DELAY_SECONDS, TimeUnit.SECONDS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG:${event.event.officeId}",
            ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }

    fun candidate(data: Data): Candidate? {
        val id = data.getString(ID) ?: return null
        val office = data.getString(OFFICE) ?: return null
        val generation = data.getLong(GENERATION, -1)
        val version = data.getLong(VERSION, -1)
        return if (id.isBlank() || office.isBlank() || generation < 0 || version < 0) null
            else Candidate(id, office, generation, version)
    }
}

/** A newer observation supersedes the candidate, including one from another office. */
internal fun confirmableCandidate(events: List<RecordedEvent>, id: String, officeId: String): Boolean {
    val candidate = events.singleOrNull { it.event.id == id && it.event.officeId == officeId } ?: return false
    if (candidate.source != "PLAY_SERVICES_GEOFENCE") return false
    return events.none { it.event.id != id &&
        (it.event.at > candidate.event.at || it.event.at == candidate.event.at && it.receivedAt > candidate.receivedAt) }
}

internal fun canRequestAdaptiveFix(setup: LocationSetup, enabled: Boolean, privacyIdle: Boolean,
    registration: RegistrationStatus): Boolean = enabled && privacyIdle && setup.prerequisitesReady &&
    registration == RegistrationStatus.ACTIVE

internal fun adaptiveFacts(candidateOfficeId: String, confirmedOfficeId: String?,
    observedAt: Instant, receivedAt: Instant, accuracyMeters: Float,
    recoveryPresence: Boolean = false,
    newId: () -> String = HamsterRepository::newId): List<RecordedEvent> = buildList {
    if (confirmedOfficeId != candidateOfficeId) add(RecordedEvent(RawEvent(newId(), candidateOfficeId,
        Transition.ABSENCE, observedAt), receivedAt, observedAt, "ADAPTIVE_LOCATION_CONFIRMATION", accuracyMeters))
    if (confirmedOfficeId != null) add(RecordedEvent(RawEvent(newId(), confirmedOfficeId,
        Transition.PRESENCE, observedAt), receivedAt, observedAt,
        if (recoveryPresence) "ADAPTIVE_RECOVERY_CONFIRMATION" else "ADAPTIVE_LOCATION_CONFIRMATION", accuracyMeters))
}

/** An old visit spanning registration recovery cannot be confirmed retroactively. */
internal fun requiresAdaptiveRecoverySplit(snapshot: AppSnapshot, coverage: CoverageLedger,
    candidateId: String, officeId: String, now: Instant): Boolean {
    val old = snapshot.derive(now).sessions.firstOrNull { it.officeId == officeId &&
        candidateId in it.sourceEventIds && it.manualSessionId == null } ?: return false
    val start = old.start ?: return false
    val boundary = coverage.recoveryBoundaryAt ?: return true
    return start < boundary || snapshot.events.any { it.officeId != officeId &&
        it.at >= start && it.at <= now }
}

/** WorkManager may start later under Doze; a late fix only establishes state at its own time. */
class AdaptiveConfirmationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val (id, officeId, generation, officeVersion) = AdaptiveConfirmation.candidate(inputData) ?: return Result.success()
        val context = applicationContext
        val repository = HamsterRepository.get(context)
        val reset = PrivacyResetStore.read(context)
        if (reset !is PrivacyResetState.Idle || reset.generation != generation ||
            !MonitoringStore.read(context).enabled) return Result.success()
        val setup = LocationPermissions.read(context)
        if (setup.prerequisitesReady) {
            try { CaptureController.get(context).refreshIfNeededForProcess() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                MonitoringStore.record(context, "Office registration unavailable")
                return Result.success()
            }
        }
        val initialCoverage = CoverageStore.state(context).first()
        if (!canRequestAdaptiveFix(setup, true, true, initialCoverage.registration) ||
            initialCoverage.outageStartedAt != null) {
            MonitoringStore.record(context, if (!setup.prerequisitesReady) setup.status
                else "Office registration unavailable")
            return Result.success()
        }
        val initial = repository.state.first() as? StorageState.Ready ?: return Result.success()
        if (initial.snapshot.offices.none { it.id == officeId && it.enabled && it.countsTowardAttendance } ||
            repository.officeVersion(officeId) != officeVersion ||
            !confirmableCandidate(initial.snapshot.eventEvidence, id, officeId)) return Result.success()
        val requestedAt = Instant.now()
        val fix = try { OfficeLocationServices(context).captureFix(freshAfterRequest = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            MonitoringStore.record(context, "Boundary check unavailable; retry from the app")
            return Result.success()
        }
        try { withTimeout(8_000) { CaptureWriteGate.mutex.withLock {
            val now = Instant.now()
            val currentReset = PrivacyResetStore.read(context)
            if (currentReset !is PrivacyResetState.Idle || currentReset.generation != generation)
                return@withLock
            val snapshot = (repository.state.first() as? StorageState.Ready)?.snapshot ?: return@withLock
            if (snapshot.offices.none { it.id == officeId && it.enabled && it.countsTowardAttendance } ||
                repository.officeVersion(officeId) != officeVersion ||
                !confirmableCandidate(snapshot.eventEvidence, id, officeId)) return@withLock
            val coverage = CoverageStore.state(context).first()
            if (!canRequestAdaptiveFix(LocationPermissions.read(context),
                    MonitoringStore.read(context).enabled, true, coverage.registration) ||
                coverage.outageStartedAt != null) return@withLock
            val wallAge = now.toEpochMilli() - fix.time
            val age = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
            if (fix.time < requestedAt.toEpochMilli() || wallAge !in 0..30_000 ||
                age !in 0..30_000_000_000L || !fix.hasAccuracy()) {
                MonitoringStore.record(context, "Boundary check stale or inaccurate")
                return@withLock
            }
            val (outcome, office) = decidePresence(snapshot.offices, fix.latitude, fix.longitude,
                fix.accuracy, wallAge)
            if (outcome !in setOf(ReconcileOutcome.CONFIRMED, ReconcileOutcome.OUTSIDE)) {
                MonitoringStore.record(context, if (outcome in setOf(ReconcileOutcome.UNCERTAIN_BOUNDARY,
                        ReconcileOutcome.OVERLAPPING_OFFICES)) "Office boundary uncertain" else "Location not precise enough")
                return@withLock
            }
            val observedAt = Instant.ofEpochMilli(fix.time)
            val recovery = office?.id == officeId && requiresAdaptiveRecoverySplit(snapshot,
                coverage, id, officeId, now)
            val facts = adaptiveFacts(officeId, office?.id, observedAt, now, fix.accuracy,
                recoveryPresence = recovery)
            repository.appendRawEvents(facts)
            CoverageStore.change(context) { it.observed(observedAt) }
            MonitoringStore.record(context, if (office == null) "Outside offices" else "Inside office", observedAt)
        } } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MonitoringStore.record(context, "Boundary check unavailable; retry from the app") }
        return Result.success()
    }
}
