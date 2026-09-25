package dev.hamstercage

import android.graphics.Color
import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import dev.hamstercage.capture.CaptureController
import dev.hamstercage.capture.ForegroundReconciliation
import dev.hamstercage.capture.CaptureHealth
import dev.hamstercage.capture.CaptureWriteGate
import dev.hamstercage.capture.CoverageStore
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.data.SystemTimeSource
import dev.hamstercage.location.BackgroundPermissionAction
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.offices.OfficeLocationServices
import dev.hamstercage.location.backgroundPermissionAction
import dev.hamstercage.privacy.PrivacyController
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import dev.hamstercage.privacy.PrivacyStorageState
import dev.hamstercage.privacy.privacyVisibleStorage
import dev.hamstercage.ui.HamsterApp
import dev.hamstercage.ui.OfficeActions
import dev.hamstercage.ui.CorrectionActions
import dev.hamstercage.ui.CalendarActions
import dev.hamstercage.ui.PrivacyActions
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var locationSetup by mutableStateOf(LocationSetup())
    private var setupError by mutableStateOf<String?>(null)
    private var fullResetRequested by mutableStateOf(false)
    private val foregroundRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshLocationSetup() }
    private val backgroundRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshLocationSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repository = HamsterRepository.get(this)
        val officeLocations = OfficeLocationServices(this)
        val foregroundReconciliation = ForegroundReconciliation(this, officeLocations)
        val privacy = PrivacyController.get(this)
        CaptureController.get(this)
        setContent {
            val visible = remember(repository, privacy) {
                privacyVisibleStorage(privacy.state, freshStorage = { repository.state },
                    freshCoverage = { CoverageStore.state(this).map { it } })
            }
            val presentation by visible.collectAsState(
                initial = PrivacyStorageState(PrivacyResetState.Unavailable, StorageState.Unavailable))
            val storageState = presentation.storage
            val privacyState = presentation.reset
            val coverage = presentation.coverage
            val captureStatus by CaptureHealth.state.collectAsState()
            val editingGeneration = (privacyState as? PrivacyResetState.Idle)?.generation
            suspend fun historyWrite(action: suspend () -> Unit) = CaptureWriteGate.mutex.withLock {
                check(editingGeneration != null &&
                    PrivacyResetStore.read(this@MainActivity) == PrivacyResetState.Idle(editingGeneration)) {
                    "Attendance changed; reopen the editor."
                }
                action()
            }
            HamsterApp(timeSource = SystemTimeSource(), storageState = storageState, locationSetup = locationSetup,
                captureStatus = captureStatus, coverage = coverage, privacyState = privacyState,
                fullResetRequested = fullResetRequested,
                correctionActions = CorrectionActions(SystemTimeSource()::now, HamsterRepository::newId,
                    { edit -> historyWrite { repository.commitAttendanceEdit(edit) } }),
                backgroundOptionLabel = LocationPermissions.backgroundOptionLabel(this), setupError = setupError,
                requestForeground = { foregroundRequest.launch(LocationPermissions.foregroundPermissions) },
                requestBackground = { requestBackground() },
                openAppSettings = { openSettings(LocationPermissions.appSettings(this)) },
                openDeviceSettings = { openSettings(LocationPermissions.deviceSettings()) },
                reconcileOffice = { foregroundReconciliation.check {
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                } },
                setMonitoringEnabled = { enabled -> lifecycleScope.launch {
                    CaptureController.get(this@MainActivity).setMonitoringEnabled(enabled)
                } },
                officeActions = OfficeActions(
                    newId = HamsterRepository::newId,
                    version = repository::officeVersion,
                    save = repository::saveOffice,
                    search = officeLocations::search,
                    current = officeLocations::current,
                    tile = officeLocations::tile,
                    requestForeground = { foregroundRequest.launch(LocationPermissions.foregroundPermissions) },
                    openDeviceSettings = { openSettings(LocationPermissions.deviceSettings()) },
                ), savePolicy = { settings, expected -> repository.savePolicy(settings, expected) },
                calendarActions = CalendarActions(
                    { value -> historyWrite { repository.saveExclusion(value) } },
                    { date -> historyWrite { repository.removeExclusion(date) } },
                    { date, enabled -> historyWrite { repository.setWfh(date, enabled) } }),
                privacyActions = PrivacyActions(privacy::deleteHistory, privacy::recoverPending, {
                    privacy.resetAllAppData().also { if (it) fullResetRequested = true }
                }))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocationSetup()
    }

    private fun refreshLocationSetup() {
        locationSetup = LocationPermissions.read(this)
        CaptureController.get(this).updatePermissionReady(locationSetup.prerequisitesReady)
        setupError = null
    }

    private fun requestBackground() {
        // Re-read permission state at the action boundary, including a revocation since rendering.
        refreshLocationSetup()
        when (backgroundPermissionAction(Build.VERSION.SDK_INT, locationSetup)) {
            BackgroundPermissionAction.FOREGROUND_FIRST -> setupError = "Allow precise foreground location before setting up background detection."
            BackgroundPermissionAction.REQUEST_BACKGROUND -> backgroundRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            BackgroundPermissionAction.OPEN_SETTINGS -> openSettings(LocationPermissions.appSettings(this))
            BackgroundPermissionAction.NONE -> Unit
        }
    }

    private fun openSettings(intent: android.content.Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { setupError = "System settings could not be opened. Use your device's Settings app to review location permissions." }
    }
}
