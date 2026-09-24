package dev.hamstercage.location

import org.junit.Assert.*
import org.junit.Test

class LocationSetupTest {
    private val ready = LocationSetup(true, true, true, true, true)

    @Test fun everyPrerequisiteIsRequiredAndStatusExplainsMissingAccess() {
        assertTrue(ready.prerequisitesReady)
        listOf(
            ready.copy(fineLocation = false), ready.copy(backgroundLocation = false),
            ready.copy(locationEnabled = false), ready.copy(playServicesAvailable = false),
        ).forEach { assertFalse(it.prerequisitesReady) }
        assertEquals("Approximate location only", ready.copy(fineLocation = false).status)
        assertEquals("Precise location not allowed", LocationSetup().status)
        assertEquals("Background location not allowed", ready.copy(backgroundLocation = false).status)
        assertEquals("Device location is off", ready.copy(locationEnabled = false).status)
        assertEquals("Google Play services unavailable", ready.copy(playServicesAvailable = false).status)
        assertFalse(ready.status.contains("active", ignoreCase = true))
    }

    @Test fun backgroundActionNeverRequestsWithoutPreciseForegroundPermission() {
        for (sdk in 26..37) {
            assertEquals(BackgroundPermissionAction.FOREGROUND_FIRST, backgroundPermissionAction(sdk, LocationSetup()))
            assertEquals(BackgroundPermissionAction.FOREGROUND_FIRST, backgroundPermissionAction(sdk, ready.copy(fineLocation = false)))
        }
    }

    @Test fun platformVersionsUseSeparateBackgroundRequestOrSettings() {
        val foregroundOnly = ready.copy(backgroundLocation = false)
        assertEquals(BackgroundPermissionAction.NONE, backgroundPermissionAction(28, foregroundOnly))
        assertEquals(BackgroundPermissionAction.REQUEST_BACKGROUND, backgroundPermissionAction(29, foregroundOnly))
        for (sdk in 30..37) assertEquals(BackgroundPermissionAction.OPEN_SETTINGS, backgroundPermissionAction(sdk, foregroundOnly))
        for (sdk in 26..37) assertEquals(BackgroundPermissionAction.NONE, backgroundPermissionAction(sdk, ready))
    }
}
