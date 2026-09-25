package dev.hamstercage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hamstercage.capture.ReconcileOutcome
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.capture.CaptureStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LocationSetupPanel(
    setup: LocationSetup,
    backgroundOptionLabel: String,
    requestForeground: () -> Unit,
    requestBackground: () -> Unit,
    openAppSettings: () -> Unit,
    openDeviceSettings: () -> Unit,
    registration: RegistrationStatus = RegistrationStatus.UNKNOWN,
    eligibleOfficeCount: Int = 0,
    reconcile: (suspend () -> ReconcileOutcome)? = null,
    captureStatus: CaptureStatus = CaptureStatus(),
    policyZone: ZoneId = ZoneId.systemDefault(),
    setMonitoringEnabled: (Boolean) -> Unit = {},
) {
    var skipped by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var checkJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { checkJob?.cancel(); checking = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); checkJob?.cancel() }
    }
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
        Text("With monitoring on, office boundary events are primary. A current location check can recover missed events about every 30 minutes, Monday–Friday, 7 AM–7 PM in $policyZone. Android may delay checks. Location and attendance stay on this device; neither is used for advertising or analytics.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        Text(if (captureStatus.monitoringEnabled) "Monitoring on" else "Monitoring disabled")
        CageButton(if (captureStatus.monitoringEnabled) "Disable attendance monitoring" else "Enable attendance monitoring",
            { setMonitoringEnabled(!captureStatus.monitoringEnabled) })
        captureStatus.lastVerifiedAt?.let { verified ->
            Text("Last verified ${verified.atZone(policyZone).format(DateTimeFormatter.ofPattern("EEE h:mm a"))} · weekday checks 7 AM–7 PM")
        }
        captureStatus.lastCheckResult?.let { Text("Latest check: $it") }
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
                RegistrationStatus.DISABLED -> "Attendance monitoring is disabled."
                RegistrationStatus.UNKNOWN, RegistrationStatus.NEEDS_SETUP ->
                    "Permissions are ready. Automatic detection is not running: office registration has not been configured."
            })
        }
        if (eligibleOfficeCount > 0 && reconcile != null) {
            Text("Already at an office? Check your current precise location once. If confirmed, attendance starts at this observation; arrival walking grace still applies. Your coordinates are not saved.")
            CageButton(if (checking) "Checking current office…" else "Check current office", onClick = {
                if (!checking) {
                    checking = true; result = null
                    checkJob = scope.launch {
                        try { result = when (reconcile()) {
                            ReconcileOutcome.CONFIRMED -> "Inside an office. A session starts at the current observation; walking grace is uncredited."
                            ReconcileOutcome.ALREADY_PRESENT -> "An office session is already open. No second entry was added."
                            ReconcileOutcome.OUTSIDE -> "Outside saved office boundaries. Office state remains unknown; retry when inside."
                            ReconcileOutcome.UNCERTAIN_BOUNDARY -> "Too close to an office boundary to confirm. Move farther inside and retry."
                            ReconcileOutcome.OVERLAPPING_OFFICES -> "More than one office could match. Adjust the office boundaries and retry."
                            ReconcileOutcome.STALE -> "The location fix is stale. Retry for a fresh observation."
                            ReconcileOutcome.INACCURATE -> "Location is not precise enough. Move into open sky and retry."
                            ReconcileOutcome.NEEDS_PRECISE -> "Precise location is needed. Allow precise access in app settings, then retry."
                            ReconcileOutcome.LOCATION_OFF -> "Device location is off. Turn it on in settings, then retry."
                            ReconcileOutcome.NOT_REGISTERED -> "Office detection is not registered yet. Review location setup, then retry."
                            ReconcileOutcome.TIMEOUT -> "Current location timed out. Retry when your device can get a fix."
                            ReconcileOutcome.UNAVAILABLE -> "Current office check failed. Review location access and retry."
                        } } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { result = "Current office check failed. Retry; your previous attendance is unchanged." }
                        finally { checking = false }
                    }
                }
            })
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        CageButton("Continue without detection", { checkJob?.cancel(); checking = false; skipped = true })
    }
}
