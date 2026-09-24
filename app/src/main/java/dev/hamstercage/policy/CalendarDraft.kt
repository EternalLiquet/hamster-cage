package dev.hamstercage.policy

import dev.hamstercage.domain.ExcludedDate
import dev.hamstercage.domain.ExclusionReason
import java.time.DateTimeException
import java.time.LocalDate

fun calendarDate(text: String): LocalDate = try { LocalDate.parse(text.trim()) }
catch (_: DateTimeException) { throw IllegalArgumentException("Enter a valid date using YYYY-MM-DD.") }

fun excludedDate(date: String, reason: ExclusionReason, note: String): ExcludedDate {
    require(note.length <= 2000) { "Keep the note under 2000 characters." }
    return ExcludedDate(calendarDate(date), reason, note.trim())
}
