package dev.hamstercage.capture

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingIntentSecurityTest {
    @Test fun geofenceCallbackIsPackageOwnedBroadcastAndMutableOnlyAsRequired() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pending = GeofenceRegistrar(context).pendingIntent()
        assertTrue(pending.isBroadcast)
        assertEquals(context.packageName, pending.creatorPackage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertFalse(pending.isImmutable)
    }
}
