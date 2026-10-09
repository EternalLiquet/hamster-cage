package dev.hamstercage.domain

import java.time.Instant

enum class DepartureStatus {
    TARGET_SATISFIED, ESTIMATED, NOT_IN_OFFICE, NEEDS_REVIEW, INCOMPLETE_HISTORY,
    OUTSIDE_WINDOW, UNREACHABLE_IN_WINDOW, OVERLAPPING_SESSIONS,
}

/**
 * What a qualified Today estimate assumes. Each can only make the estimate later than
 * necessary, never earlier; none confirms attendance or changes credited time.
 */
enum class EstimateAssumption {
    /** A possible departure from the current visit is still being checked; credit stops at it meanwhile. */
    STILL_PRESENT_WHILE_EXIT_CHECKED,
    /** An earlier visit today ends at an observed but unconfirmed EXIT; it gets no credit beyond it. */
    EARLIER_EXIT_UNCONFIRMED,
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
    /** Assumptions behind a qualified estimate; empty for an unqualified one. */
    val assumptions: Set<EstimateAssumption> = emptySet(),
    /** Sessions whose open reviews cannot make this estimate too early. They stay reviewable in History. */
    val nonBlockingSessionIds: Set<String> = emptySet(),
) {
    val targetName: String get() = when (target) {
        TargetWindow.TODAY -> "Today"
        TargetWindow.WEEK_TO_DATE -> "Week through today"
        TargetWindow.FULL_WEEK -> "Full week"
        TargetWindow.ROLLING_30 -> "Rolling 30 days"
        TargetWindow.ROLLING_90 -> "Rolling 90 days"
    }
}
