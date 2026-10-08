package dev.hamstercage.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.time.LocalDate

/** The draft stays in memory. A uniquely named app-private file exists only after confirmation. */
internal object DayShareFiles {
    private const val LIFETIME_MILLIS = 60 * 60 * 1000L
    fun create(cacheDir: File, date: LocalDate, json: String): File {
        require(json.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Day data too large" }
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
