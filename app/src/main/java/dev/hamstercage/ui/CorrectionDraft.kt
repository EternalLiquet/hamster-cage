package dev.hamstercage.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class CorrectionBounds(val start: Instant, val end: Instant?)
data class LocalBoundary(val dateTime: LocalDateTime, val overlapChoice: Int? = null)

/** No silent DST adjustment: a missing hour is rejected and a repeated hour needs a choice. */
fun resolveLocalBoundary(boundary: LocalBoundary, zone: ZoneId): Instant {
    val offsets = zone.rules.getValidOffsets(boundary.dateTime)
    val offset = when (offsets.size) {
        0 -> {
            val next = zone.rules.getTransition(boundary.dateTime)?.dateTimeAfter
            val hint = next?.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")) ?: "a later valid time"
            throw IllegalArgumentException("This local time does not exist because clocks move forward. Choose $hint or another valid time.")
        }
        1 -> offsets.single()
        else -> offsets.getOrNull(boundary.overlapChoice ?: -1)
            ?: throw IllegalArgumentException("Choose the first or second occurrence of this repeated local time.")
    }
    return boundary.dateTime.atOffset(offset).toInstant()
}

fun overlapOffsets(local: LocalDateTime, zone: ZoneId): List<ZoneOffset> =
    zone.rules.getValidOffsets(local).takeIf { it.size > 1 }.orEmpty()

fun overlapChoiceFor(instant: Instant?, zone: ZoneId): Int? {
    if (instant == null) return null
    val zoned = instant.atZone(zone)
    return overlapOffsets(zoned.toLocalDateTime(), zone).indexOf(zoned.offset).takeIf { it >= 0 }
}

fun localCorrectionBounds(start: LocalBoundary, end: LocalBoundary?, zone: ZoneId, now: Instant): CorrectionBounds =
    validatedBounds(resolveLocalBoundary(start, zone), end?.let { resolveLocalBoundary(it, zone) }, now)

private fun validatedBounds(from: Instant, until: Instant?, now: Instant): CorrectionBounds {
    require(Instant.ofEpochMilli(from.toEpochMilli()) == from &&
        (until == null || Instant.ofEpochMilli(until.toEpochMilli()) == until)) { "Choose a time to the nearest millisecond." }
    require(until == null || until > from) { "End must follow start." }
    require(from <= now && (until == null || until <= now)) { "Attendance bounds cannot be in the future." }
    return CorrectionBounds(from, until)
}
fun correctionBounds(start: String, end: String, now: Instant): CorrectionBounds {
    fun parse(text: String): Instant = try { OffsetDateTime.parse(text.trim()).toInstant().also {
        require(Instant.ofEpochMilli(it.toEpochMilli()) == it)
    } } catch (_: Exception) { throw IllegalArgumentException("Use date, time and UTC offset, such as 2026-09-23T09:00-04:00.") }
    val from = parse(start)
    val until = if (end.isBlank()) null else parse(end)
    return validatedBounds(from, until, now)
}

internal fun nextCorrectionSequence(current: Long): Long {
    require(current in 0 until Long.MAX_VALUE) { "Correction order is unavailable. Your existing facts were kept." }
    return current + 1
}
