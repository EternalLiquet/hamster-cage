package dev.hamstercage.capture

/**
 * Whether office registration can be trusted to have continued across a new process.
 *
 * Android reclaims background processes routinely; Play services keeps delivering geofences
 * registered with a PendingIntent the system still holds. Registration is lost after a reboot,
 * an app update, a data clear or a force-stop. Any recorded sign of those is an outage. A
 * surviving token rules out a reboot and (expected) an app data clear, and from Android 15 a
 * force-stop, so with the app update time unchanged it is trusted as continuous monitoring: ordinary
 * reclamation is by far the likeliest cause, and treating it as an outage withholds confirmed
 * presence for the rest of the day (#142). Before Android 15 a user-requested exit may have
 * been a force-stop, which leaves the token, so it always stays an outage: a background start
 * cannot rule that out, because opening the app after a force-stop lets overdue work run first.
 * Accepted risk: Android 8–10 record no exit reason, so a force-stop there is undetectable, as
 * is one on Android 11–14 applied while no process was running; and clearing or updating Play
 * services may drop geofences while the app's token remains.
 */
enum class ProcessContinuity { SURVIVED, LOST, UNKNOWN }

/** How the previous process ended, from Android 11+ exit records. */
enum class PreviousExit {
    /** Reclaimed or ended by the system without touching the app's registrations. */
    RECLAIMED,
    /** User stop, permission change or package change: registrations may be gone. */
    STOPPED_OR_CHANGED,
    /**
     * Ended at the user's request: Settings force-stop, a task-manager stop, or on some devices
     * removal from recents. From Android 15 a force-stop also cancels the app's PendingIntents
     * and is reported through `ApplicationStartInfo`, so the token check covers it there.
     */
    USER_REQUESTED,
    /** No usable record for the process that last confirmed capture health. */
    UNKNOWN,
}

/**
 * @param tokenPresent the geofence PendingIntent still exists (null: could not be inspected).
 * @param rebooted boot count differs from the one recorded with the last registration (null: unknown).
 * @param packageReplaced app update time differs from the recorded one (null: unknown).
 * @param forceStopReported Android 15+ start record says the app was force-stopped (null: unavailable).
 * @param backgroundStart this process began capture work from a background job. Not decisive:
 *   opening the app after a force-stop lets overdue work run first, so it cannot rule one out.
 * @param tokensCancelledOnStop the platform cancels PendingIntents on force-stop (Android 15+).
 */
fun processContinuity(
    tokenPresent: Boolean?, rebooted: Boolean?, packageReplaced: Boolean?, previousExit: PreviousExit,
    forceStopReported: Boolean?, backgroundStart: Boolean, tokensCancelledOnStop: Boolean,
): ProcessContinuity = when {
    tokenPresent == false || rebooted == true || packageReplaced == true || forceStopReported == true ||
        previousExit == PreviousExit.STOPPED_OR_CHANGED -> ProcessContinuity.LOST
    previousExit == PreviousExit.USER_REQUESTED && !tokensCancelledOnStop -> ProcessContinuity.LOST
    // The token rules out a reboot (so an unreadable boot count does not matter), but an update
    // keeps it, so the app update time must be known to match.
    tokenPresent == true && packageReplaced == false -> ProcessContinuity.SURVIVED
    else -> ProcessContinuity.UNKNOWN
}

/** Device boot count and app update time recorded with a successful registration. */
data class ProcessIdentity(val bootCount: Int?, val packageUpdatedAt: Long?)
