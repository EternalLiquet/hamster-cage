package dev.hamstercage.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.location.LocationPermissions
import dev.hamstercage.offices.OfficeRegistrationIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Play Services registration with a synthetic boundary, then removes it. */
@RunWith(AndroidJUnit4::class)
class GeofenceRegistrarDeviceTest {
    @Test fun enabledBoundaryRegistersAndDisabledBoundaryIsRemoved() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Hosted deterministic CI does not promise a working Play Services geofence service.
        // Opt in explicitly for the local platform probe.
        assumeTrue(InstrumentationRegistry.getArguments().getString("platformGeofenceProbe") == "true")
        val context = instrumentation.targetContext
        assumeTrue("Google Play services and device location required", LocationPermissions.read(context).let {
            it.playServicesAvailable && it.locationEnabled
        })
        val permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val missing = permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val registrar = GeofenceRegistrar(context)
        try {
            missing.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
            val registered = registrar.synchronize(listOf(OfficeRegistrationIntent(
                "synthetic-boundary", 0.0, 0.0, 150f)), ready = true)
            assertEquals(RegistrationStatus.ACTIVE, registered.registration)
            assertEquals(1, registered.registeredCount)
            assertEquals(RegistrationStatus.NO_OFFICES,
                registrar.synchronize(emptyList(), ready = true).registration)
        } finally {
            try { registrar.synchronize(emptyList(), ready = true) } catch (_: Exception) { Unit }
            // Revoking the target app here force-stops AndroidJUnitRunner itself. The
            // opt-in host probe restores the prior permissions after instrumentation ends.
        }
    }
}
