package dev.hamstercage.ui

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
}
