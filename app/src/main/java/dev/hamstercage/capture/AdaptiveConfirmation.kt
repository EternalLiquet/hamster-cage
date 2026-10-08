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
import dev.hamstercage.data.EXIT_VERIFY_INSIDE
import dev.hamstercage.data.EXIT_VERIFY_OUTSIDE
import dev.hamstercage.data.EXIT_VERIFICATION_WINDOW
import dev.hamstercage.data.newlyRejectedExitIds
import dev.hamstercage.data.exitVerificationState
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Transition
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.offices.OfficeLocationServices
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Inexact one-shot checks, bounded by the delivery-time deadline. */
internal object AdaptiveConfirmation {
    const val TAG = "office-boundary-confirmation"
    private const val ID = "event-id"
    private const val OFFICE = "office-id"
    private const val GENERATION = "generation"
    private const val FINGERPRINT = "batch-versions"
    private const val OFFICES = "batch-offices"
    private const val ATTEMPT = "attempt"
    internal const val MAX_EXIT_ATTEMPTS = 5
    internal val EXIT_WINDOW_SECONDS = EXIT_VERIFICATION_WINDOW.seconds

    internal fun delaySeconds(transition: Transition, attempt: Int): Long =
        if (transition == Transition.EXIT) 30L + (attempt - 1) * 60L else 45L

    data class Candidate(val eventId: String, val officeId: String, val generation: Long,
        val versionsFingerprint: String, val officeIds: List<String>, val attempt: Int = 1)
    data class DeliveryBatch(val events: List<RecordedEvent>, val representative: RecordedEvent)

    fun selectDelivery(observations: List<RecordedEvent>, inserted: Set<String>,
        snapshot: AppSnapshot): DeliveryBatch? {
        val eligible = observations.filter { observation -> observation.event.id in inserted &&
            snapshot.offices.any { it.id == observation.event.officeId &&
                it.enabled && it.countsTowardAttendance } }
        val latest = eligible.maxByOrNull { it.event.id } ?: return null
        return if (confirmableCandidate(snapshot.eventEvidence, latest.event.id, latest.event.officeId))
            DeliveryBatch(eligible, latest) else null
    }

