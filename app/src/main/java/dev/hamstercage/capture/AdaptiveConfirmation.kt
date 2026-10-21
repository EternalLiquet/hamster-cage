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
import dev.hamstercage.data.RegistrationReason
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.data.EXIT_VERIFY_INSIDE
import dev.hamstercage.data.EXIT_VERIFY_OUTSIDE
import dev.hamstercage.data.EXIT_VERIFICATION_WINDOW
import dev.hamstercage.data.newlyRejectedExitIds
import dev.hamstercage.data.exitVerificationState
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.Office
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
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
    private const val BOUNDED = "bounded"
    internal const val MAX_EXIT_ATTEMPTS = 5
    internal val EXIT_WINDOW_SECONDS = EXIT_VERIFICATION_WINDOW.seconds

    internal fun delaySeconds(transition: Transition, attempt: Int): Long =
        if (transition == Transition.EXIT) 30L + (attempt - 1) * 60L else 45L

    data class Candidate(val eventId: String, val officeId: String, val generation: Long,
        val versionsFingerprint: String, val officeIds: List<String>, val attempt: Int = 1,
        val bounded: Boolean = false)
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
                .putInt(ATTEMPT, attempt).putBoolean(BOUNDED, event.event.transition == Transition.EXIT).build())
            .setInitialDelay(delaySeconds(event.event.transition, attempt), TimeUnit.SECONDS)
            .addTag(TAG).addTag("candidate:${event.event.id}").build()
        val requests = listOf(request(1))
        // The receiver gate serializes calls. Awaiting WorkManager's commit prevents
        // a delayed old enqueue from replacing the newer candidate out of order.
        // One bounded worker waits between sparse one-shot fixes once it starts.
        // Android can still delay or stop it; periodic checks remain a backstop.
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
            else Candidate(id, office, generation, fingerprint, officeIds, attempt,
                data.getBoolean(BOUNDED, false))
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

/**
 * Offices in a verification batch whose EXIT is still undecided (awaiting checks, or expired
 * without a decision). Rejected and decisively confirmed departures need no re-arm. Once the
 * platform has delivered an EXIT it treats the device as outside, so a later real departure
 * would raise no EXIT; re-arming with an initial ENTER restores its inside state if the device
 * is inside and does nothing if it is outside. Only the final scheduled check does this, after
 * the outside checks have had their chance: an earlier re-arm could return an equally coarse
 * ENTER within the quick-return window and reject a real departure.
 */
internal fun unresolvedExitOfficeIds(evidence: List<RecordedEvent>, batch: List<RecordedEvent>,
    now: Instant): List<String> {
    val state = exitVerificationState(evidence, now)
    return batch.filter { it.event.transition == Transition.EXIT &&
        (it.event.id in state.candidateIds || it.event.id in state.uncertainIds) }
        .map { it.event.officeId }.distinct()
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

/** A fix can classify any eligible office, not just the boundary's original batch. */
internal fun sameAdaptiveOfficeConfiguration(before: List<Office>, beforeVersions: Map<String, Long>,
    after: List<Office>, afterVersions: Map<String, Long>): Boolean =
    before == after && beforeVersions == afterVersions

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
    // A visit that opened after the previous recovery and whose EXIT was observed no later
    // than the start of the latest outage lies wholly inside one healthy capture window, so no
    // outage can have hidden a departure and return within it.
    val observedEnd = old.end ?: snapshot.events.firstOrNull { it.id == candidateId &&
        it.transition == Transition.EXIT && it.id in old.sourceEventIds }?.at
    val healthySince = coverage.lastOutageHealthySince
    val healthyUntil = coverage.lastOutageStartedAt
    val wholeVisitHealthy = observedEnd != null && healthySince != null && healthyUntil != null &&
        start >= healthySince && observedEnd <= healthyUntil
    val evidenceUntil = if (wholeVisitHealthy) observedEnd!! else now
    return (!wholeVisitHealthy && start < boundary) || snapshot.events.any { it.officeId != officeId &&
        it.at >= start && it.at <= evidenceUntil }
}

