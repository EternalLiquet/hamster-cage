package dev.hamstercage.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.time.Instant

data class TrackingHealth(
    val fineLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val locationEnabled: Boolean = false,
    val playServicesAvailable: Boolean = false,
    val registeredOfficeCount: Int = 0,
    val lastRegisteredAt: Instant? = null,
    val registrationError: String? = null,
) {
    val canTrack: Boolean get() = fineLocation && backgroundLocation && locationEnabled && playServicesAvailable
    val message: String get() = when {
        !fineLocation -> "Precise location is needed for office detection. Manual history remains available."
        !backgroundLocation -> "Allow location all the time to detect offices with the app closed."
        !locationEnabled -> "Device location is switched off. Attendance may need correction."
        !playServicesAvailable -> "Google Play services is unavailable. Manual history remains available."
        registrationError != null -> registrationError
        registeredOfficeCount == 0 -> "Add and enable an office to start automatic detection."
        else -> "$registeredOfficeCount office zones registered. Background events can arrive minutes late."
    }
}

object LocationPermissions {
    val foregroundPermissions: Array<String> get() = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    fun health(context: Context): TrackingHealth {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return TrackingHealth(
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            locationEnabled = LocationManagerCompat.isLocationEnabled(manager),
            playServicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS,
        )
    }
    fun appSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}
