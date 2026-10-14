package dev.hamstercage.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The draft stays in memory. A uniquely named app-private file exists only after confirmation. */
internal object DayShareFiles {
    private const val LIFETIME_MILLIS = 60 * 60 * 1000L
    const val MAX_BYTES = 2_000_000
    fun create(cacheDir: File, date: LocalDate, json: String): File {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Day data too large" }
        val directory = File(cacheDir, "day-diagnostics")
        check(directory.isDirectory || directory.mkdirs())
        val file = File.createTempFile("hamster-day-$date-", ".json", directory)
        try { file.writeText(json, Charsets.UTF_8) }
        catch (failure: Exception) { file.delete(); throw failure }
        return file
    }
    fun clearExpired(cacheDir: File, nowMillis: Long = System.currentTimeMillis()) {
        File(cacheDir, "day-diagnostics").listFiles()
            ?.filter { it.isFile && nowMillis - it.lastModified() > LIFETIME_MILLIS }
            ?.forEach { it.delete() }
    }
    fun clearAll(cacheDir: File): Boolean {
        val directory = File(cacheDir, "day-diagnostics")
        return !directory.exists() || directory.deleteRecursively()
    }
}

internal fun dayShareIntent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "application/json"
    putExtra(Intent.EXTRA_STREAM, uri)
    clipData = ClipData.newUri(context.contentResolver, "Hamster Cage day data", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** The name offered to the system file picker; the user can change it there. */
internal fun daySaveFileName(date: LocalDate): String = "hamster-day-$date.json"

internal enum class DaySaveOutcome { SAVED, FAILED, CHANGED }

/** The capture gate covers only the privacy check and reading the reviewed draft. */
internal suspend fun completeStagedDaySave(staged: File, gate: Mutex,
    current: suspend () -> Boolean, open: () -> OutputStream?, deleteDocument: () -> Unit): DaySaveOutcome {
    var outcome = DaySaveOutcome.FAILED
    try {
        val bytes = gate.withLock { if (current()) readStagedDayData(staged) else null }
        if (bytes == null || !current()) {
            outcome = DaySaveOutcome.CHANGED
        } else {
            val output = open() ?: throw IOException("The chosen location could not be opened")
            output.use {
                if (!current()) outcome = DaySaveOutcome.CHANGED
                else {
                    it.write(bytes)
                    it.flush()
                    // Deletion can start while a document provider is writing or closing.
                    outcome = if (current()) DaySaveOutcome.SAVED else DaySaveOutcome.CHANGED
                }
            }
            if (outcome == DaySaveOutcome.SAVED && !current()) outcome = DaySaveOutcome.CHANGED
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { outcome = DaySaveOutcome.FAILED }
    finally {
        if (outcome != DaySaveOutcome.SAVED) try { deleteDocument() } catch (_: Exception) { }
        staged.delete()
    }
    return outcome
}

private fun readStagedDayData(staged: File): ByteArray {
    if (!staged.isFile) throw IOException("Reviewed day data is no longer available")
    require(staged.length() <= DayShareFiles.MAX_BYTES) { "Day data too large" }
    val bytes = staged.readBytes()
    require(bytes.size <= DayShareFiles.MAX_BYTES) { "Day data too large" }
    return bytes
}

/**
 * Copies a staged, already-reviewed draft to a user-chosen document. Returns only after the
 * bytes are written and the stream is closed; any failure, including a null stream or a close
 * error, throws so the caller never reports success for an incomplete file.
 */
internal fun copyStagedDayData(staged: File, open: () -> OutputStream?) {
    val bytes = readStagedDayData(staged)
    val output = open() ?: throw IOException("The chosen location could not be opened")
    output.use { it.write(bytes); it.flush() }
}
