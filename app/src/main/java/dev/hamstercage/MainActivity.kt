package dev.hamstercage

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.ui.HamsterApp
import dev.hamstercage.ui.HamsterTheme

class MainActivity : ComponentActivity() {
    private val repository by lazy { HamsterRepository(applicationContext) }
    private var resumeGeneration by mutableIntStateOf(0)
    private val foregroundPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeGeneration++ }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeGeneration++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HamsterTheme {
                HamsterApp(
                    repository = repository,
                    refreshGeneration = resumeGeneration,
                    requestForeground = { foregroundPermission.launch(LocationPermissions.foregroundPermissions) },
                    requestBackground = {
                        if(Build.VERSION.SDK_INT == 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        else startActivity(LocationPermissions.appSettingsIntent(this))
                    },
                    openAppSettings = { startActivity(LocationPermissions.appSettingsIntent(this)) },
                    openLocationSettings = { startActivity(LocationPermissions.locationSettingsIntent()) }
                )
            }
        }
    }

    override fun onResume() { super.onResume();resumeGeneration++ }
}
