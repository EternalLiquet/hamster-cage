package dev.hamstercage.capture

import android.content.Context
import android.os.SystemClock
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.RawEvent
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.Transition
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.offices.OfficeLocationServices
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class ReconcileOutcome {
    CONFIRMED, ALREADY_PRESENT, OUTSIDE, UNCERTAIN_BOUNDARY, OVERLAPPING_OFFICES,
    STALE, INACCURATE, NEEDS_PRECISE, LOCATION_OFF, NOT_REGISTERED, TIMEOUT, UNAVAILABLE,
}

/** A fix identifies current presence only when its entire accuracy circle fits one office. */
internal fun decidePresence(offices: List<Office>, latitude: Double, longitude: Double,
    accuracyMeters: Float, ageMillis: Long): Pair<ReconcileOutcome, Office?> {
    if (ageMillis !in 0..30_000) return ReconcileOutcome.STALE to null
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 ||
        longitude !in -180.0..180.0 || !accuracyMeters.isFinite() || accuracyMeters !in 0f..100f)
        return ReconcileOutcome.INACCURATE to null
    val distances = offices.filter { it.enabled && it.countsTowardAttendance }.map { office ->
        office to metersBetween(latitude, longitude, office.latitude, office.longitude)
    }
    val inside = distances.filter { (office, distance) -> distance + accuracyMeters < office.radiusMeters }
    if (inside.size > 1) return ReconcileOutcome.OVERLAPPING_OFFICES to null
    if (inside.size == 1) {
        // A second plausible office also makes this observation ambiguous.
        if (distances.any { (office, distance) -> office.id != inside[0].first.id &&
                distance - accuracyMeters <= office.radiusMeters })
            return ReconcileOutcome.OVERLAPPING_OFFICES to null
        return ReconcileOutcome.CONFIRMED to inside[0].first
    }
    return if (distances.any { (office, distance) -> distance - accuracyMeters <= office.radiusMeters })
        ReconcileOutcome.UNCERTAIN_BOUNDARY to null else ReconcileOutcome.OUTSIDE to null
}

internal fun canOpenFromObservation(snapshot: AppSnapshot, officeId: String, coverage: CoverageLedger,
    observedAt: Instant, now: Instant): ReconcileOutcome? {
    if (snapshot.events.any { it.at > observedAt }) return ReconcileOutcome.STALE
    val open = snapshot.derive(now).sessions.filter { it.isOpen }
    if (open.isEmpty()) return null
    if (open.size != 1 || open.single().officeId != officeId)
        return ReconcileOutcome.OVERLAPPING_OFFICES
    val session = open.single()
    if (session.manualSessionId != null) return ReconcileOutcome.ALREADY_PRESENT
    // Ledger presence is global across offices. An A opening after recovery can
    // still be stale if B was observed between that opening and this A fix.
    val currentOpeningFact = snapshot.events.any { event ->
        event.id in session.sourceEventIds && event.officeId == officeId &&
            event.at == session.start && event.at >= (coverage.recoveryBoundaryAt ?: Instant.MAX) &&
            event.at.atZone(snapshot.policy.zoneId).toLocalDate() == now.atZone(snapshot.policy.zoneId).toLocalDate() &&
            event.transition in setOf(Transition.ENTER, Transition.PRESENCE)
    }
    val otherOfficeSinceOpening = snapshot.events.any { event ->
        event.officeId != officeId && session.start?.let { event.at >= it } == true && event.at <= observedAt
    }
    val blockingReview = session.reviewReasons.any { it !in setOf(ReviewReason.OPEN_SESSION, ReviewReason.DUPLICATE_EVENT) }
    if (currentOpeningFact && !otherOfficeSinceOpening && !blockingReview &&
        coverage.presenceConfirmed(now, snapshot.policy.zoneId))
        return ReconcileOutcome.ALREADY_PRESENT
    // An unconfirmed old raw ENTER may span an outage. A PRESENCE fact splits
    // that old interval for review and starts a new observed interval now.
    return null
}

