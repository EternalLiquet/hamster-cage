package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import dev.hamstercage.domain.TimeSource
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.data.StorageState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Destination(val label: String, val description: String) {
    DASHBOARD("Dashboard", "Attendance totals will appear here when office capture and the attendance engine are connected."),
    OFFICES("Offices", "Configure private office boundaries and walking grace."),
    HISTORY("History", "Saved attendance and corrections will appear here. This shell has no attendance history."),
    SETTINGS("Settings", "Attendance policy and privacy controls are coming in the settings features."),
}

/** UI depends on domain contracts; the Activity supplies platform/data implementations. */
@Composable
fun HamsterApp(
    timeSource: TimeSource, zoneId: ZoneId = ZoneId.systemDefault(),
    storageState: StorageState = StorageState.Loading,
    captureStatus: CaptureStatus = CaptureStatus(),
    locationSetup: LocationSetup = LocationSetup(), backgroundOptionLabel: String = "Allow all the time",
    setupError: String? = null, requestForeground: () -> Unit = {}, requestBackground: () -> Unit = {},
    openAppSettings: () -> Unit = {}, openDeviceSettings: () -> Unit = {},
    trackingReady: Boolean = false,
    officeActions: OfficeActions? = null,
    correctionActions: CorrectionActions? = null,
) {
    var selectedName by rememberSaveable { mutableStateOf(Destination.DASHBOARD.name) }
    val selected = Destination.valueOf(selectedName)
    val now = rememberVisibleNow(timeSource, enabled = selected == Destination.DASHBOARD || selected == Destination.HISTORY)
    val snapshot = (storageState as? StorageState.Ready)?.snapshot
    val displayZone = snapshot?.policy?.zoneId ?: zoneId
    HamsterTheme {
        Scaffold(bottomBar = {
            if (LocalDensity.current.fontScale >= CageStyle.LargeFontThreshold) {
                Column(Modifier.navigationBarsPadding().padding(CageStyle.Small), verticalArrangement = Arrangement.spacedBy(CageStyle.Small)) {
                    Destination.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(CageStyle.Small)) {
                            pair.forEach { destination ->
                                FilledTonalButton(
                                    onClick = { selectedName = destination.name },
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = CageStyle.TouchTarget)
                                        .semantics { this.selected = selected == destination },
                                    contentPadding = PaddingValues(CageStyle.Small),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(destination.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                            color = if (selected == destination) CageStyle.Amber else CageStyle.Secondary)
                                        if (selected == destination) Text("✓", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                NavigationBar(containerColor = CageStyle.Surface) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = selected == destination,
                            onClick = { selectedName = destination.name },
                            icon = { DestinationIcon(when (destination) {
                                Destination.DASHBOARD -> 0
                                Destination.HISTORY -> 1
                                Destination.OFFICES -> 2
                                Destination.SETTINGS -> 3
                            }, selected == destination) },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = CageStyle.Amber,
                                unselectedTextColor = CageStyle.Secondary,
                                indicatorColor = CageStyle.Warm,
                            ),
                        )
                    }
                }
            }
        }) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(CageStyle.Space),
                verticalArrangement = Arrangement.spacedBy(CageStyle.Gap),
            ) {
                Text("Hamster Cage", style = MaterialTheme.typography.titleMedium, color = CageStyle.Peach)
                if (storageState == StorageState.Unavailable) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Attendance data unavailable", "Local attendance data could not be opened. Saved data was kept for recovery.")
                    }
                }
                if (captureStatus.registration == RegistrationStatus.FAILED) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Office detection unavailable", "Office boundaries could not be registered or removed on this device. Review location setup and try opening the app again.")
                    }
                }
                if (captureStatus.deliveryFailure) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Attendance capture needs attention", "A boundary update could not be saved locally. Saved attendance data was kept for review.")
                    }
                }
                PageHeading(selected.label, now.atZone(displayZone).toLocalDate().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")))
                Tag("LOCAL ONLY", warm = true)
                if (selected == Destination.DASHBOARD || selected == Destination.HISTORY) {
                    when {
                        snapshot != null -> {
                            val input = remember(snapshot, now) { snapshot.input(now) }
                            val result = remember(input) { AttendanceEngine.derive(input) }
                            if (selected == Destination.HISTORY) HistoryScreen(input, result, correctionActions)
                            else DashboardScreen(input, result, trackingReady,
                                openOffices = { selectedName = Destination.OFFICES.name },
                                openHistory = { selectedName = Destination.HISTORY.name })
                        }
                        storageState == StorageState.Loading -> Text("Opening your local record…")
                        else -> Unit // The sanitized storage failure notice above remains the only data state.
                    }
                } else if (selected == Destination.OFFICES && officeActions != null) {
                    OfficeScreen(storageState, officeActions)
                } else Notice("Ready for the next step", selected.description)

                if (selected == Destination.OFFICES || selected == Destination.SETTINGS) {
                    FormError(setupError)
                    LocationSetupPanel(locationSetup, backgroundOptionLabel, requestForeground, requestBackground,
                        openAppSettings, openDeviceSettings, captureStatus.registration)
                }
            }
        }
    }
}