    suspend fun schedule(context: Context, event: RecordedEvent, generation: Long,
        versionsFingerprint: String, officeIds: List<String>) {
        fun request(attempt: Int) = OneTimeWorkRequestBuilder<AdaptiveConfirmationWorker>()
            .setInputData(Data.Builder().putString(ID, event.event.id).putString(OFFICE, event.event.officeId)
                .putLong(GENERATION, generation).putString(FINGERPRINT, versionsFingerprint)
                .putStringArray(OFFICES, officeIds.distinct().sorted().toTypedArray())
                .putInt(ATTEMPT, attempt).build())
            .setInitialDelay(delaySeconds(event.event.transition, attempt), TimeUnit.SECONDS)
            .addTag(TAG).addTag("candidate:${event.event.id}").build()
        val count = if (event.event.transition == Transition.EXIT) MAX_EXIT_ATTEMPTS else 1
        val requests = (1..count).map(::request)
        // The receiver gate serializes calls. Awaiting WorkManager's commit prevents
        // a delayed old enqueue from replacing the newer candidate out of order.
        // Independent absolute delays avoid accumulating each prior fix's runtime.
        // Android can still delay or bunch these inexact jobs; the worker checks the deadline.
        WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, requests)
            .result.get(3, TimeUnit.SECONDS)
    }

    fun cancelAll(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }

    fun candidate(data: Data): Candidate? {
        val id = data.getString(ID) ?: return null
        val office = data.getString(OFFICE) ?: return null
        val generation = data.getLong(GENERATION, -1)
        val fingerprint = data.getString(FINGERPRINT) ?: return null
        val officeIds = data.getStringArray(OFFICES)?.toList().orEmpty()
        val attempt = data.getInt(ATTEMPT, 1)
        return if (id.isBlank() || office.isBlank() || generation < 0 || fingerprint.length != 64 ||
            officeIds.isEmpty() || office !in officeIds || officeIds.any { it.isBlank() } ||
            attempt !in 1..MAX_EXIT_ATTEMPTS) null
            else Candidate(id, office, generation, fingerprint, officeIds, attempt)
    }

    fun batch(events: List<RecordedEvent>, candidateId: String,
        officeIds: List<String>): List<RecordedEvent> {
        val candidate = events.singleOrNull { it.event.id == candidateId } ?: return emptyList()
        return events.filter { it.source == "PLAY_SERVICES_GEOFENCE" &&
            it.event.at == candidate.event.at && it.receivedAt == candidate.receivedAt &&
            it.event.transition == candidate.event.transition &&
            it.event.officeId in officeIds }.sortedBy { it.event.officeId }
    }

    fun fingerprint(versions: Map<String, Long>): String = MessageDigest.getInstance("SHA-256")
        .digest(versions.toSortedMap().entries.joinToString("|") { "${it.key}:${it.value}" }
            .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** A newer observation supersedes the candidate, including one from another office. */
internal fun confirmableCandidate(events: List<RecordedEvent>, id: String, officeId: String): Boolean {
    val candidate = events.singleOrNull { it.event.id == id && it.event.officeId == officeId } ?: return false
    if (candidate.source != "PLAY_SERVICES_GEOFENCE") return false
    return events.none { it.event.id != id &&
        it.source !in setOf(EXIT_VERIFY_INSIDE, EXIT_VERIFY_OUTSIDE) &&
        (it.event.at > candidate.event.at || it.event.at == candidate.event.at && it.receivedAt > candidate.receivedAt) }
}

internal fun canRequestAdaptiveFix(setup: LocationSetup, enabled: Boolean, privacyIdle: Boolean,
    registration: RegistrationStatus): Boolean = enabled && privacyIdle && setup.prerequisitesReady &&
    registration == RegistrationStatus.ACTIVE

/** All worker gates are checked again under the write mutex after the location request. */
internal fun eligibleAdaptiveBatch(snapshot: AppSnapshot, candidate: AdaptiveConfirmation.Candidate,
    versions: Map<String, Long>, currentGeneration: Long, monitoringEnabled: Boolean,
    privacyIdle: Boolean, setup: LocationSetup, coverage: CoverageLedger): List<RecordedEvent>? {
    if (candidate.generation != currentGeneration ||
        !canRequestAdaptiveFix(setup, monitoringEnabled, privacyIdle, coverage.registration) ||
        coverage.outageStartedAt != null) return null
    val batch = AdaptiveConfirmation.batch(snapshot.eventEvidence, candidate.eventId, candidate.officeIds)
    if (batch.map { it.event.officeId }.toSet() != candidate.officeIds.toSet() ||
        batch.any { event -> snapshot.offices.none { office ->
            office.id == event.event.officeId && office.enabled && office.countsTowardAttendance } } ||
        AdaptiveConfirmation.fingerprint(versions) != candidate.versionsFingerprint ||
        !confirmableCandidate(snapshot.eventEvidence, candidate.eventId, candidate.officeId)) return null
    return batch
}

/**
 * Facts for one EXIT verification fix. Delivery latency alone never makes an inside fix a
 * recovery split: the EXIT was observed, so the visit it closes keeps its observed credit and
 * only the unobserved interval after it is uncertain. Only a genuine capture outage or other
 * office evidence since the visit opened marks the fix as an unsafe recovery.
 */
internal fun exitVerificationFactsFor(snapshot: AppSnapshot, coverage: CoverageLedger,
    batch: List<RecordedEvent>, candidateId: String, insideOfficeId: String?, observedAt: Instant,
    now: Instant, accuracyMeters: Float, newId: () -> String = HamsterRepository::newId): List<RecordedEvent> {
    // Once one sample has split an unsafe old visit, the next samples belong to the new
    // fix-time visit; splitting again would restart arrival grace on every check.
    val alreadySplit = insideOfficeId != null && batch.isNotEmpty() && snapshot.eventEvidence.any {
        it.event.officeId == insideOfficeId && it.source == "ADAPTIVE_RECOVERY_CONFIRMATION" &&
            it.event.at > batch.maxOf { exit -> exit.event.at } }
    val recovery = insideOfficeId != null && !alreadySplit &&
        requiresAdaptiveRecoverySplit(snapshot, coverage, candidateId, insideOfficeId, now)
    return exitVerificationFacts(batch.map { it.event.officeId }, insideOfficeId, observedAt, now,
        accuracyMeters, recoveryPresence = recovery, newId = newId)
}

internal fun exitVerificationFacts(candidateOfficeIds: List<String>, confirmedOfficeId: String?,
    observedAt: Instant, receivedAt: Instant, accuracyMeters: Float,
    recoveryPresence: Boolean = false,
    newId: () -> String = HamsterRepository::newId): List<RecordedEvent> =
    (candidateOfficeIds.distinct() + listOfNotNull(confirmedOfficeId).filter {
        it !in candidateOfficeIds
    }).map { officeId ->
        val inside = officeId == confirmedOfficeId
        RecordedEvent(RawEvent(newId(), officeId,
            if (inside) Transition.PRESENCE else Transition.ABSENCE, observedAt),
            receivedAt, observedAt, if (!inside) EXIT_VERIFY_OUTSIDE else if (recoveryPresence)
                "ADAPTIVE_RECOVERY_CONFIRMATION" else EXIT_VERIFY_INSIDE,
            accuracyMeters)
    }

internal fun adaptiveFacts(candidateOfficeIds: List<String>, confirmedOfficeId: String?,
    observedAt: Instant, receivedAt: Instant, accuracyMeters: Float,
    recoveryPresence: Boolean = false,
    newId: () -> String = HamsterRepository::newId): List<RecordedEvent> = buildList {
    candidateOfficeIds.distinct().filter { it != confirmedOfficeId }.forEach { candidateOfficeId ->
        add(RecordedEvent(RawEvent(newId(), candidateOfficeId, Transition.ABSENCE, observedAt),
            receivedAt, observedAt, "ADAPTIVE_LOCATION_CONFIRMATION", accuracyMeters))
    }
    if (confirmedOfficeId != null) add(RecordedEvent(RawEvent(newId(), confirmedOfficeId,
        Transition.PRESENCE, observedAt), receivedAt, observedAt,
        if (recoveryPresence) "ADAPTIVE_RECOVERY_CONFIRMATION" else "ADAPTIVE_LOCATION_CONFIRMATION", accuracyMeters))
}

internal fun adaptiveFacts(candidateOfficeId: String, confirmedOfficeId: String?,
    observedAt: Instant, receivedAt: Instant, accuracyMeters: Float,
    recoveryPresence: Boolean = false,
    newId: () -> String = HamsterRepository::newId): List<RecordedEvent> =
    adaptiveFacts(listOf(candidateOfficeId), confirmedOfficeId, observedAt, receivedAt,
        accuracyMeters, recoveryPresence, newId)

/** An old visit spanning registration recovery cannot be confirmed retroactively. */
internal fun requiresAdaptiveRecoverySplit(snapshot: AppSnapshot, coverage: CoverageLedger,
    candidateId: String, officeId: String, now: Instant): Boolean {
    val sessions = snapshot.derive(now).sessions.filter { it.officeId == officeId && it.manualSessionId == null }
    val old = sessions.firstOrNull { candidateId in it.sourceEventIds }
        ?: sessions.firstOrNull { it.isOpen } ?: return false
    val start = old.start ?: return false
    val boundary = coverage.recoveryBoundaryAt ?: return true
    // A visit whose EXIT was observed no later than the start of the recovered outage was
    // observed entirely under healthy capture: the outage cannot have hidden its departure.
    val observedEnd = old.end ?: snapshot.events.firstOrNull { it.id == candidateId &&
        it.transition == Transition.EXIT && it.id in old.sourceEventIds }?.at
    val healthyThrough = coverage.lastOutageStartedAt
    val evidenceUntil = if (observedEnd != null && healthyThrough != null && observedEnd <= healthyThrough) observedEnd else now
    return (start < boundary && evidenceUntil == now) || snapshot.events.any { it.officeId != officeId &&
        it.at >= start && it.at <= evidenceUntil }
}

/** WorkManager may start later under Doze; a late fix only establishes state at its own time. */
class AdaptiveConfirmationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val candidate = AdaptiveConfirmation.candidate(inputData) ?: return Result.success()
        val (id, officeId, generation) = candidate
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
        val initialBatch = AdaptiveConfirmation.batch(initial.snapshot.eventEvidence, id, candidate.officeIds)
        val isExit = initialBatch.firstOrNull()?.event?.transition == Transition.EXIT
        val deadline = if (isExit) initialBatch.first().receivedAt.plusSeconds(AdaptiveConfirmation.EXIT_WINDOW_SECONDS)
            else null
        if (deadline != null && Instant.now() >= deadline) return Result.success()
        if (isExit && initialBatch.none { it.event.id in initial.snapshot.input(Instant.now()).candidateExitIds })
            return Result.success()
        if (eligibleAdaptiveBatch(initial.snapshot, candidate,
                repository.officeVersions(initialBatch.map { it.event.officeId }), reset.generation,
                MonitoringStore.read(context).enabled, true, setup, initialCoverage) == null) return Result.success()
        val requestedAt = Instant.now()
        val fix = try { OfficeLocationServices(context).captureFix(freshAfterRequest = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            MonitoringStore.record(context, "Boundary check unavailable; retry from the app")
            return Result.success()
        }
        var resyncOffices = emptyList<String>()
        try { withTimeout(8_000) { CaptureWriteGate.mutex.withLock {
            val now = Instant.now()
            val currentReset = PrivacyResetStore.read(context)
            if (currentReset !is PrivacyResetState.Idle || currentReset.generation != generation)
                return@withLock
            val snapshot = (repository.state.first() as? StorageState.Ready)?.snapshot ?: return@withLock
            val batch = AdaptiveConfirmation.batch(snapshot.eventEvidence, id, candidate.officeIds)
            val coverage = CoverageStore.state(context).first()
            if (eligibleAdaptiveBatch(snapshot, candidate,
                    repository.officeVersions(batch.map { it.event.officeId }), currentReset.generation,
                    MonitoringStore.read(context).enabled, true, LocationPermissions.read(context),
                    coverage) == null) return@withLock
            val wallAge = now.toEpochMilli() - fix.time
            val age = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
            val accuracy = fix.accuracy.takeIf { fix.hasAccuracy() && it.isFinite() && it in 0f..10000f }
            if (fix.time < requestedAt.toEpochMilli() || wallAge !in 0..30_000 ||
                age !in 0..30_000_000_000L || !fix.hasAccuracy()) {
                MonitoringStore.record(context, "Boundary check stale or inaccurate", accuracyMeters = accuracy)
                return@withLock
            }
            val (outcome, office) = decidePresence(snapshot.offices, fix.latitude, fix.longitude,
                fix.accuracy, wallAge)
            if (outcome !in setOf(ReconcileOutcome.CONFIRMED, ReconcileOutcome.OUTSIDE)) {
                MonitoringStore.record(context, if (outcome in setOf(ReconcileOutcome.UNCERTAIN_BOUNDARY,
                        ReconcileOutcome.OVERLAPPING_OFFICES)) "Office boundary uncertain" else "Location not precise enough",
                    accuracyMeters = accuracy)
                return@withLock
            }
            val observedAt = Instant.ofEpochMilli(fix.time)
            if (deadline != null && observedAt > deadline) {
                MonitoringStore.record(context, "Boundary check delayed; later checks may recover",
                    accuracyMeters = accuracy)
                return@withLock
            }
            // Judge whether the EXIT still awaits verification as of the fix's own observation,
            // so a check observed inside the window is kept even if committing it ran past it.
            if (isExit && batch.none { it.event.id in snapshot.input(observedAt).candidateExitIds }) return@withLock
            val facts = if (isExit) exitVerificationFactsFor(snapshot, coverage, batch, id, office?.id,
                observedAt, now, fix.accuracy)
            else {
                val recovery = office != null && requiresAdaptiveRecoverySplit(snapshot,
                    coverage, id, office.id, now)
                adaptiveFacts(batch.map { it.event.officeId }, office?.id, observedAt, now, fix.accuracy,
                    recoveryPresence = recovery)
            }
            repository.appendRawEvents(facts)
            CoverageStore.change(context) { it.observed(observedAt) }
            MonitoringStore.record(context, if (office == null) "Outside offices" else "Inside office", observedAt,
                fix.accuracy)
            val updated = snapshot.copy(eventEvidence = snapshot.eventEvidence + facts)
            if (isExit) {
                val rejected = newlyRejectedExitIds(exitVerificationState(snapshot.eventEvidence, now),
                    exitVerificationState(updated.eventEvidence, now))
                resyncOffices = batch.filter { it.event.id in rejected }.map { it.event.officeId }.distinct()
            }
            // Confirmation can also run in a process with no activity observer.
            updateReconciliationAfterObservation(context, updated, now)
        } } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MonitoringStore.record(context, "Boundary check unavailable; retry from the app") }
        // Outside the capture gate: re-registration takes the same gate.
        if (resyncOffices.isNotEmpty()) try {
            val resynced = withTimeout(8_000) { CaptureController.get(context).resyncAfterRejectedExit(resyncOffices) }
            if (!resynced) MonitoringStore.record(context, "Office boundary refresh skipped; later checks may recover")
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MonitoringStore.record(context, "Office boundary refresh unavailable; later checks may recover") }
        return Result.success()
    }
}