/** What one check attempt did, for the diagnostic log. */
internal data class CheckReport(val outcome: BoundaryCheckOutcome, val accuracyMeters: Float? = null,
    val fixAgeMs: Long? = null)

internal fun adaptiveRecordedOutcome(facts: List<RecordedEvent>, insideOfficeId: String?): BoundaryCheckOutcome =
    if (facts.isEmpty()) BoundaryCheckOutcome.SETTLED_BEFORE_FIX
    else if (insideOfficeId == null) BoundaryCheckOutcome.RECORDED_OUTSIDE
    else BoundaryCheckOutcome.RECORDED_INSIDE

/** Why a fix failed the freshness gate, or null when it passed. Order matches the original single check. */
internal fun fixFreshnessFailure(fixTimeMs: Long, requestedAtMs: Long, wallAgeMs: Long, elapsedAgeNanos: Long,
    hasAccuracy: Boolean): BoundaryCheckOutcome? = when {
    fixTimeMs <= requestedAtMs -> BoundaryCheckOutcome.FIX_CACHED
    wallAgeMs !in 0..30_000 || elapsedAgeNanos !in 0..30_000_000_000L -> BoundaryCheckOutcome.FIX_STALE
    // The two clocks are sampled separately; a difference above one second means the
    // epoch timestamp and monotonic age cannot be trusted as the same observation.
    kotlin.math.abs(wallAgeMs - elapsedAgeNanos / 1_000_000L) > 1_000 ->
        BoundaryCheckOutcome.FIX_STALE
    !hasAccuracy -> BoundaryCheckOutcome.FIX_NO_ACCURACY
    else -> null
}

/** Skip missed absolute slots rather than firing cached/bunched requests back-to-back. */
internal fun nextExitSlot(receivedAt: Instant, now: Instant): Int? =
    (2..AdaptiveConfirmation.MAX_EXIT_ATTEMPTS).firstOrNull { slot ->
        receivedAt.plusSeconds(AdaptiveConfirmation.delaySeconds(Transition.EXIT, slot)) > now
    }

internal fun lateRecoveryAllowed(candidate: AdaptiveConfirmation.Candidate): Boolean =
    candidate.bounded || candidate.attempt == AdaptiveConfirmation.MAX_EXIT_ATTEMPTS

/** A final in-window request may finish after the deadline: use it for current state only. */
internal fun canRecoverLateFix(candidate: AdaptiveConfirmation.Candidate, receivedAt: Instant,
    now: Instant): Boolean = lateRecoveryAllowed(candidate) ||
    candidate.bounded && nextExitSlot(receivedAt, now) == null

/**
 * Whether an EXIT check must stop before requesting a fix, and how to describe it. Enforcement is
 * unchanged: the attempt stops when the clock at [checkedAt] has reached [deadline]. The label only
 * separates a worker that began after the deadline from one that began in time and crossed it
 * while preparing (registration refresh, storage reads). Null means the check may continue.
 */
internal fun deadlineStop(startedAt: Instant, checkedAt: Instant, deadline: Instant?): BoundaryCheckOutcome? = when {
    deadline == null || checkedAt < deadline -> null
    startedAt >= deadline -> BoundaryCheckOutcome.STARTED_PAST_DEADLINE
    else -> BoundaryCheckOutcome.DEADLINE_PASSED_DURING_PREPARATION
}

