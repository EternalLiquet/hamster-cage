package dev.hamstercage.capture

import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.GeofencingRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeofenceRegistrarFailureTest {
    @Test fun failedRemovalKeepsHealthUncertainUntilSuccessfulRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operations = FailingRemoval()
        val registrar = GeofenceRegistrar(context, operations = operations)

        assertEquals(RegistrationStatus.FAILED,
            registrar.synchronize(emptyList(), ready = false).registration)
        assertEquals(RegistrationStatus.FAILED, CaptureHealth.state.value.registration)
        assertEquals(1, operations.attempts)

        operations.fail = false
        assertEquals(RegistrationStatus.NEEDS_SETUP,
            registrar.synchronize(emptyList(), ready = false).registration)
        assertEquals(2, operations.attempts)
    }

    private class FailingRemoval : FenceOperations {
        var fail = true
        var attempts = 0
        override suspend fun remove(intent: PendingIntent) {
            attempts++
            if (fail) throw IllegalStateException("synthetic platform failure")
        }
        override suspend fun add(request: GeofencingRequest, intent: PendingIntent) {
            error("No registration is expected")
        }
    }
}
