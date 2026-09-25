package dev.hamstercage.capture

import android.Manifest
import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.GeofencingRequest
import dev.hamstercage.offices.OfficeRegistrationIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the platform operation sequence without relying on location delivery. */
@RunWith(AndroidJUnit4::class)
class GeofenceRegistrarRecoveryTest {
    @Test fun disabledDeletedAndEditedOfficesReplaceOnlyTheDesiredSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantLocation()
        val operations = RecordingFences()
        val registrar = GeofenceRegistrar(context, operations = operations)
        val a = office("a", 150f)
        val b = office("b", 150f)

        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(a, b), true).registration)
        assertEquals(setOf("a", "b"), operations.registered)
        assertEquals(2, operations.adds)

        // A disabled office drops out of registrationIntents. Removing a saved
        // office has the same desired-set effect; neither case touches raw facts.
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(b), true).registration)
        assertEquals(setOf("b"), operations.registered)
        assertEquals(3, operations.adds)

        val edited = b.copy(radiusMeters = 300f)
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(edited), true).registration)
        assertEquals(setOf("b"), operations.registered)
        assertEquals(300f, operations.radiusByOffice.getValue("b"), 0f)

        assertEquals(RegistrationStatus.NO_OFFICES, registrar.synchronize(emptyList(), true).registration)
        assertTrue(operations.registered.isEmpty())

        // A new process has no in-memory lastApplied and must reapply the set.
        val restarted = GeofenceRegistrar(context, operations = operations)
        assertEquals(RegistrationStatus.ACTIVE, restarted.synchronize(listOf(a), true).registration)
        assertEquals(setOf("a"), operations.registered)
        repeat(2) {
            assertEquals(RegistrationStatus.ACTIVE, restarted.synchronize(listOf(a), true, force = true).registration)
            assertEquals(setOf("a"), operations.registered)
        }
    }

    @Test fun failedCleanupNeverReportsActiveAndRetryRemovesStaleOffice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantLocation()
        val operations = RecordingFences()
        val registrar = GeofenceRegistrar(context, operations = operations)
        val a = office("a", 150f)
        val b = office("b", 150f)
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(a, b), true).registration)

        operations.failRemoval = true
        assertEquals(RegistrationStatus.FAILED, registrar.synchronize(listOf(b), true).registration)
        assertEquals(setOf("a", "b"), operations.registered)

        operations.failRemoval = false
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(listOf(b), true).registration)
        assertEquals(setOf("b"), operations.registered)
    }

    @Test fun partialAddFailureIsCleanedAndRetryRegistersCompleteSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantLocation()
        val operations = RecordingFences().apply { failAddNumber = 2 }
        val registrar = GeofenceRegistrar(context, operations = operations)
        val desired = listOf(office("a", 150f), office("b", 150f))

        assertEquals(RegistrationStatus.FAILED, registrar.synchronize(desired, true).registration)
        assertTrue(operations.registered.isEmpty())

        operations.failAddNumber = null
        assertEquals(RegistrationStatus.ACTIVE, registrar.synchronize(desired, true).registration)
        assertEquals(setOf("a", "b"), operations.registered)
    }

    private fun grantLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, it)
        }
    }

    private fun office(id: String, radius: Float) = OfficeRegistrationIntent(id, 0.0, 0.0, radius)

    private class RecordingFences : FenceOperations {
        var failRemoval = false
        var failAddNumber: Int? = null
        var adds = 0
        val registered = mutableSetOf<String>()
        val radiusByOffice = mutableMapOf<String, Float>()

        override suspend fun remove(intent: PendingIntent) {
            if (failRemoval) error("synthetic cleanup failure")
            registered.clear()
            radiusByOffice.clear()
        }

        override suspend fun add(request: GeofencingRequest, intent: PendingIntent) {
            adds++
            if (adds == failAddNumber) error("synthetic partial-add failure")
            request.geofences.forEach { fence ->
                registered += fence.requestId
                radiusByOffice[fence.requestId] = fence.radius
            }
        }
    }
}
