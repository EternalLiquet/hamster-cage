package dev.hamstercage.capture

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Capture confidence metadata; no attendance event or correction is synthesized here. */
data class CoverageLedger(
    val historyStartDate: LocalDate? = null,
    val unknownDates: Set<LocalDate> = emptySet(),
    val reviewedDates: Set<LocalDate> = emptySet(),
    val lastHealthyAt: Instant? = null,
    val outageStartedAt: Instant? = null,
    val outageRecordedThrough: LocalDate? = null,
    val recoveryBoundaryAt: Instant? = null,
    val lastObservationAt: Instant? = null,
    val registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
    val policyZoneId: ZoneId? = null,
) {
    /** A new process cannot assume an old OS registration survived a force-stop. */
    fun processStarted(now: Instant, zone: ZoneId): CoverageLedger =
        if (lastHealthyAt == null && outageStartedAt == null) this else outage(now, zone)

    fun outage(now: Instant, zone: ZoneId): CoverageLedger {
        val current = inZone(now, zone)
        return current.outage(current.lastHealthyAt ?: now, now, zone)
    }

    /** Date-only confidence cannot be rebased losslessly after a policy timezone edit. */
    private fun inZone(now: Instant, zone: ZoneId): CoverageLedger = when {
        policyZoneId == null || policyZoneId == zone -> copy(policyZoneId = zone)
        else -> copy(historyStartDate = null, unknownDates = emptySet(), reviewedDates = emptySet(),
            lastObservationAt = null, outageStartedAt = outageStartedAt ?: lastHealthyAt ?: now,
            outageRecordedThrough = null, recoveryBoundaryAt = null,
            registration = RegistrationStatus.FAILED, policyZoneId = zone)
    }

    /** A receiver may know an outage instant before it can safely read policy timezone. */
    fun unlocatedOutage(now: Instant): CoverageLedger = copy(
        outageStartedAt = outageStartedAt ?: lastHealthyAt ?: now,
        recoveryBoundaryAt = null, registration = RegistrationStatus.FAILED,
    )

    private fun outage(start: Instant, now: Instant, zone: ZoneId): CoverageLedger {
        val first = outageStartedAt ?: start
        val through = now.atZone(zone).toLocalDate()
        val firstDate = outageRecordedThrough?.plusDays(1) ?: first.atZone(zone).toLocalDate()
        if (firstDate.plusDays(3660) < through) return copy(
            historyStartDate = null, unknownDates = emptySet(), reviewedDates = emptySet(),
            lastObservationAt = null, outageStartedAt = first, outageRecordedThrough = through,
            recoveryBoundaryAt = null, registration = RegistrationStatus.FAILED, policyZoneId = zone,
        )
        val newlyAffected = dates(firstDate, through)
        return copy(unknownDates = unknownDates + newlyAffected, reviewedDates = reviewedDates - newlyAffected,
            outageStartedAt = first, outageRecordedThrough = through, recoveryBoundaryAt = null,
            registration = RegistrationStatus.FAILED, policyZoneId = zone)
    }

    fun registrationSucceeded(now: Instant, zone: ZoneId, hasOffices: Boolean): CoverageLedger {
        if (!hasOffices) return outage(now, zone).copy(registration = RegistrationStatus.NO_OFFICES)
        val current = inZone(now, zone)
        val recovered = if (current.outageStartedAt != null) current.outage(now, zone) else current
        // A registration request alone is not evidence that any observation was delivered.
        val firstObservedDay = current.lastObservationAt?.atZone(zone)?.toLocalDate()
        return recovered.copy(historyStartDate = current.historyStartDate ?: firstObservedDay,
            unknownDates = if (current.historyStartDate == null && firstObservedDay != null)
                recovered.unknownDates + firstObservedDay else recovered.unknownDates,
            lastHealthyAt = now, outageStartedAt = null, outageRecordedThrough = null,
            recoveryBoundaryAt = if (current.outageStartedAt != null) now else current.recoveryBoundaryAt ?: now,
            registration = RegistrationStatus.ACTIVE, policyZoneId = zone)
    }

    fun observed(at: Instant): CoverageLedger {
        val firstObservedDay = if (historyStartDate == null) policyZoneId?.let { at.atZone(it).toLocalDate() } else null
        return copy(
        historyStartDate = historyStartDate ?: firstObservedDay,
        unknownDates = if (firstObservedDay == null) unknownDates else unknownDates + firstObservedDay,
        lastObservationAt = if (lastObservationAt == null || at > lastObservationAt) at else lastObservationAt,
        lastHealthyAt = if (registration == RegistrationStatus.ACTIVE && outageStartedAt == null &&
            (lastHealthyAt == null || at > lastHealthyAt)) at else lastHealthyAt,
    )
    }

    fun presenceConfirmed(now: Instant, zone: ZoneId): Boolean =
        registration == RegistrationStatus.ACTIVE && outageStartedAt == null &&
            lastObservationAt?.let { observed ->
                observed >= (recoveryBoundaryAt ?: return false) &&
                    observed <= now &&
                    observed.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
            } == true

    fun review(date: LocalDate): CoverageLedger = copy(reviewedDates = reviewedDates + date)

    val unreviewedUnknownDates: Set<LocalDate> get() = unknownDates - reviewedDates

    private fun dates(start: LocalDate, end: LocalDate): Set<LocalDate> {
        if (end < start) return emptySet()
        return generateSequence(start) { if (it < end) it.plusDays(1) else null }.toSet()
    }
}
