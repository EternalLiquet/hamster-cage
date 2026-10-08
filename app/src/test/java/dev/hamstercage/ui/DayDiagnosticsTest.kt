package dev.hamstercage.ui

import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DayDiagnosticsTest {
    private val day = LocalDate.of(2025, 3, 9)
    private val office = Office("sensitive-office-id", "Private home address", 39.123456, -82.654321)
    private val now = Instant.parse("2025-03-10T10:00:00Z")
    private fun evidence(id: String, transition: Transition, at: String, source: String = "PLAY_SERVICES_GEOFENCE") =
        RecordedEvent(RawEvent(id, office.id, transition, Instant.parse(at)), Instant.parse(at).plusSeconds(13),
            Instant.parse(at), source, 17.5f)
    private fun snapshot(vararg facts: RecordedEvent) = AppSnapshot(listOf(office), facts.toList(), emptyList(),
        emptyList(), Policy(zoneId = ZoneId.of("America/New_York")))

    @Test fun selectedDayUsesDstWindowAndOnePriorLocalDayContext() {
        val data = snapshot(
            evidence("old", Transition.EXIT, "2025-03-08T06:00:00Z"),
            evidence("prior", Transition.EXIT, "2025-03-09T04:59:00Z"),
            evidence("arrival", Transition.ENTER, "2025-03-09T05:00:00Z"),
            evidence("departure", Transition.EXIT, "2025-03-10T03:59:00Z"),
            evidence("after", Transition.ENTER, "2025-03-10T04:00:00Z"))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test")
        assertEquals(2, report.observationCount)
        assertEquals(1, report.contextCount)
        assertEquals(day.minusDays(1), report.firstIncludedDay)
        assertEquals(day, report.lastIncludedDay)
        assertTrue(report.json.contains("\"windowStartInclusive\":\"2025-03-09T05:00:00Z\""))
        assertTrue(report.json.contains("\"windowEndExclusive\":\"2025-03-10T04:00:00Z\""))
        assertFalse(report.json.contains("2025-03-08T06:00:00Z"))
        assertFalse(report.json.contains("\"eventAt\":\"2025-03-10T04:00:00Z\""))
    }

    @Test fun stableAliasesRetainRawTimingAndSourceWithoutLocationNamesOrNotes() {
        val data = snapshot(evidence("actual-raw-uuid", Transition.ENTER, "2025-03-09T15:00:00Z"),
            evidence("second-uuid", Transition.EXIT, "2025-03-09T17:00:00Z")).copy(
            manualSessions = listOf(ManualSession("manual-secret", office.id, Instant.parse("2025-03-09T19:00:00Z"),
                Instant.parse("2025-03-09T20:00:00Z"), now, "Sensitive note")))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test")
        assertEquals(report.json, dayDiagnostics(data, day, now, null, CaptureStatus(), "test").json)
        listOf("Private home address", "39.123456", "-82.654321", "sensitive-office-id",
            "actual-raw-uuid", "second-uuid", "manual-secret", "Sensitive note").forEach {
            assertFalse("Leaked $it", report.json.contains(it))
        }
        assertTrue(report.json.contains("\"fact\":\"fact-1\""))
        assertTrue(report.json.contains("\"fact\":\"fact-2\""))
        assertTrue(report.json.contains("\"receivedAt\":\"2025-03-09T15:00:13Z\""))
        assertTrue(report.json.contains("\"accuracyMeters\":17.5"))
        assertTrue(report.json.contains("\"entryGraceMinutes\":5"))
        assertTrue(report.json.contains("\"schemaVersion\":1"))
    }
}
