package dev.hamstercage.domain

import java.time.LocalDate

enum class TargetWindow { TODAY, WEEK_TO_DATE, FULL_WEEK, ROLLING_30, ROLLING_90 }

/** Credit is the recorded amount; incomplete coverage must remain visible alongside it. */
data class PeriodSummary(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val creditedMinutes: Double,
    val expectedWorkdays: Int,
    val requiredMinutes: Int,
    val balanceMinutes: Double,
    val averageMinutes: Double?,
    val knownExpectedWorkdays: Int,
    val unknownExpectedWorkdays: Int,
    val projectedExpectedWorkdays: Int = 0,
    val unknownCalendarDays: Int = unknownExpectedWorkdays,
) {
    val hasCompleteHistory: Boolean get() = unknownCalendarDays == 0
}

/** Dates safe to use for an actionable denominator, separate from the full policy window. */
data class ReportingCoverage(
    val firstReliableDay: LocalDate?,
    val coveredDates: Set<LocalDate>,
    val unavailableBeforeTracking: Set<LocalDate>,
    val unknownAfterTracking: Set<LocalDate>,
    val coveredExpectedWorkdays: Int,
    val coveredRequiredMinutes: Int,
)
