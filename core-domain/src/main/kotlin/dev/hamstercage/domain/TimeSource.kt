package dev.hamstercage.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Explicit time dependency. Domain code never reads the Android or system clock directly. */
fun interface TimeSource {
    fun now(): Instant

    fun localDate(zoneId: ZoneId): LocalDate = now().atZone(zoneId).toLocalDate()
}
