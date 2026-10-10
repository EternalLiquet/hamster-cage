package dev.hamstercage.ui

import dev.hamstercage.domain.ADVISORY_REVIEW_REASONS
import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.AttendanceResult
import dev.hamstercage.domain.DepartureEstimate
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.EstimateAssumption
import dev.hamstercage.domain.PeriodSummary
import dev.hamstercage.domain.ReviewReason
import dev.hamstercage.domain.TargetWindow
import dev.hamstercage.domain.Transition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** [needsReview] concerns today; [otherReview] is a review elsewhere that today's numbers don't depend on. */
data class DashboardPresence(val label: String, val needsReview: Boolean, val sessionStarted: Instant?, val manual: Boolean,
    val otherReview: Boolean = false)

/** Registration alone never establishes outside state; a current trustworthy observation is needed. */
fun dashboardPresence(input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean): DashboardPresence {
    val eligibleIds = input.offices.filter { it.enabled && it.countsTowardAttendance }.map { it.id }.toSet()
    val open = result.sessions.filter { it.isOpen && it.officeId in eligibleIds }
    val candidateOpen = open.size == 1 && open.single().sourceEventIds.any { it in input.candidateExitIds }
    val todayEstimate = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
    val today = input.now.atZone(input.policy.zoneId).toLocalDate()
    // Doubt that can only make today's estimate later stays visible in History, but it
    // is not a reason to send the user there before they can rely on a leave time.
    val actionable = result.reviews.filter { it.reason != ReviewReason.OPEN_SESSION &&
        it.reason !in ADVISORY_REVIEW_REASONS && it.sessionId !in todayEstimate.nonBlockingSessionIds }
    // Reviews about other days stay in History and may affect longer targets, not today.
    val (todayReviews, otherReviews) = actionable.partition { AttendanceEngine.reviewAffectsDate(input, result, it, today) }
    val review = todayReviews.isNotEmpty() || open.size > 1
    // An old interval closed for review by a fresh presence observation does not
    // make the newly observed current office ambiguous. Keep its review notice.
    val liveReview = todayReviews.any { it.reason != ReviewReason.UNCONFIRMED_GAP &&
        !(candidateOpen && it.sessionId == open.single().id &&
            it.reason == ReviewReason.UNCONFIRMED_BOUNDARY) } || open.size > 1
    val safeLiveProjection = open.size == 1 &&
        todayEstimate.status in setOf(DepartureStatus.ESTIMATED, DepartureStatus.TARGET_SATISFIED)
    val latest = input.events.filter { it.officeId in eligibleIds && it.at <= input.now }.maxByOrNull { it.at }
    val label = when {
        !trackingReady -> "Office state unknown"
        liveReview && !safeLiveProjection -> "Needs review"
        candidateOpen -> "Checking whether you've left"
        open.size == 1 -> if (open.single().manualSessionId != null) "Using times you entered" else
            "In ${dashboardOfficeName(input.offices.single { it.id == open.single().officeId }.name)}"
        latest?.transition in setOf(Transition.EXIT, Transition.ABSENCE) &&
            latest?.at?.atZone(input.policy.zoneId)?.toLocalDate() == today ->
            // A departure that was never confirmed is likely, not certain.
            if (latest?.id in input.unconfirmedExitIds) "Probably outside office" else "Outside office"
        else -> "Office state unknown"
    }
    return DashboardPresence(label, review, open.mapNotNull { it.start }.minOrNull(),
        open.any { it.manualSessionId != null || it.hasActiveCorrection }, otherReviews.isNotEmpty())
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

/**
 * Today's one-line answer to "can I go?", in everyday words with one next step.
 * A projection stays useful with unknown coverage, but unless [confident] (detection confirmed, today
 * fully tracked, nothing to review) it is shown as an estimate, never as a plain "you can leave".
 * An unsafe or unconfirmed bound has no time at all. A qualified estimate names its assumption; each
 * assumption can only make the time later than needed, so it is never stated as a plain instruction.
 */
fun todayLeaveText(estimate: DepartureEstimate, trackingReady: Boolean, now: Instant, zone: ZoneId,
    confident: Boolean): String = when (estimate.status) {
    DepartureStatus.TARGET_SATISFIED -> if (confident) "Goal met. You can leave now."
        else "Goal may be met. Check today's timeline before you leave."
    DepartureStatus.ESTIMATED -> when {
        !trackingReady -> "No leave time yet: your location isn't confirmed. Check office setup."
        estimate.assumptions.isNotEmpty() -> {
            val time = departureTimeText(estimate.estimatedExitAt!!, now, zone)
            val sooner = EstimateAssumption.EARLIER_EXIT_UNCONFIRMED in estimate.assumptions
            if (EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED in estimate.assumptions)
                "If you're still here, leave at about $time${if (sooner) ", or sooner if you didn't really leave earlier." else "."}"
            else "Estimated leave time: about $time. It may be sooner if you didn't really leave earlier."
        }
        confident -> "You can leave at ${departureTimeText(estimate.estimatedExitAt!!, now, zone)}"
        else -> "Estimated leave time: about ${departureTimeText(estimate.estimatedExitAt!!, now, zone)}. Check today's timeline."
    }
    DepartureStatus.NOT_IN_OFFICE -> "A leave time appears once you're at the office."
    DepartureStatus.OVERLAPPING_SESSIONS -> "Two office visits overlap. Fix them in History to see a leave time."
    DepartureStatus.NEEDS_REVIEW -> when {
        ReviewReason.FUTURE_EVENT in estimate.reviewReasons ->
            "A visit is dated in the future. Check your phone's clock, then History."
        ReviewReason.STALE_OPEN_SESSION in estimate.reviewReasons ->
            "Today's visit has run unusually long. Check it in History."
        estimate.reviewReasons.any { it in setOf(ReviewReason.MISSING_ENTER, ReviewReason.REPEATED_ENTER,
            ReviewReason.TRANSIENT_BOUNDARY, ReviewReason.ZERO_LENGTH_SESSION) } ->
            "Today's arrival and leaving times don't line up. Check them in History."
        estimate.reviewReasons.any { it in setOf(ReviewReason.INVALID_CORRECTION, ReviewReason.ORPHAN_CORRECTION) } ->
            "One of your time edits needs a look. Open it in History."
        estimate.reviewReasons.any { it in setOf(ReviewReason.INVALID_MANUAL_SESSION, ReviewReason.CONFLICTING_MANUAL_SESSION_ID) } ->
            "A visit you entered has a problem. Check it in History."
        ReviewReason.UNKNOWN_OFFICE in estimate.reviewReasons ->
            "A visit belongs to an office that's no longer set up. Check Offices and History."
        else -> "Today's records disagree, so there's no leave time yet. Check History."
    }
    DepartureStatus.UNREACHABLE_IN_WINDOW ->
        "There isn't enough time left today to reach the goal. Check your goal in Settings or today's visit in History."
    DepartureStatus.OUTSIDE_WINDOW ->
        "Your phone's time doesn't match today's goal. Check the clock and time zone in Settings."
    DepartureStatus.INCOMPLETE_HISTORY -> "Part of today wasn't tracked. Check today's timeline."
}

/** One row of the expanded departure targets. A qualified estimate keeps its condition here too. */
fun departureTargetText(estimate: DepartureEstimate, trackingReady: Boolean, now: Instant, zone: ZoneId): String =
    when (estimate.status) {
        DepartureStatus.TARGET_SATISFIED -> "Already satisfied"
        DepartureStatus.ESTIMATED -> if (!trackingReady) "Detection unconfirmed" else {
            val still = EstimateAssumption.STILL_PRESENT_WHILE_EXIT_CHECKED in estimate.assumptions
            val sooner = EstimateAssumption.EARLIER_EXIT_UNCONFIRMED in estimate.assumptions
            "About ${departureTimeText(estimate.estimatedExitAt!!, now, zone)}" +
                (if (still) " if you're still here" else "") + (if (sooner) ", maybe sooner" else "")
        }
        DepartureStatus.NOT_IN_OFFICE -> if (!trackingReady) "Office state unknown" else "No active session"
        DepartureStatus.NEEDS_REVIEW -> "Needs review"
        DepartureStatus.INCOMPLETE_HISTORY -> "History incomplete"
        DepartureStatus.OUTSIDE_WINDOW -> "Outside target window"
        DepartureStatus.UNREACHABLE_IN_WINDOW -> "Beyond this window"
        DepartureStatus.OVERLAPPING_SESSIONS -> "Overlapping active sessions"
    }

/**
 * A leave time is stated plainly only when detection is confirmed, today is fully tracked and the current
 * visit has no departure awaiting confirmation. Reviews that block today already remove the time (NEEDS_REVIEW);
 * a review limited to an earlier day does not make today's projection uncertain.
 */
fun todayLeaveConfident(input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean): Boolean {
    val eligible = input.offices.filter { it.enabled && it.countsTowardAttendance }.map { it.id }.toSet()
    val exitPending = result.sessions.any { it.isOpen && it.officeId in eligible &&
        it.sourceEventIds.any { id -> id in input.candidateExitIds } }
    return trackingReady && !exitPending &&
        AttendanceEngine.daily(input, result, input.now.atZone(input.policy.zoneId).toLocalDate()).hasCompleteHistory
}

/** The label beside today's credited number; uncertain totals always read as estimates. */
fun todayCreditNote(historyComplete: Boolean, liveUnconfirmed: Boolean): String = when {
    !historyComplete -> "Estimate · part of today wasn't tracked"
    liveUnconfirmed -> "Estimate · we can't confirm you're still there"
    else -> "Counted toward today's goal"
}

/** Time still needed today. Unknown stays unknown; a remaining deficit rounds up so it never reads as met early. */
fun todayRemainingText(daily: PeriodSummary, liveUnconfirmed: Boolean): String = when {
    !daily.hasCompleteHistory -> "Unknown"
    daily.balanceMinutes < 0 -> (if (liveUnconfirmed) "About " else "") + minutesText(ceil(-daily.balanceMinutes))
    // The open-session total may include time after you left, so a met goal is only possible.
    liveUnconfirmed -> "Maybe none (goal may be met)"
    daily.balanceMinutes >= 1 -> "None, goal met (+${minutesText(daily.balanceMinutes)})"
    else -> "None, goal met"
}
