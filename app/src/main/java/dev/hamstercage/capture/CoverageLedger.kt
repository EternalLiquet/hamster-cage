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
) {
    /** A new process cannot assume an old OS registration survived a force-stop. */
    fun processStarted(now: Instant, zone: ZoneId): CoverageLedger =
        if (lastHealthyAt == null && outageStartedAt == null) this else outage(now, zone)

    fun outage(now: Instant, zone: ZoneId): CoverageLedger = outage(lastHealthyAt ?: now, now, zone)

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
            recoveryBoundaryAt = null, registration = RegistrationStatus.FAILED,
        )
        val newlyAffected = dates(firstDate, through)
        return copy(unknownDates = unknownDates + newlyAffected, reviewedDates = reviewedDates - newlyAffected,
            outageStartedAt = first, outageRecordedThrough = through, recoveryBoundaryAt = null,
            registration = RegistrationStatus.FAILED)
    }

    fun registrationSucceeded(now: Instant, zone: ZoneId, hasOffices: Boolean): CoverageLedger {
        if (!hasOffices) return outage(now, zone).copy(registration = RegistrationStatus.NO_OFFICES)
        val recovered = if (outageStartedAt != null) outage(now, zone) else this
        // A registration request alone is not evidence that any observation was delivered.
        val firstObservedDay = lastObservationAt?.atZone(zone)?.toLocalDate()
        return recovered.copy(historyStartDate = historyStartDate ?: firstObservedDay,
            unknownDates = if (historyStartDate == null && firstObservedDay != null)
                recovered.unknownDates + firstObservedDay else recovered.unknownDates,
            lastHealthyAt = now, outageStartedAt = null, outageRecordedThrough = null,
            recoveryBoundaryAt = if (outageStartedAt != null) now else recoveryBoundaryAt ?: now,
            registration = RegistrationStatus.ACTIVE)
    }

    fun observed(at: Instant): CoverageLedger = copy(
        lastObservationAt = if (lastObservationAt == null || at > lastObservationAt) at else lastObservationAt,
        lastHealthyAt = if (registration == RegistrationStatus.ACTIVE && outageStartedAt == null &&
            (lastHealthyAt == null || at > lastHealthyAt)) at else lastHealthyAt,
    )

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
