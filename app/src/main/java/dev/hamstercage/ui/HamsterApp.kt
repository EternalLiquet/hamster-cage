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
import androidx.compose.runtime.key
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
import dev.hamstercage.capture.CoverageLedger
import dev.hamstercage.capture.RegistrationStatus
import dev.hamstercage.capture.ReconcileOutcome
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.data.StorageState
import dev.hamstercage.data.PolicySettings
import dev.hamstercage.privacy.PrivacyResetState
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
    coverage: CoverageLedger? = null,
    privacyState: PrivacyResetState = PrivacyResetState.Idle(0),
    fullResetRequested: Boolean = false,
    locationSetup: LocationSetup = LocationSetup(), backgroundOptionLabel: String = "Allow all the time",
    setupError: String? = null, requestForeground: () -> Unit = {}, requestBackground: () -> Unit = {},
    openAppSettings: () -> Unit = {}, openDeviceSettings: () -> Unit = {},
    reconcileOffice: (suspend () -> ReconcileOutcome)? = null,
    setMonitoringEnabled: suspend (Boolean) -> Unit = {},
    trackingReady: Boolean = false,
    officeActions: OfficeActions? = null,
    correctionActions: CorrectionActions? = null,
    savePolicy: (suspend (PolicySettings, PolicySettings) -> Unit)? = null,
    calendarActions: CalendarActions? = null,
    privacyActions: PrivacyActions? = null,
) {
    var selectedName by rememberSaveable { mutableStateOf(Destination.DASHBOARD.name) }
    val selected = Destination.valueOf(selectedName)
    val now = rememberVisibleNow(timeSource, enabled = selected == Destination.DASHBOARD ||
        selected == Destination.HISTORY || selected == Destination.SETTINGS)
    val usableStorage = if (privacyState is PrivacyResetState.Idle && !fullResetRequested) storageState else StorageState.Unavailable
    val snapshot = (usableStorage as? StorageState.Ready)?.snapshot
    val displayZone = snapshot?.policy?.zoneId ?: zoneId
    val effectiveTrackingReady = privacyState is PrivacyResetState.Idle && !fullResetRequested &&
        (if (coverage == null) trackingReady else
            captureStatus.registration == RegistrationStatus.ACTIVE && !captureStatus.deliveryFailure &&
                coverage.presenceConfirmed(now, displayZone))
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
                if (fullResetRequested) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Full reset requested", "Android is clearing local app data. Reopen the app to check the fresh state.")
                    }
                } else if (privacyState is PrivacyResetState.Pending) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("History deletion pending", "Attendance is hidden while local deletion finishes. Open Privacy and local data to retry if needed.")
                    }
                } else if (privacyState == PrivacyResetState.Unavailable) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Privacy state unavailable", "Attendance is hidden because local deletion state could not be read. Review Privacy and local data.")
                    }
                } else if (storageState == StorageState.Unavailable) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Attendance data unavailable", "Local attendance data could not be opened. Saved data was kept for recovery.")
                    }
                }
                if (privacyState is PrivacyResetState.Idle && !fullResetRequested && captureStatus.registration == RegistrationStatus.FAILED) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Office detection unavailable", "Office boundaries could not be registered or removed on this device. Review location setup and try opening the app again.")
                    }
                }
                if (privacyState is PrivacyResetState.Idle && !fullResetRequested && captureStatus.deliveryFailure) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Notice("Attendance capture needs attention", "A boundary update could not be saved locally. Saved attendance data was kept for review.")
                    }
                }
                PageHeading(selected.label, now.atZone(displayZone).toLocalDate().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")))
                Tag("LOCAL ONLY", warm = true)
                if (selected == Destination.DASHBOARD || selected == Destination.HISTORY) {
                    when {
                        snapshot != null -> {
                            val input = remember(snapshot, now, coverage) {
                                snapshot.input(now).copy(historyStartDate = coverage?.historyStartDate,
                                    unknownDates = coverage?.unreviewedUnknownDates.orEmpty())
                            }
                            val result = remember(input) { AttendanceEngine.derive(input) }
                            if (selected == Destination.HISTORY) key(privacyState.generation) {
                                HistoryScreen(input, result, correctionActions, snapshot.eventEvidence)
                            }
                            else {
                                DashboardScreen(input, result, effectiveTrackingReady,
                                    openOffices = { selectedName = Destination.OFFICES.name },
                                    openHistory = { selectedName = Destination.HISTORY.name })
                                if (coverage?.unreviewedUnknownDates?.isNotEmpty() == true)
                                    Notice("Attendance coverage needs review",
                                        "Detection was unavailable for one or more dates. Recorded time remains, but missing time is unknown.",
                                        "Review history", { selectedName = Destination.HISTORY.name })
                            }
                        }
                        usableStorage == StorageState.Loading -> Text("Opening your local record…")
                        else -> Unit // The sanitized storage failure notice above remains the only data state.
                    }
                } else if (selected == Destination.OFFICES && officeActions != null) {
                    OfficeScreen(usableStorage, officeActions)
                } else if (selected == Destination.SETTINGS && savePolicy != null) {
                    PolicyScreen(usableStorage, savePolicy)
                } else Notice("Ready for the next step", selected.description)

                if (selected == Destination.SETTINGS && calendarActions != null) {
                    key(privacyState.generation) {
                        CalendarScreen(usableStorage, now.atZone(displayZone).toLocalDate(), calendarActions)
                    }
                }
                if (selected == Destination.SETTINGS && privacyActions != null) {
                    PrivacyScreen(usableStorage, privacyState, privacyActions)
                }
                if ((selected == Destination.OFFICES || selected == Destination.SETTINGS) &&
                    privacyState is PrivacyResetState.Idle && !fullResetRequested) {
                    FormError(setupError)
                    key(selected) {
                        LocationSetupPanel(locationSetup, backgroundOptionLabel, requestForeground, requestBackground,
                            openAppSettings, openDeviceSettings, captureStatus.registration,
                            snapshot?.offices?.count { it.enabled && it.countsTowardAttendance } ?: 0,
                            reconcileOffice, captureStatus, displayZone, setMonitoringEnabled)
                    }
                }
            }
        }
    }
}
