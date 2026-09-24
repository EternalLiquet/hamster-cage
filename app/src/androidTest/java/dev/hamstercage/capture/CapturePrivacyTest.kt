package dev.hamstercage.capture

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturePrivacyTest {
    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun newCaptureMetadataFilesRemainPrivateToAppUid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousCoverage = CoverageStore.state(context).first()
        val previousFailure = CaptureHealthStore.deliveryFailure(context).first()
        try {
            val at = Instant.parse("2025-03-10T16:00:00Z")
            CoverageStore.change(context) {
                CoverageLedger().registrationSucceeded(at, ZoneId.of("UTC"), true).observed(at)
            }
            CaptureHealthStore.setDeliveryFailure(context, false)
            val privateRoot = File(context.applicationInfo.dataDir).canonicalFile
            listOf("capture_coverage", "capture_health").forEach { name ->
                val file = context.preferencesDataStoreFile(name)
                assertTrue(file.isFile)
                assertTrue(file.canonicalFile.toPath().startsWith(privateRoot.toPath()))
                assertEquals(0, Os.stat(file.absolutePath).st_mode and OsConstants.S_IRWXO)
                // App-generated path has no user-supplied shell characters.
                val descriptors = instrumentation.uiAutomation.executeShellCommandRwe("cat ${file.absolutePath}")
                descriptors[1].close()
                val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
                val error = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
                assertTrue(error.contains("Permission denied"))
                assertTrue(output.isEmpty())
            }
        } finally {
            CoverageStore.change(context) { previousCoverage }
            CaptureHealthStore.setDeliveryFailure(context, previousFailure)
        }
    }
}
