package dev.hamstercage.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureGateTimeoutTest {
    @Test fun blockedCallbackPersistsDeliveryFailureAndCoverageOutage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousCoverage = CoverageStore.state(context).first()
        val previousFailure = CaptureHealthStore.deliveryFailure(context).first()
        val previousStatus = CaptureHealth.state.value
        val receivedAt = Instant.now()
        var appended = false
        try {
            // A slow registrar holds the shared gate past the receiver deadline.
            val success = withCaptureGateTimeout(Mutex(locked = true), 25,
                action = { appended = true },
                onFailure = { recordDeliveryFailure(context, receivedAt) })
            assertFalse(success)
            assertFalse(appended)
            assertTrue(CaptureHealthStore.deliveryFailure(context).first())
            assertEquals(RegistrationStatus.FAILED, CoverageStore.state(context).first().registration)
            assertTrue(CaptureHealth.state.value.deliveryFailure)
        } finally {
            CaptureHealthStore.setDeliveryFailure(context, previousFailure)
            CoverageStore.change(context) { previousCoverage }
            CaptureHealth.registration(previousStatus.registration, previousStatus.registeredCount)
            if (previousFailure) CaptureHealth.deliveryFailed() else CaptureHealth.deliverySucceeded()
        }
    }
}
