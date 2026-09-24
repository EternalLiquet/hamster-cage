package dev.hamstercage.location

/** Prerequisites only: granted permissions do not establish registered zones or observed presence. */
data class LocationSetup(
    val coarseLocation: Boolean = false,
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val locationEnabled: Boolean = false,
    val playServicesAvailable: Boolean = false,
) {
    val prerequisitesReady: Boolean get() = fineLocation && backgroundLocation && locationEnabled && playServicesAvailable
    val status: String get() = when {
        !fineLocation && coarseLocation -> "Approximate location only"
        !fineLocation -> "Precise location not allowed"
        !backgroundLocation -> "Background location not allowed"
        !locationEnabled -> "Device location is off"
        !playServicesAvailable -> "Google Play services unavailable"
        else -> "Location prerequisites ready"
    }
}

enum class BackgroundPermissionAction { NONE, FOREGROUND_FIRST, REQUEST_BACKGROUND, OPEN_SETTINGS }

fun backgroundPermissionAction(sdk: Int, setup: LocationSetup): BackgroundPermissionAction = when {
    !setup.fineLocation -> BackgroundPermissionAction.FOREGROUND_FIRST
    sdk < 29 || setup.backgroundLocation -> BackgroundPermissionAction.NONE
    sdk == 29 -> BackgroundPermissionAction.REQUEST_BACKGROUND
    else -> BackgroundPermissionAction.OPEN_SETTINGS
}
