package dev.hamstercage.ui

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DayShareFilesTest {
    private val day = LocalDate.of(2026, 10, 8)

    @Test fun previewCancelHasNoFileAndRepeatedConfirmedSharesAreDistinct() {
        val cache = Files.createTempDirectory("hamster-share-test").toFile()
        try {
            // Preparing a preview is in-memory; only the explicit create operation writes.
            val preview = "{\"schemaVersion\":1}"
            assertFalse(cache.resolve("day-diagnostics").exists())
            val first = DayShareFiles.create(cache, day, preview)
            val second = DayShareFiles.create(cache, day, preview)
            assertNotEquals(first.name, second.name)
            assertEquals(preview, first.readText())
            assertEquals(preview, second.readText())
            assertTrue(first.canonicalPath.startsWith(cache.resolve("day-diagnostics").canonicalPath))
            assertTrue(second.canonicalPath.startsWith(cache.resolve("day-diagnostics").canonicalPath))
        } finally { cache.deleteRecursively() }
    }

    @Test fun expiredFilesAndHistoryDeletionCleanOnlyTheDiagnosticDirectory() {
        val cache = Files.createTempDirectory("hamster-share-test").toFile()
        try {
            val old = DayShareFiles.create(cache, day, "old")
            val current = DayShareFiles.create(cache, day, "current")
            val unrelated = cache.resolve("other-cache").apply { writeText("keep") }
            val now = System.currentTimeMillis()
            assertTrue(old.setLastModified(now - 2 * 60 * 60 * 1000L))
            DayShareFiles.clearExpired(cache, now)
            assertFalse(old.exists())
            assertTrue(current.exists())
            assertTrue(DayShareFiles.clearAll(cache))
            assertFalse(current.exists())
            assertTrue(unrelated.exists())
        } finally { cache.deleteRecursively() }
    }

    @Test fun oversizedDraftLeavesNoFile() {
        val cache = Files.createTempDirectory("hamster-share-test").toFile()
        try {
            try {
                DayShareFiles.create(cache, day, "x".repeat(2_000_001))
                fail("Oversized draft should be refused")
            } catch (_: IllegalArgumentException) { }
            assertFalse(cache.resolve("day-diagnostics").exists())
        } finally { cache.deleteRecursively() }
    }

    @Test fun saveNameIsTheSelectedDay() {
        assertEquals("hamster-day-2026-10-08.json", daySaveFileName(day))
    }

    @Test fun saveCopiesTheExactReviewedBytesAndClosesTheStream() {
        val cache = Files.createTempDirectory("hamster-save-test").toFile()
        try {
            val reviewed = "{\"schemaVersion\":1,\"note\":\"caf\u00e9\"}"
            val staged = DayShareFiles.create(cache, day, reviewed)
            var closed = false
            val target = object : ByteArrayOutputStream() {
                override fun close() { closed = true; super.close() }
            }
            copyStagedDayData(staged) { target }
            assertTrue(closed)
            assertEquals(reviewed, target.toString(Charsets.UTF_8.name()))
        } finally { cache.deleteRecursively() }
    }

    @Test fun saveFailuresAlwaysThrowSoSuccessIsNeverReported() {
        val cache = Files.createTempDirectory("hamster-save-test").toFile()
        try {
            val staged = DayShareFiles.create(cache, day, "{}")
            // A provider that returns no stream.
            assertThrows(IOException::class.java) { copyStagedDayData(staged) { null } }
            // A provider that refuses to open.
            assertThrows(SecurityException::class.java) {
                copyStagedDayData(staged) { throw SecurityException("denied") }
            }
            // A failed write still closes the stream and surfaces the write error.
            var closedAfterWriteFailure = false
            val failingWrite = object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
                override fun close() { closedAfterWriteFailure = true }
            }
            val writeError = assertThrows(IOException::class.java) { copyStagedDayData(staged) { failingWrite } }
            assertEquals("disk full", writeError.message)
            assertTrue(closedAfterWriteFailure)
            // Some providers only report a failed commit when the stream closes.
            val failingClose = object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun close() = throw IOException("commit failed")
            }
            assertThrows(IOException::class.java) { copyStagedDayData(staged) { failingClose } }
        } finally { cache.deleteRecursively() }
    }

    @Test fun saveRefusesMissingOrOversizedStagedData() {
        val cache = Files.createTempDirectory("hamster-save-test").toFile()
        try {
            var opened = false
            val missing = cache.resolve("day-diagnostics/gone.json")
            // History deletion clears the staging directory before a late picker result arrives.
            assertThrows(IOException::class.java) { copyStagedDayData(missing) { opened = true; ByteArrayOutputStream() } }
            val oversized = cache.resolve("oversized.json").apply { writeText("x".repeat(2_000_001)) }
            assertThrows(IllegalArgumentException::class.java) {
                copyStagedDayData(oversized) { opened = true; ByteArrayOutputStream() }
            }
            assertFalse("No destination may be opened for refused data", opened)
        } finally { cache.deleteRecursively() }
    }
}