/** WorkManager may start later under Doze; a late fix only establishes state at its own time. */
class AdaptiveConfirmationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val candidate = AdaptiveConfirmation.candidate(inputData) ?: return Result.success()
        val snapshot = (HamsterRepository.get(applicationContext).state.first() as? StorageState.Ready)?.snapshot
        val exit = AdaptiveConfirmation.batch(snapshot?.eventEvidence.orEmpty(), candidate.eventId,
            candidate.officeIds).firstOrNull { it.event.transition == Transition.EXIT }
        val bounded = candidate.bounded && exit != null
        val exitReceived = exit?.receivedAt
        val deadline = exitReceived?.plusSeconds(AdaptiveConfirmation.EXIT_WINDOW_SECONDS)
        var attempt = candidate.attempt
        while (true) {
            val current = candidate.copy(attempt = attempt)
            val startedAt = Instant.now()
            try { check(current, startedAt) }
            catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                report = CheckReport(BoundaryCheckOutcome.ERROR)
            } catch (_: Exception) { report = CheckReport(BoundaryCheckOutcome.ERROR) }
            report?.let { finished -> BoundaryCheckLog.record(applicationContext, candidate.generation,
                BoundaryCheckEntry(candidate.eventId, attempt, startedAt, maxOf(startedAt, Instant.now()),
                    finished.outcome, finished.accuracyMeters, finished.fixAgeMs)) }
            if (!bounded || exitReceived == null || deadline == null || Instant.now() >= deadline || report == null ||
                report?.outcome in setOf(BoundaryCheckOutcome.SUPERSEDED_OR_CHANGED,
                    BoundaryCheckOutcome.CHANGED_DURING_FIX, BoundaryCheckOutcome.MONITORING_OFF,
                    BoundaryCheckOutcome.SETTLED_BEFORE_FIX, BoundaryCheckOutcome.NOT_AWAITING_VERIFICATION)) break
            val slot = nextExitSlot(exitReceived, Instant.now()) ?: break
            val target = exitReceived.plusSeconds(AdaptiveConfirmation.delaySeconds(Transition.EXIT, slot))
            delay(Duration.between(Instant.now(), target).toMillis().coerceAtLeast(0))
            attempt = slot
        }
        if (bounded && report != null && exitReceived != null) {
            val rearmAt = exitReceived.plusSeconds(AdaptiveConfirmation.delaySeconds(Transition.EXIT,
                AdaptiveConfirmation.MAX_EXIT_ATTEMPTS))
            delay(Duration.between(Instant.now(), rearmAt).toMillis().coerceAtLeast(0))
        }
        if ((bounded && report != null) || candidate.attempt == AdaptiveConfirmation.MAX_EXIT_ATTEMPTS)
            resyncIfUnresolved(applicationContext, candidate)
        return Result.success()
    }

    /** Why this attempt stopped. Null only when the privacy generation no longer matches. */
    private var report: CheckReport? = CheckReport(BoundaryCheckOutcome.ERROR)

    /** Every path sets [report] before returning. */
    private suspend fun check(candidate: AdaptiveConfirmation.Candidate, startedAt: Instant) {
        val (id, officeId, generation) = candidate
        val context = applicationContext
        val repository = HamsterRepository.get(context)
        val reset = PrivacyResetStore.read(context)
        if (reset !is PrivacyResetState.Idle || reset.generation != generation) { report = null; return }
        if (!MonitoringStore.read(context).enabled) { report = CheckReport(BoundaryCheckOutcome.MONITORING_OFF); return }
        val setup = LocationPermissions.read(context)
        if (setup.prerequisitesReady) {
            try { CaptureController.get(context).refreshIfNeededForProcess(RegistrationReason.ADAPTIVE_CONFIRMATION) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                MonitoringStore.record(context, "Office registration unavailable")
                report = CheckReport(BoundaryCheckOutcome.REGISTRATION_REFRESH_FAILED)
                return
            }
        }
        val initialCoverage = CoverageStore.state(context).first()
        if (!canRequestAdaptiveFix(setup, true, true, initialCoverage.registration) ||
            initialCoverage.outageStartedAt != null) {
            MonitoringStore.record(context, if (!setup.prerequisitesReady) setup.status
                else "Office registration unavailable")
            report = CheckReport(if (!setup.prerequisitesReady) BoundaryCheckOutcome.PREREQUISITES_MISSING
                else BoundaryCheckOutcome.REGISTRATION_NOT_ACTIVE)
            return
        }
        val initial = repository.state.first() as? StorageState.Ready
            ?: run { report = CheckReport(BoundaryCheckOutcome.STORAGE_UNAVAILABLE); return }
        val initialBatch = AdaptiveConfirmation.batch(initial.snapshot.eventEvidence, id, candidate.officeIds)
        val isExit = initialBatch.firstOrNull()?.event?.transition == Transition.EXIT
        val deadline = if (isExit) initialBatch.first().receivedAt.plusSeconds(AdaptiveConfirmation.EXIT_WINDOW_SECONDS)
            else null
        val late = deadline != null && Instant.now() >= deadline && lateRecoveryAllowed(candidate)
        deadlineStop(startedAt, Instant.now(), deadline)?.let {
            if (!late) { report = CheckReport(it); return }
        }
        if (isExit && !late && initialBatch.none { it.event.id in initial.snapshot.input(Instant.now()).candidateExitIds }) {
            report = CheckReport(BoundaryCheckOutcome.NOT_AWAITING_VERIFICATION); return
        }
        if (eligibleAdaptiveBatch(initial.snapshot, candidate,
                repository.officeVersions(initialBatch.map { it.event.officeId }), reset.generation,
                MonitoringStore.read(context).enabled, true, setup, initialCoverage) == null) {
            report = CheckReport(BoundaryCheckOutcome.SUPERSEDED_OR_CHANGED); return
        }
        val initialOffices = initial.snapshot.offices
        val allVersions = repository.officeVersions(initialOffices.map { it.id })
        val fix = try { OfficeLocationServices(context).captureFix(freshAfterRequest = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            MonitoringStore.record(context, "Boundary check unavailable; retry from the app")
            report = CheckReport(BoundaryCheckOutcome.FIX_UNAVAILABLE)
            return
        }
        var resyncOffices = emptyList<String>()
        report = CheckReport(BoundaryCheckOutcome.WRITE_FAILED)
        try { withTimeout(8_000) { CaptureWriteGate.mutex.withLock {
            val now = Instant.now()
            val currentReset = PrivacyResetStore.read(context)
            if (currentReset !is PrivacyResetState.Idle || currentReset.generation != generation) {
                report = null
                return@withLock
            }
            val snapshot = (repository.state.first() as? StorageState.Ready)?.snapshot ?: run {
                report = CheckReport(BoundaryCheckOutcome.STORAGE_UNAVAILABLE)
                return@withLock
            }
            val batch = AdaptiveConfirmation.batch(snapshot.eventEvidence, id, candidate.officeIds)
            val coverage = CoverageStore.state(context).first()
            if (!sameAdaptiveOfficeConfiguration(initialOffices, allVersions, snapshot.offices,
                    repository.officeVersions(snapshot.offices.map { it.id })) ||
                eligibleAdaptiveBatch(snapshot, candidate,
                    repository.officeVersions(batch.map { it.event.officeId }), currentReset.generation,
                    MonitoringStore.read(context).enabled, true, LocationPermissions.read(context),
                    coverage) == null) {
                report = CheckReport(BoundaryCheckOutcome.CHANGED_DURING_FIX)
                return@withLock
            }
            val wallAge = now.toEpochMilli() - fix.time
            val age = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
            val accuracy = fix.accuracy.takeIf { fix.hasAccuracy() && it.isFinite() && it in 0f..10000f }
            val loggedAge = wallAge.takeIf { it >= 0 }
            val boundaryMs = maxOf(batch.maxOf { it.event.at }.toEpochMilli(),
                coverage.recoveryBoundaryAt?.toEpochMilli() ?: Long.MIN_VALUE)
            val stale = fixFreshnessFailure(fix.time, boundaryMs, wallAge, age, fix.hasAccuracy())
            if (stale != null) {
                MonitoringStore.record(context, "Boundary check stale or inaccurate", accuracyMeters = accuracy)
                report = CheckReport(stale, accuracy, loggedAge)
                return@withLock
            }
            val (outcome, office) = decidePresence(snapshot.offices, fix.latitude, fix.longitude,
                fix.accuracy, wallAge)
            if (outcome !in setOf(ReconcileOutcome.CONFIRMED, ReconcileOutcome.OUTSIDE)) {
                val boundary = outcome in setOf(ReconcileOutcome.UNCERTAIN_BOUNDARY, ReconcileOutcome.OVERLAPPING_OFFICES)
                MonitoringStore.record(context, if (boundary) "Office boundary uncertain" else "Location not precise enough",
                    accuracyMeters = accuracy)
                report = CheckReport(if (boundary) BoundaryCheckOutcome.BOUNDARY_UNCERTAIN
                    else BoundaryCheckOutcome.FIX_IMPRECISE, accuracy, loggedAge)
                return@withLock
            }
            val observedAt = Instant.ofEpochMilli(fix.time)
            val lateFix = deadline != null && observedAt > deadline
            if (lateFix && !canRecoverLateFix(candidate, batch.first().receivedAt, now)) {
                MonitoringStore.record(context, "Boundary check delayed; later checks may recover",
                    accuracyMeters = accuracy)
                report = CheckReport(BoundaryCheckOutcome.FIX_PAST_DEADLINE, accuracy, loggedAge)
                return@withLock
            }
            // Judge whether the EXIT still awaits verification as of the fix's own observation,
            // so a check observed inside the window is kept even if committing it ran past it.
            if (isExit && !lateFix && batch.none { it.event.id in snapshot.input(observedAt).candidateExitIds }) {
                report = CheckReport(BoundaryCheckOutcome.SETTLED_BEFORE_FIX, accuracy, loggedAge)
                return@withLock
            }
            val facts = if (lateFix) reconciliationFacts(snapshot, coverage, now, observedAt,
                office?.id, fix.accuracy)
            else if (isExit) exitVerificationFactsFor(snapshot, coverage, batch, id, office?.id,
                observedAt, now, fix.accuracy)
            else {
                val recovery = office != null && requiresAdaptiveRecoverySplit(snapshot,
                    coverage, id, office.id, now)
                adaptiveFacts(batch.map { it.event.officeId }, office?.id, observedAt, now, fix.accuracy,
                    recoveryPresence = recovery)
            }
            val recordedOutcome = adaptiveRecordedOutcome(facts, office?.id)
            if (facts.isEmpty()) {
                report = CheckReport(recordedOutcome, accuracy, loggedAge)
                return@withLock
            }
            repository.appendRawEvents(facts)
            report = CheckReport(recordedOutcome, accuracy, loggedAge)
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
    }
}

/** The last scheduled check re-arms an EXIT that is still undecided; see [unresolvedExitOfficeIds]. */
internal suspend fun resyncIfUnresolved(context: Context, candidate: AdaptiveConfirmation.Candidate) {
    try {
        val reset = PrivacyResetStore.read(context)
        if (reset !is PrivacyResetState.Idle || reset.generation != candidate.generation ||
            !MonitoringStore.read(context).enabled) return
        val snapshot = (HamsterRepository.get(context).state.first() as? StorageState.Ready)?.snapshot ?: return
        // A newer observation has already re-established the platform's state.
        if (!confirmableCandidate(snapshot.eventEvidence, candidate.eventId, candidate.officeId)) return
        val batch = AdaptiveConfirmation.batch(snapshot.eventEvidence, candidate.eventId, candidate.officeIds)
        resyncOfficeBoundaries(context, unresolvedExitOfficeIds(snapshot.eventEvidence, batch, Instant.now()))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { recordBoundaryRefreshUnavailable(context) }
}

private suspend fun recordBoundaryRefreshUnavailable(context: Context) {
    try { MonitoringStore.record(context, "Office boundary refresh unavailable; later checks may recover") }
    catch (_: Exception) { Unit }
}

/**
 * Re-arms the platform's inside state for [officeIds]. A new process first restores its own
 * registration, outside the timeout so a partial re-registration is never cancelled. Without
 * location prerequisites nothing is touched, because a refresh would then remove the fences
 * instead. Call outside the capture gate: re-registration takes it.
 */
internal suspend fun resyncOfficeBoundaries(context: Context, officeIds: List<String>) {
    if (officeIds.isEmpty() || !LocationPermissions.read(context).prerequisitesReady) return
    try {
        val controller = CaptureController.get(context)
        controller.refreshIfNeededForProcess(RegistrationReason.ADAPTIVE_CONFIRMATION)
        val resynced = withTimeout(8_000) { controller.resyncAfterRejectedExit(officeIds) }
        if (!resynced) MonitoringStore.record(context, "Office boundary refresh skipped; later checks may recover")
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { recordBoundaryRefreshUnavailable(context) }
}