/** A cached fix from before registration cannot confirm post-recovery presence. */
internal fun recoveryObservationGate(coverage: CoverageLedger, observedAt: Instant,
    requestedAt: Instant, now: Instant, zone: ZoneId): ReconcileOutcome? {
    if (coverage.registration != RegistrationStatus.ACTIVE || coverage.outageStartedAt != null ||
        coverage.recoveryBoundaryAt == null || coverage.policyZoneId != zone)
        return ReconcileOutcome.NOT_REGISTERED
    if (observedAt < requestedAt || observedAt < coverage.recoveryBoundaryAt ||
        !coverage.observed(observedAt).presenceConfirmed(now, zone)) return ReconcileOutcome.STALE
    return null
}

private fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val arc = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) *
        cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 12_742_000.0 * asin(sqrt(min(1.0, arc)))
}

/** Invoked only by a visible UI action. Coordinates never enter the attendance store. */
class ForegroundReconciliation(private val context: Context,
    private val location: OfficeLocationServices = OfficeLocationServices(context)) {
    private val application = context.applicationContext
    private val repository = HamsterRepository.get(application)

    suspend fun check(isForeground: () -> Boolean): ReconcileOutcome {
        if (!isForeground()) return ReconcileOutcome.UNAVAILABLE
        val setup = LocationPermissions.read(application)
        if (!setup.fineLocation) return ReconcileOutcome.NEEDS_PRECISE
        if (!setup.locationEnabled) return ReconcileOutcome.LOCATION_OFF
        if (CaptureHealth.state.value.registration != RegistrationStatus.ACTIVE) return ReconcileOutcome.NOT_REGISTERED
        val requestedAt = Instant.now()
        val fix = try { location.captureFix(freshAfterRequest = true) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return if (failure.message?.contains("timed out") == true) ReconcileOutcome.TIMEOUT
                else if (failure.message?.contains("revoked") == true) ReconcileOutcome.NEEDS_PRECISE
                else ReconcileOutcome.UNAVAILABLE
        }
        return try { withTimeout(8_000) { CaptureWriteGate.mutex.withLock {
            if (!isForeground() || PrivacyResetStore.read(application) !is PrivacyResetState.Idle)
                return@withLock ReconcileOutcome.UNAVAILABLE
            val current = LocationPermissions.read(application)
            if (!current.fineLocation) return@withLock ReconcileOutcome.NEEDS_PRECISE
            if (!current.locationEnabled) return@withLock ReconcileOutcome.LOCATION_OFF
            if (CaptureHealth.state.value.registration != RegistrationStatus.ACTIVE)
                return@withLock ReconcileOutcome.NOT_REGISTERED
            val state = repository.state.first() as? StorageState.Ready ?: return@withLock ReconcileOutcome.UNAVAILABLE
            val now = Instant.now()
            val observedAt = Instant.ofEpochMilli(fix.time)
            val age = SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos
            val wallAge = now.toEpochMilli() - fix.time
            if (age !in 0..30_000_000_000L || wallAge !in 0..30_000L)
                return@withLock ReconcileOutcome.STALE
            if (!fix.hasAccuracy()) return@withLock ReconcileOutcome.INACCURATE
            val coverage = CoverageStore.state(application).first()
            recoveryObservationGate(coverage, observedAt, requestedAt, now, state.snapshot.policy.zoneId)
                ?.let { return@withLock it }
            val (outcome, office) = decidePresence(state.snapshot.offices, fix.latitude,
                fix.longitude, fix.accuracy, wallAge)
            if (office == null) return@withLock outcome
            // A queued transition later than this sample, or any current open session,
            // supersedes the sampled location. The write gate serializes receiver writes.
            canOpenFromObservation(state.snapshot, office.id, coverage, observedAt, now)
                ?.let { return@withLock it }
            repository.appendRawEvents(listOf(RecordedEvent(
                RawEvent(HamsterRepository.newId(), office.id, Transition.PRESENCE, observedAt),
                now, observedAt, "FOREGROUND_LOCATION_RECONCILIATION")))
            CoverageStore.change(application) { it.observed(observedAt) }
            ReconcileOutcome.CONFIRMED
        } } } catch (_: TimeoutCancellationException) { ReconcileOutcome.TIMEOUT }
    }
}
