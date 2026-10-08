package dev.hamstercage.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DayShareSecurityTest {
    @Test fun providerIsPrivateNarrowAndShareIntentGrantsReadOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.daydiagnostics"
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, FileProvider::class.java), PackageManager.GET_META_DATA)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(authority, provider.authority)

        val file = DayShareFiles.create(context.cacheDir, LocalDate.of(2026, 10, 8), "{\"schemaVersion\":1}")
        try {
            val uri = FileProvider.getUriForFile(context, authority, file)
            assertEquals("content", uri.scheme)
            assertEquals(file.readText(), context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            try {
                FileProvider.getUriForFile(context, authority, File(context.cacheDir, "outside-day-share.json"))
                fail("FileProvider allowed a file outside the diagnostic subdirectory")
            } catch (_: IllegalArgumentException) { }
            val send = dayShareIntent(context, uri)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/json", send.type)
            @Suppress("DEPRECATION")
            val stream = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            assertEquals(uri, stream)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } finally { file.delete() }
    }
}
