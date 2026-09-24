package dev.hamstercage.capture

import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.GeofencingRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun historyDeletionGenerationSeparatesOldQueuedCallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operations = FailingRemoval().apply { fail = false }
        val registrar = GeofenceRegistrar(context, operations = operations)
        val old = registrar.pendingIntent(0)
        val afterDelete = registrar.pendingIntent(1)
        try {
            assertNotEquals(old, afterDelete)
            assertNotEquals(GeofenceRegistrar.action(0), GeofenceRegistrar.action(1))
            assertEquals(RegistrationStatus.NEEDS_SETUP,
                registrar.synchronize(emptyList(), ready = false, generation = 1).registration)
            assertEquals(2, operations.attempts)
        } finally {
            old.cancel()
            afterDelete.cancel()
        }
    }

    @Test fun laterResetRetriesFenceLeftByEarlierFailedRemoval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operations = FailingRemoval().apply { fail = false }
        val registrar = GeofenceRegistrar(context, operations = operations)
        val stale = registrar.pendingIntent(0)
        val firstReset = registrar.pendingIntent(1)
        val secondReset = registrar.pendingIntent(2)
        try {
            operations.failedIntent = stale
            assertEquals(RegistrationStatus.FAILED,
                registrar.synchronize(emptyList(), ready = false, generation = 1).registration)
            assertEquals(listOf(stale), operations.removed)

            operations.failedIntent = null
            assertEquals(RegistrationStatus.NEEDS_SETUP,
                registrar.synchronize(emptyList(), ready = false, generation = 2).registration)
            assertEquals(listOf(stale, stale, firstReset, secondReset), operations.removed)
        } finally {
            stale.cancel()
            firstReset.cancel()
            secondReset.cancel()
        }
    }

    private class FailingRemoval : FenceOperations {
        var fail = true
        var attempts = 0
        var failedIntent: PendingIntent? = null
        val removed = mutableListOf<PendingIntent>()
        override suspend fun remove(intent: PendingIntent) {
            attempts++
            removed += intent
            if (fail || intent == failedIntent) throw IllegalStateException("synthetic platform failure")
        }
        override suspend fun add(request: GeofencingRequest, intent: PendingIntent) {
            error("No registration is expected")
        }
    }
}
