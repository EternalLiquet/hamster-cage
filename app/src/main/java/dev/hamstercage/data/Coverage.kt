package dev.hamstercage.data

import java.time.LocalDate

/** An unresolved monitoring gap covers every intervening date, including today. */
internal fun unknownCoverageDates(
    persisted: Set<String>, gapSince: String?, today: LocalDate, verified: Set<String>,
): Set<LocalDate> {
    val ongoingGap = gapSince?.let { start ->
        generateSequence(LocalDate.parse(start)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }.map { it.toString() }.toSet()
    }.orEmpty()
    return ((persisted + ongoingGap) - verified).map(LocalDate::parse).toSet()
}
