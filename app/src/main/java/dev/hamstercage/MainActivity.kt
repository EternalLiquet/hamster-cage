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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import dev.hamstercage.capture.CaptureController
import dev.hamstercage.capture.CaptureHealth
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.StorageState
import dev.hamstercage.data.SystemTimeSource
import dev.hamstercage.location.BackgroundPermissionAction
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.location.LocationSetup
import dev.hamstercage.location.backgroundPermissionAction
import dev.hamstercage.ui.HamsterApp
import dev.hamstercage.ui.OfficeActions

class MainActivity : ComponentActivity() {
    private var locationSetup by mutableStateOf(LocationSetup())
    private var setupError by mutableStateOf<String?>(null)
    private val foregroundRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshLocationSetup() }
    private val backgroundRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshLocationSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repository = HamsterRepository.get(this)
        CaptureController.get(this)
        setContent {
            val storageState by repository.state.collectAsState(initial = StorageState.Loading)
            val captureStatus by CaptureHealth.state.collectAsState()
            HamsterApp(timeSource = SystemTimeSource(), storageState = storageState, locationSetup = locationSetup,
                captureStatus = captureStatus,
                backgroundOptionLabel = LocationPermissions.backgroundOptionLabel(this), setupError = setupError,
                requestForeground = { foregroundRequest.launch(LocationPermissions.foregroundPermissions) },
                requestBackground = { requestBackground() },
                openAppSettings = { openSettings(LocationPermissions.appSettings(this)) },
                openDeviceSettings = { openSettings(LocationPermissions.deviceSettings()) },
                officeActions = OfficeActions(
                    newId = HamsterRepository::newId,
                    version = repository::officeVersion,
                    save = repository::saveOffice,
                ), savePolicy = { settings, expected -> repository.savePolicy(settings, expected) })
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
