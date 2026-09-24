package dev.hamstercage.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.domain.Office
import dev.hamstercage.domain.TimeSource
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Assert installed OS controls with synthetic local files, not only source manifest declarations. */
@RunWith(AndroidJUnit4::class)
class FoundationPrivacyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Suppress("DEPRECATION")
    @Test fun installedPackageHasNoNetworkBackupOrDataProviderSurface() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertEquals(0, info.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, info.applicationInfo!!.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertTrue(info.providers.orEmpty().all { !it.exported && !it.grantUriPermissions })
        assertTrue(info.activities.orEmpty().filter { it.exported }.all { it.name == "dev.hamstercage.MainActivity" })
        assertTrue(info.services.orEmpty().none { it.exported })
        assertTrue(info.receivers.orEmpty().filter { it.exported }.all {
            it.name == "androidx.profileinstaller.ProfileInstallReceiver" && it.permission == "android.permission.DUMP"
        })
    }

    @Test
    @SdkSuppress(minSdkVersion = 34) // Android 14 added separate stdout/stderr shell descriptors.
    fun actualRoomAndPolicyFilesArePrivateAndShellCannotReadThem() = runBlocking {
        val name = "privacy-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(name) }
        val database = HamsterDatabase.open(context, "$name.db")
        val now = Instant.parse("2026-09-23T16:00:00Z")
        try {
            val repository = HamsterRepository(database, preferences, TimeSource { now })
            repository.saveOffice(Office("synthetic", "Synthetic privacy fixture", 0.0, 0.0))
            repository.savePolicy(PolicySettings(targetMinutesPerDay = 420))
            val files = listOf(context.getDatabasePath("$name.db"), context.preferencesDataStoreFile(name))
            val privateRoot = File(context.applicationInfo.dataDir).canonicalFile
            assertEquals("2000", shell("id -u").trim())
            files.forEach { file ->
                assertTrue(file.isFile)
                assertTrue(file.canonicalFile.toPath().startsWith(privateRoot.toPath()))
                assertEquals(0, Os.stat(file.absolutePath).st_mode and OsConstants.S_IRWXO)
                // The generated package/file paths contain no shell metacharacters or user input.
                val descriptors = instrumentation.uiAutomation.executeShellCommandRwe("cat ${file.absolutePath}")
                descriptors[1].close() // cat needs no stdin.
                val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
                val error = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
                assertTrue("A different OS identity must be denied", error.contains("Permission denied"))
                assertTrue("No source bytes may be returned", output.isEmpty())
            }
        } finally {
            database.close()
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            context.deleteDatabase("$name.db")
            context.preferencesDataStoreFile(name).delete()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
