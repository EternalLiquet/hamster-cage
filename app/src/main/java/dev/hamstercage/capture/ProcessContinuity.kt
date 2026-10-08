package dev.hamstercage.capture

/**
 * Whether office registration can be trusted to have continued across a process start.
 * Android keeps a PendingIntent token in the system when its app process is reclaimed, so
 * Play services geofences registered with it keep delivering. A force-stop, reboot, app
 * update or data clear removes the token (and the platform discards the geofences), so a
 * missing token, or an exit the user requested, is a genuine capture outage.
 */
enum class ProcessContinuity { SURVIVED, LOST, UNKNOWN }

/** [pendingIntentPresent] is null when the token could not be inspected. */
fun processContinuity(pendingIntentPresent: Boolean?, userStopped: Boolean): ProcessContinuity = when {
    userStopped || pendingIntentPresent == false -> ProcessContinuity.LOST
    pendingIntentPresent == true -> ProcessContinuity.SURVIVED
    else -> ProcessContinuity.UNKNOWN
}
