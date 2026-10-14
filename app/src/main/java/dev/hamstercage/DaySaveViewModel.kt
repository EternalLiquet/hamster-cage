package dev.hamstercage

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hamstercage.capture.CaptureWriteGate
import dev.hamstercage.privacy.PrivacyResetState
import dev.hamstercage.privacy.PrivacyResetStore
import dev.hamstercage.ui.DaySaveOutcome
import dev.hamstercage.ui.DayShareFiles
import dev.hamstercage.ui.completeStagedDaySave
import dev.hamstercage.ui.daySaveFileName
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retained across Activity recreation, including a document provider write in progress. */
internal class DaySaveViewModel : ViewModel() {
    var pendingSave by mutableStateOf<PendingDaySave?>(null)
        private set
    var saveInFlight by mutableStateOf(false)
        private set
    var shareError by mutableStateOf<String?>(null)
    var shareNotice by mutableStateOf<String?>(null)
    private val requestedPicker = MutableStateFlow<String?>(null)
    val pickerRequest = requestedPicker.asStateFlow()

    fun restore(state: Bundle?) {
        if (pendingSave == null) pendingSave = PendingDaySave.restore(state)
    }

    fun save(state: Bundle) { pendingSave?.save(state) }

    fun startDaySave(context: Context, json: String, date: LocalDate, generation: Long) {
        if (pendingSave != null || saveInFlight) return
        saveInFlight = true
        shareError = null
        shareNotice = null
        val application = context.applicationContext
        viewModelScope.launch {
            try {
                val staged = CaptureWriteGate.mutex.withLock {
                    if (PrivacyResetStore.read(application) != PrivacyResetState.Idle(generation)) null
                    else withContext(Dispatchers.IO) { DayShareFiles.create(application.cacheDir, date, json) }
                }
                if (staged == null) shareError = "Attendance changed. Reopen the day before saving."
                else stageForPicker(staged, generation, daySaveFileName(date))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                shareError = "Couldn't prepare this day for saving, or it was too large. Nothing was saved."
            } finally { saveInFlight = false }
        }
    }

    internal fun stageForPicker(staged: File, generation: Long, filename: String) {
        pendingSave = PendingDaySave(staged.absolutePath, generation)
        requestedPicker.value = filename
    }

    /** The active Activity supplies the launcher; a recreated Activity consumes a pending request. */
    fun launchPicker(launch: (String) -> Unit) {
        val name = requestedPicker.value ?: return
        requestedPicker.value = null
        try { launch(name) }
        catch (_: Exception) {
            pendingSave?.let { File(it.path).delete() }
            pendingSave = null
            shareError = "Couldn't open a location for saving. Nothing was saved. Try again."
        }
    }

    fun finishDocumentResult(context: Context, uri: Uri?) {
        val application = context.applicationContext
        val pending = pendingSave
        if (pending == null) {
            if (uri != null) {
                viewModelScope.launch(Dispatchers.IO) { deleteCreatedDocument(application, uri) }
                shareError = "Couldn't save this day. Reopen it and try again."
            }
            return
        }
        if (uri == null) {
            pendingSave = null
            File(pending.path).delete()
            shareNotice = "Save cancelled. Nothing was saved."
            return
        }
        saveInFlight = true
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                finishPendingSave(CaptureWriteGate.mutex,
                    { PrivacyResetStore.read(application) == PrivacyResetState.Idle(pending.generation) },
                    { application.contentResolver.openOutputStream(uri) },
                    { deleteCreatedDocument(application, uri) })
            }
        }
    }

    /** Keeps the result on the retained model, so a rotated Activity sees the completed write. */
    internal suspend fun finishPendingSave(gate: Mutex, current: suspend () -> Boolean,
        open: () -> OutputStream?, deleteDocument: () -> Unit): DaySaveOutcome {
        val pending = requireNotNull(pendingSave) { "No reviewed draft is pending" }
        pendingSave = null
        saveInFlight = true
        try {
            val outcome = completeStagedDaySave(File(pending.path), gate, current, open, deleteDocument)
            when (outcome) {
                DaySaveOutcome.SAVED -> shareNotice =
                    "Saved. This copy is outside Hamster Cage, so deleting history here won't remove it."
                DaySaveOutcome.FAILED -> shareError = "Couldn't save this day to that location. Try another location."
                DaySaveOutcome.CHANGED -> shareError =
                    "Attendance changed while choosing a location. Reopen the day before saving."
            }
            return outcome
        } finally { saveInFlight = false }
    }

    private fun deleteCreatedDocument(context: Context, uri: Uri) {
        try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { }
    }
}

/** Only the staged path and generation cross process recreation while the picker is open. */
internal data class PendingDaySave(val path: String, val generation: Long) {
    fun save(outState: Bundle) {
        outState.putString(PATH, path)
        outState.putLong(GENERATION, generation)
    }

    companion object {
        private const val PATH = "pendingDaySavePath"
        private const val GENERATION = "pendingDaySaveGeneration"
        fun restore(state: Bundle?): PendingDaySave? {
            val path = state?.getString(PATH) ?: return null
            return PendingDaySave(path, state.getLong(GENERATION))
        }
    }
}
