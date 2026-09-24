package dev.hamstercage.domain

import java.time.Instant

enum class DepartureStatus {
    TARGET_SATISFIED, ESTIMATED, NOT_IN_OFFICE, NEEDS_REVIEW, INCOMPLETE_HISTORY,
    OUTSIDE_WINDOW, UNREACHABLE_IN_WINDOW, OVERLAPPING_SESSIONS,
}

/** A projection only: neither timestamp is an observed fact or future aggregate credit. */
data class DepartureEstimate(
    val target: TargetWindow,
    val status: DepartureStatus,
    val remainingMinutes: Double,
    val creditedTargetAt: Instant? = null,
    val estimatedExitAt: Instant? = null,
    /** Reasons that block a projection; empty for a bounded estimate. */
    val reviewReasons: Set<ReviewReason> = emptySet(),
) {
    val targetName: String get() = when (target) {
        TargetWindow.TODAY -> "Today"
        TargetWindow.WEEK_TO_DATE -> "Week through today"
        TargetWindow.FULL_WEEK -> "Full week"
        TargetWindow.ROLLING_30 -> "Rolling 30 days"
        TargetWindow.ROLLING_90 -> "Rolling 90 days"
    }
}
