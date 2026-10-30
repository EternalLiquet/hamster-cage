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
    setMonitoringEnabled: suspend (Boolean) -> Unit = {},
) {
    var skipped by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var changingMonitoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var checkJob by remember { mutableStateOf<Job?>(null) }
    fun changeMonitoring(enabled: Boolean, onSuccess: () -> Unit = {}) {
        if (changingMonitoring) return
        changingMonitoring = true
        result = null
        scope.launch {
            try {
                setMonitoringEnabled(enabled)
                onSuccess()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { result = "Attendance monitoring could not be changed. Retry or review app settings." }
            finally { changingMonitoring = false }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { checkJob?.cancel(); checking = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); checkJob?.cancel() }
    }
    if (skipped) {
        Notice("Attendance monitoring disabled", "Office boundaries and weekday checks are off. You can still browse and enter attendance manually.",
            "Review and re-enable detection", { skipped = false })
        return
    }
    Panel {
        Text("Location setup", style = MaterialTheme.typography.titleMedium)
        Tag(setup.status, warm = !setup.prerequisitesReady)
        Text("Office detection only watches the office areas you set up and keeps no travel history. Location is optional; you can still browse, correct attendance and change settings without it.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        Text("After you leave an office area, the app may run up to five quick checks over five minutes; Android can delay them. Routine checks aim for every 30 minutes (every 15 while a visit needs checking), weekdays 7 AM–7 PM in $policyZone. Location and attendance are stored on this device and are not used for ads or analytics.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        Text(if (captureStatus.monitoringEnabled) "Monitoring is on" else "Monitoring is off")
        CageButton(if (changingMonitoring) "Updating attendance monitoring…" else if (captureStatus.monitoringEnabled)
            "Disable attendance monitoring" else "Enable attendance monitoring",
            { changeMonitoring(!captureStatus.monitoringEnabled) })
        captureStatus.lastVerifiedAt?.let { verified ->
            Text("Last verified ${verified.atZone(policyZone).format(DateTimeFormatter.ofPattern("EEE h:mm a"))} · weekday checks 7 AM–7 PM")
        }
        captureStatus.lastCheckResult?.let { Text("Latest check: $it") }
        Text("If you force-stop the app, Android pauses office detection. Open Hamster Cage again so it can resume; any uncertain time will need review.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        when {
            !setup.fineLocation -> {
                Text(if (setup.coarseLocation) "Approximate location can't reliably tell when you're at an office. Allow precise location to set up automatic detection."
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
                RegistrationStatus.ACTIVE -> "Office detection is set up on this device. Android may deliver updates late."
                RegistrationStatus.NO_OFFICES -> "Permissions are ready. Add or turn on an office to start detection."
                RegistrationStatus.FAILED -> "Permissions are ready, but office detection setup needs attention."
                RegistrationStatus.REGISTERING -> "Setting up office detection on this device."
                RegistrationStatus.DISABLED -> "Attendance monitoring is off."
                RegistrationStatus.UNKNOWN, RegistrationStatus.NEEDS_SETUP ->
                    "Permissions are ready, but automatic detection is not running because office detection is not set up yet."
            })
        }
        if (eligibleOfficeCount > 0 && reconcile != null) {
            Text("Already at an office? Check your precise location once. If you're inside, attendance starts now and the arrival walking grace still applies. Your coordinates are not saved.")
            CageButton(if (checking) "Checking current office…" else "Check current office", onClick = {
                if (!checking) {
                    checking = true; result = null
                    checkJob = scope.launch {
                        try { result = when (reconcile()) {
                            ReconcileOutcome.CONFIRMED -> "You're inside an office area. A visit starts now; the arrival walking grace is not credited."
                            ReconcileOutcome.ALREADY_PRESENT -> "A visit is already open. Nothing new was added."
                            ReconcileOutcome.OUTSIDE -> "You're outside your saved office areas. Office status stays unknown; check again when you're inside."
                            ReconcileOutcome.UNCERTAIN_BOUNDARY -> "Too close to the edge of an office area to confirm. Move farther inside and try again."
                            ReconcileOutcome.OVERLAPPING_OFFICES -> "More than one office could match. Adjust the office areas and try again."
                            ReconcileOutcome.STALE -> "That location was out of date. Try again for a fresh one."
                            ReconcileOutcome.INACCURATE -> "Location isn't precise enough. Move somewhere with a clearer view of the sky and try again."
                            ReconcileOutcome.NEEDS_PRECISE -> "Precise location is needed. Allow it in app settings, then try again."
                            ReconcileOutcome.LOCATION_OFF -> "Device location is off. Turn it on in settings, then try again."
                            ReconcileOutcome.NOT_REGISTERED -> "Office detection isn't set up yet. Finish location setup, then try again."
                            ReconcileOutcome.TIMEOUT -> "Finding your location took too long. Try again when your phone can get a location."
                            ReconcileOutcome.UNAVAILABLE -> "The office check didn't work. Check location access and try again."
                        } } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { result = "Current office check failed. Retry; your previous attendance is unchanged." }
                        finally { checking = false }
                    }
                }
            })
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        CageButton(if (changingMonitoring) "Stopping attendance monitoring…" else "Continue without detection", {
            if (!changingMonitoring) {
                checkJob?.cancel()
                checking = false
                changeMonitoring(false) { skipped = true }
            }
        })
    }
}
