package dev.hamstercage.ui

import java.time.Instant
import java.time.OffsetDateTime

data class CorrectionBounds(val start: Instant, val end: Instant?)
fun correctionBounds(start: String, end: String, now: Instant): CorrectionBounds {
    fun parse(text: String): Instant = try { OffsetDateTime.parse(text.trim()).toInstant().also {
        require(Instant.ofEpochMilli(it.toEpochMilli()) == it)
    } } catch (_: Exception) { throw IllegalArgumentException("Use date, time and UTC offset, such as 2026-09-23T09:00-04:00.") }
    val from = parse(start)
    val until = if (end.isBlank()) null else parse(end)
    require(until == null || until > from) { "End must follow start." }
    require(from <= now && (until == null || until <= now)) { "Attendance bounds cannot be in the future." }
    return CorrectionBounds(from, until)
}

internal fun nextCorrectionSequence(current: Long): Long {
    require(current in 0 until Long.MAX_VALUE) { "Correction order is unavailable. Your existing facts were kept." }
    return current + 1
}
