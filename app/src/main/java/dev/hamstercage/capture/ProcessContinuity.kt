package dev.hamstercage.capture

/**
 * Whether office registration can be trusted to have continued across a new process.
 *
 * Android reclaims background processes routinely; Play services keeps delivering geofences
 * registered with a PendingIntent the system still holds. Registration is lost after a reboot,
 * an app update, a data clear or a force-stop. Before Android 15 a force-stop does not cancel
 * PendingIntents, so a surviving token alone cannot rule one out there. The decision therefore
 * needs positive evidence, and anything it cannot establish is treated as an outage.
 */
enum class ProcessContinuity { SURVIVED, LOST, UNKNOWN }

/** How the previous process ended, from Android 11+ exit records. */
enum class PreviousExit {
    /** Reclaimed or ended by the system without touching the app's registrations. */
    RECLAIMED,
    /** User stop, permission change or package change: registrations may be gone. */
    STOPPED_OR_CHANGED,
    /** No usable record for the process that last confirmed capture health. */
    UNKNOWN,
}

/**
 * @param tokenPresent the geofence PendingIntent still exists (null: could not be inspected).
 * @param rebooted boot count differs from the one recorded with the last registration (null: unknown).
 * @param packageReplaced app update time differs from the recorded one (null: unknown).
 * @param forceStopReported Android 15+ start record says the app was force-stopped (null: unavailable).
 * @param backgroundStart this process began capture work from a background job; a force-stopped
 *   app cannot run background jobs until the user opens it again.
 * @param tokensCancelledOnStop the platform cancels PendingIntents on force-stop (Android 15+).
 */
fun processContinuity(
    tokenPresent: Boolean?, rebooted: Boolean?, packageReplaced: Boolean?, previousExit: PreviousExit,
    forceStopReported: Boolean?, backgroundStart: Boolean, tokensCancelledOnStop: Boolean,
): ProcessContinuity = when {
    tokenPresent == false || rebooted == true || packageReplaced == true || forceStopReported == true ||
        previousExit == PreviousExit.STOPPED_OR_CHANGED -> ProcessContinuity.LOST
    tokenPresent == null || rebooted == null || packageReplaced == null -> ProcessContinuity.UNKNOWN
    backgroundStart -> ProcessContinuity.SURVIVED
    tokensCancelledOnStop -> ProcessContinuity.SURVIVED
    previousExit == PreviousExit.RECLAIMED -> ProcessContinuity.SURVIVED
    else -> ProcessContinuity.UNKNOWN
}

/** Device boot count and app update time recorded with a successful registration. */
data class ProcessIdentity(val bootCount: Int?, val packageUpdatedAt: Long?)
