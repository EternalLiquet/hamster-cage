package dev.hamstercage.ui

import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.AttendanceResult
import dev.hamstercage.domain.DepartureEstimate
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class DashboardPresence(val label: String, val needsReview: Boolean, val sessionStarted: Instant?, val manual: Boolean)

/** Registration alone never establishes outside state; a current trustworthy observation is needed. */
fun dashboardPresence(input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean): DashboardPresence {
    val eligibleIds = input.offices.filter { it.enabled && it.countsTowardAttendance }.map { it.id }.toSet()
    val open = result.sessions.filter { it.isOpen && it.officeId in eligibleIds }
    val review = result.reviews.any { it.reason !in setOf(ReviewReason.OPEN_SESSION, ReviewReason.DUPLICATE_EVENT) } || open.size > 1
    // An old interval closed for review by a fresh presence observation does not
    // make the newly observed current office ambiguous. Keep its review notice.
    val liveReview = result.reviews.any { it.reason !in setOf(ReviewReason.OPEN_SESSION,
        ReviewReason.DUPLICATE_EVENT, ReviewReason.UNCONFIRMED_GAP) } || open.size > 1
    val today = input.now.atZone(input.policy.zoneId).toLocalDate()
    val latest = input.events.filter { it.officeId in eligibleIds && it.at <= input.now }.maxByOrNull { it.at }
    val label = when {
        !trackingReady -> "Office state unknown"
        liveReview -> "Needs review"
        open.size == 1 -> if (open.single().manualSessionId != null) "Manual session active" else
            "In ${dashboardOfficeName(input.offices.single { it.id == open.single().officeId }.name)}"
        latest?.transition in setOf(Transition.EXIT, Transition.ABSENCE) &&
            latest?.at?.atZone(input.policy.zoneId)?.toLocalDate() == today -> "Outside office"
        else -> "Office state unknown"
    }
    return DashboardPresence(label, review, open.mapNotNull { it.start }.minOrNull(), open.any { it.manualSessionId != null || it.correctionId != null })
}

/** User-controlled labels cannot inject directional/control characters into presence text. */
internal fun dashboardOfficeName(name: String): String = name
    .filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
    .take(120).ifBlank { "configured office" }

/** Display completed credited minutes; round a remaining deficit up so it never reads as met early. */
fun minutesText(value: Double): String {
    val minutes = floor(abs(value)).toLong()
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
fun balanceText(value: Double): String = when {
    value < 0 -> "−" + minutesText(ceil(-value))
    value > 0 -> "+" + minutesText(value)
    else -> "0m"
}
fun instantText(value: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("h:mm a").withZone(zone).format(value)
fun departureTimeText(value: Instant, now: Instant, zone: ZoneId): String {
    val minute = value.truncatedTo(ChronoUnit.MINUTES)
    val rounded = if (minute < value) minute.plusSeconds(60) else minute
    val pattern = if (rounded.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) "h:mm a" else "EEE, MMM d, h:mm a"
    return DateTimeFormatter.ofPattern(pattern).withZone(zone).format(rounded)
}

/** A daily projection remains useful with unknown coverage, but an unsafe bound has no time. */
fun todayLeaveText(estimate: DepartureEstimate, trackingReady: Boolean, now: Instant, zone: ZoneId): String = when (estimate.status) {
    DepartureStatus.TARGET_SATISFIED -> "You can leave now"
    DepartureStatus.ESTIMATED -> if (trackingReady) "You can leave at ${departureTimeText(estimate.estimatedExitAt!!, now, zone)}"
        else "Confirm office detection to see a leave time"
    DepartureStatus.NOT_IN_OFFICE -> "Start an eligible office session to see a leave time"
    DepartureStatus.OVERLAPPING_SESSIONS -> "Review overlapping active sessions in History"
    DepartureStatus.NEEDS_REVIEW -> when {
        ReviewReason.FUTURE_EVENT in estimate.reviewReasons ->
            "An office observation is in the future. Check the device clock, then review History"
        ReviewReason.STALE_OPEN_SESSION in estimate.reviewReasons ->
            "The open session exceeds its safe length. Review its bounds in History"
        estimate.reviewReasons.any { it in setOf(ReviewReason.MISSING_ENTER, ReviewReason.REPEATED_ENTER, ReviewReason.ZERO_LENGTH_SESSION) } ->
            "Office entry and exit boundaries conflict. Review the session in History"
        estimate.reviewReasons.any { it in setOf(ReviewReason.INVALID_CORRECTION, ReviewReason.ORPHAN_CORRECTION) } ->
            "An attendance correction is unresolved. Review it in History"
        estimate.reviewReasons.any { it in setOf(ReviewReason.INVALID_MANUAL_SESSION, ReviewReason.CONFLICTING_MANUAL_SESSION_ID) } ->
            "Manual session bounds conflict. Review them in History"
        ReviewReason.UNKNOWN_OFFICE in estimate.reviewReasons ->
            "An observation names an unknown office. Review office setup and History"
        else -> "Conflicting attendance evidence blocks today's estimate. Review History"
    }
    DepartureStatus.UNREACHABLE_IN_WINDOW ->
        "Not enough time remains before today's boundary or the session limit. Review the target in Settings or session in History"
    DepartureStatus.OUTSIDE_WINDOW ->
        "Device time is outside this target window. Check the clock and policy timezone in Settings"
    DepartureStatus.INCOMPLETE_HISTORY -> "Review missing coverage in History"
}
