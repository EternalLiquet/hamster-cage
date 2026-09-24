package dev.hamstercage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.capture.RegistrationStatus

@Composable
fun LocationSetupPanel(
    setup: LocationSetup,
    backgroundOptionLabel: String,
    requestForeground: () -> Unit,
    requestBackground: () -> Unit,
    openAppSettings: () -> Unit,
    openDeviceSettings: () -> Unit,
    registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
) {
    var skipped by rememberSaveable { mutableStateOf(false) }
    if (skipped) {
        Notice("Detection setup skipped", "You can keep browsing and use manual attendance features without granting location.",
            "Review location setup", { skipped = false })
        return
    }
    Panel {
        Text("Location setup", style = MaterialTheme.typography.titleMedium)
        Tag(setup.status, warm = !setup.prerequisitesReady)
        Text("Office detection uses configured office boundaries, not a continuous travel history. Location is optional; browsing, corrections and settings remain available.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        Text("If you force-stop the app, Android pauses boundary detection. Open Hamster Cage again to request recovery; any uncertain time needs review.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        when {
            !setup.fineLocation -> {
                Text(if (setup.coarseLocation) "Approximate access cannot reliably identify an office boundary. Choose precise location to prepare automatic detection."
                    else "First, allow precise location while using the app. You can decline and continue using local features.")
                CageButton("Allow precise location", requestForeground)
                CageButton("Open app permission settings", openAppSettings)
            }
            !setup.backgroundLocation -> {
                Text("Next, allow background location so office boundaries can be detected when the app is closed. In system permissions, choose Location then “$backgroundOptionLabel”. You can revoke access at any time.")
                CageButton("Set up background location", requestBackground)
            }
            !setup.locationEnabled -> CageButton("Open device location settings", openDeviceSettings)
            !setup.playServicesAvailable -> Text("Automatic detection needs available Google Play services. Local records and manual features do not depend on it.")
            else -> Text(when (registration) {
                RegistrationStatus.ACTIVE -> "Office boundary requests are registered on this device. Background delivery may be delayed."
                RegistrationStatus.NO_OFFICES -> "Permissions are ready. Add an enabled office to request boundary detection."
                RegistrationStatus.FAILED -> "Permissions are ready, but office boundary registration needs attention."
                RegistrationStatus.REGISTERING -> "Registering office boundaries on this device."
                RegistrationStatus.UNKNOWN, RegistrationStatus.NEEDS_SETUP ->
                    "Permissions are ready. Automatic detection is not running: office registration has not been configured."
            })
        }
        CageButton("Continue without detection", { skipped = true })
    }
}
