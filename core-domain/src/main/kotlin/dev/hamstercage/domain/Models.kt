package dev.hamstercage.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Office(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f,
    val enabled: Boolean = true,
    val countsTowardAttendance: Boolean = true,
    val entryGraceMinutes: Int = 5,
    val exitGraceMinutes: Int = 5,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMeters.isFinite() && radiusMeters in 50f..5000f)
        require(entryGraceMinutes in 0..120 && exitGraceMinutes in 0..120)
    }
}

/** PRESENCE is a current observation, not a claim that entry happened at that instant. */
enum class Transition { ENTER, EXIT, PRESENCE, ABSENCE }
data class RawEvent(val id: String, val officeId: String, val transition: Transition, val at: Instant)
/** User-entered evidence is a complete interval, never synthetic geofence transitions. */
data class ManualSession(
    val id: String,
    val officeId: String,
    val start: Instant,
    val end: Instant?,
    val createdAt: Instant,
    val note: String = "",
)
enum class ExclusionReason { BANK_HOLIDAY, COMPANY_CLOSURE, PTO, OTHER_EXCUSED }
data class ExcludedDate(val date: LocalDate, val reason: ExclusionReason, val note: String = "")
data class Policy(
    val zoneId: ZoneId = ZoneId.of("America/New_York"),
    val targetMinutesPerDay: Int = 360,
    val expectedWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val excludedDates: List<ExcludedDate> = emptyList(),
    val wfhDates: Set<LocalDate> = emptySet(),
    val shortGapMinutes: Int = 10,
    val maxOpenSessionHours: Int = 16,
) {
    init {
        require(targetMinutesPerDay in 1..1440)
        require(shortGapMinutes in 0..120)
        require(maxOpenSessionHours in 1..24)
    }
    fun isExpected(date: LocalDate): Boolean = date.dayOfWeek in expectedWeekdays && excludedDates.none { it.date == date }
}

/** Effective observed bounds: per-office arrival walking delay is still applied. Latest correction wins. */
data class Correction(
    val id: String,
    val sessionId: String,
    val start: Instant,
    val end: Instant?,
    val createdAt: Instant,
    val note: String = "",
    /** Append-only revert marker. Bounds are retained audit payload and ignored by derivation. */
    val revertToOriginal: Boolean = false,
    /** Durable append order. Zero is legacy data ordered by timestamp/id. */
    val appendSequence: Long = 0,
)
enum class Confidence { HIGH, MEDIUM, LOW, MANUAL }
enum class ReviewReason { DUPLICATE_EVENT, REPEATED_ENTER, TRANSIENT_BOUNDARY, MISSING_ENTER, OPEN_SESSION, STALE_OPEN_SESSION, UNCONFIRMED_GAP, UNCONFIRMED_BOUNDARY, INVALID_CORRECTION, ORPHAN_CORRECTION, UNKNOWN_OFFICE, FUTURE_EVENT, CONFLICTING_EVENT_ID, INVALID_EVENT, ZERO_LENGTH_SESSION, INVALID_MANUAL_SESSION, CONFLICTING_MANUAL_SESSION_ID }
data class ReviewItem(val reason: ReviewReason, val sourceEventIds: Set<String> = emptySet(), val sessionId: String? = null)
data class Session(
    val id: String,
    val officeId: String,
    val start: Instant?,
    val end: Instant?,
    val sourceEventIds: Set<String>,
    val confidence: Confidence,
    val reviewReasons: Set<ReviewReason> = emptySet(),
    val correctionId: String? = null,
    val manualSessionId: String? = null,
    val correctionReverted: Boolean = false,
) {
    val isOpen: Boolean get() = start != null && end == null
    /** A later replay or missing boundary must not orphan edits to retained source facts. */
    val correctionTargetIds: Set<String> get() = setOf(id) + sourceEventIds.map { "session:$it" }
}
data class CreditedInterval(val start: Instant, val end: Instant, val sessionIds: Set<String>, val reconciledGap: Boolean = false) {
    init { require(!end.isBefore(start)) }
    val minutes: Double get() = Duration.between(start, end).elapsedMinutes()
}

/** Avoid overflowing a Long millisecond total for malformed or extreme source intervals. */
internal fun Duration.elapsedMinutes(): Double = seconds / 60.0 + nano / 60_000_000_000.0
data class AttendanceInput(
    val offices: List<Office>,
    val events: List<RawEvent>,
    val corrections: List<Correction> = emptyList(),
    val policy: Policy = Policy(),
    val now: Instant,
    /** First date with reliably captured history. Null means coverage is unknown. */
    val historyStartDate: LocalDate? = null,
    val unknownDates: Set<LocalDate> = emptySet(),
    val manualSessions: List<ManualSession> = emptyList(),
    /** Foreground recovery fixes intentionally split an old, unverified open visit. */
    val recoveryPresenceIds: Set<String> = emptySet(),
    /** A fresh, accurate one-shot fix may corroborate an immediately preceding EXIT. */
    val adaptivePresenceIds: Set<String> = emptySet(),
    /** Only the latest uncorroborated geofence EXIT is a live boundary question. */
    val unconfirmedExitIds: Set<String> = emptySet(),
    /** An adaptive fix after a pre-recovery opening also invalidates the old credited span. */
    val unsafeRecoveryPresenceIds: Set<String> = emptySet(),
)
data class AttendanceResult(val sessions: List<Session>, val intervals: List<CreditedInterval>, val reviews: List<ReviewItem>)
