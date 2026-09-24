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
import com.google.android.gms.common.GoogleApiAvailabilityLight

object LocationPermissions {
    // Foreground and background are deliberately separate activity-result requests.
    val foregroundPermissions: Array<String> get() = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun read(context: Context): LocationSetup {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationSetup(
            coarseLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            fineLocation = fine,
            backgroundLocation = if (Build.VERSION.SDK_INT < 29) fine else granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            locationEnabled = LocationManagerCompat.isLocationEnabled(manager),
            playServicesAvailable = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS,
        )
    }

    fun backgroundOptionLabel(context: Context): String = if (Build.VERSION.SDK_INT >= 30)
        context.packageManager.backgroundPermissionOptionLabel.toString() else "Allow all the time"

    fun appSettings(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    fun deviceSettings() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
}
