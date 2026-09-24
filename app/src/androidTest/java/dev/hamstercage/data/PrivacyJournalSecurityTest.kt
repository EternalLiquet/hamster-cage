package dev.hamstercage.data

import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.hamstercage.privacy.PrivacyResetJournal
import dev.hamstercage.privacy.PrivacyResetState
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PrivacyJournalSecurityTest {
    @Test @SdkSuppress(minSdkVersion = 34)
    fun corruptJournalFailsClosedPreservesBytesAndCannotBeReadByShell() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val malformed = listOf(
            preferencesOf(booleanPreferencesKey("pending_history_delete") to true),
            preferencesOf(booleanPreferencesKey("pending_history_delete") to false),
            preferencesOf(intPreferencesKey("version") to 99, longPreferencesKey("generation") to 0L, booleanPreferencesKey("pending_history_delete") to false),
            preferencesOf(intPreferencesKey("version") to 1, longPreferencesKey("generation") to -1L, booleanPreferencesKey("pending_history_delete") to false),
            preferencesOf(intPreferencesKey("version") to 1, longPreferencesKey("generation") to 2L, booleanPreferencesKey("pending_history_delete") to false, longPreferencesKey("retired_fence_generation_through") to 2L),
            preferencesOf(stringPreferencesKey("version") to "PRIVATE_JOURNAL_SENTINEL_32", longPreferencesKey("generation") to 0L, booleanPreferencesKey("pending_history_delete") to false),
        )
        for (bad in malformed) {
            val name = "journal-security-${UUID.randomUUID()}"
            val file = context.preferencesDataStoreFile(name)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
            try {
                preferences.updateData { bad }
                val before = file.readBytes()
                val journal = PrivacyResetJournal(preferences)
                assertEquals(PrivacyResetState.Unavailable, journal.read())
                assertTrue(runCatching { journal.begin() }.isFailure)
                assertArrayEquals(before, file.readBytes())
                val descriptors = instrumentation.uiAutomation.executeShellCommandRwe("cat ${file.absolutePath}")
                descriptors[1].close()
                val output = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
                val error = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
                assertTrue(output.isEmpty()); assertTrue(error.contains("Permission denied"))
            } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); file.delete() }
        }
    }
}
