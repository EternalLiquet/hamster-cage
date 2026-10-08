package dev.hamstercage.ui

import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.capture.CoverageLedger
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
        assertEquals(2, report.contextCount)
        assertEquals(day.minusDays(1), report.firstIncludedDay)
        assertEquals(day.plusDays(1), report.lastIncludedDay)
        assertTrue(report.json.contains("\"windowStartInclusive\":\"2025-03-09T05:00:00Z\""))
        assertTrue(report.json.contains("\"windowEndExclusive\":\"2025-03-10T04:00:00Z\""))
        assertFalse(report.json.contains("2025-03-08T06:00:00Z"))
        assertTrue(report.json.contains("\"eventAt\":\"2025-03-10T04:00:00Z\""))
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

    @Test fun missingCorrectionTargetsAndNoteOnlyConflictsRemainDistinguishable() {
        val start = Instant.parse("2025-03-09T16:00:00Z")
        val end = Instant.parse("2025-03-09T17:00:00Z")
        val data = snapshot().copy(
            corrections = listOf(
                Correction("same-edit", "session:absent-a", start, end, now, "secret one"),
                Correction("same-edit", "session:absent-a", start, end, now, "secret two"),
                Correction("other-edit", "session:absent-b", start, end, now, "secret one")))
        val json = dayDiagnostics(data, day, now, null, CaptureStatus(), "test").json
        assertTrue(json.contains("\"redactedNoteVariant\":1"))
        assertTrue(json.contains("\"redactedNoteVariant\":2"))
        assertEquals(2, "\"target\":\"target-1\"".toRegex().findAll(json).count())
        assertEquals(1, "\"target\":\"target-2\"".toRegex().findAll(json).count())
        listOf("absent-a", "absent-b", "same-edit", "other-edit", "secret one", "secret two")
            .forEach { assertFalse(json.contains(it)) }
    }

    @Test fun nextDayCrossOfficePresenceCanExplainLateExit() {
        val other = Office("other-private-id", "Other private place", 40.0, -83.0)
        val unrelated = Office("unrelated-private-id", "Unrelated private place", 41.0, -84.0)
        val data = snapshot(
            evidence("arrive", Transition.ENTER, "2025-03-09T20:00:00Z"),
            evidence("exit", Transition.EXIT, "2025-03-10T03:50:00Z"))
            .copy(offices = listOf(office, other, unrelated), eventEvidence = listOf(
                evidence("arrive", Transition.ENTER, "2025-03-09T20:00:00Z"),
                evidence("exit", Transition.EXIT, "2025-03-10T03:50:00Z"),
                evidence("return-private-id", Transition.ENTER, "2025-03-10T04:04:00Z"),
                RecordedEvent(RawEvent("untrusted-earlier", unrelated.id, Transition.PRESENCE,
                    Instant.parse("2025-03-10T04:01:00Z")), Instant.parse("2025-03-10T04:01:10Z"),
                    Instant.parse("2025-03-10T04:01:00Z"), "PLAY_SERVICES_GEOFENCE"),
                RecordedEvent(RawEvent("later-private-id", other.id, Transition.PRESENCE,
                    Instant.parse("2025-03-10T04:05:00Z")), Instant.parse("2025-03-10T04:05:10Z"),
                    Instant.parse("2025-03-10T04:05:00Z"), "FOREGROUND_LOCATION_RECONCILIATION"),
                RecordedEvent(RawEvent("unrelated-fact", unrelated.id, Transition.PRESENCE,
                    Instant.parse("2025-03-10T04:30:00Z")), Instant.parse("2025-03-10T04:30:10Z"),
                    Instant.parse("2025-03-10T04:30:00Z"), "FOREGROUND_LOCATION_RECONCILIATION")))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test")
        assertEquals(day.plusDays(1), report.lastIncludedDay)
        assertTrue(report.json.contains("2025-03-10T04:05:00Z"))
        assertTrue(report.json.contains("2025-03-10T04:04:00Z"))
        assertFalse(report.json.contains("later-private-id"))
        assertFalse(report.json.contains("Other private place"))
        assertFalse(report.json.contains("2025-03-10T04:30:00Z"))
        assertFalse(report.json.contains("2025-03-10T04:01:00Z"))
        assertFalse(report.json.contains("Unrelated private place"))
    }

    @Test fun previewSpanIncludesManualAndEditTimesOutsideSelectedDay() {
        val data = snapshot().copy(manualSessions = listOf(ManualSession("manual", office.id,
            Instant.parse("2025-03-09T04:00:00Z"), Instant.parse("2025-03-09T06:00:00Z"),
            Instant.parse("2025-03-10T12:00:00Z"))))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test")
        assertEquals(day.minusDays(1), report.firstIncludedDay)
        assertEquals(day.plusDays(1), report.lastIncludedDay)
        assertEquals(0, report.observationCount)
    }
    /**
     * The engine classifies facts with derived inputs computed from the whole stored history,
     * including evidence beyond the export bounds. Each exported fact must carry the
     * classifications the engine actually used, or a bounded replay could disagree with it.
     */
    @Test fun everyExportedFactCarriesTheEngineClassificationsItWasDerivedWith() {
        val receiptTimed = RecordedEvent(RawEvent("receipt-id", office.id, Transition.ENTER,
            Instant.parse("2025-03-09T19:00:00Z")), Instant.parse("2025-03-09T19:00:00Z"), null,
            "PLAY_SERVICES_GEOFENCE")
        val data = snapshot(evidence("arrive-id", Transition.ENTER, "2025-03-09T14:00:00Z"),
            evidence("leave-id", Transition.EXIT, "2025-03-09T18:00:00Z"), receiptTimed,
            evidence("receipt-exit-id", Transition.EXIT, "2025-03-09T20:00:00Z"),
            RecordedEvent(RawEvent("check-id", office.id, Transition.PRESENCE,
                Instant.parse("2025-03-09T19:30:00Z")), Instant.parse("2025-03-09T19:30:05Z"),
                Instant.parse("2025-03-09T19:30:00Z"), "ADAPTIVE_RECOVERY_CONFIRMATION", 12f))
        val json = dayDiagnostics(data, day, now, null, CaptureStatus(), "test").json
        val input = data.input(now)
        val expected = mapOf("adaptivePresence" to input.adaptivePresenceIds, "candidateExit" to input.candidateExitIds,
            "delayedExit" to input.delayedExitIds, "provisionalAbsence" to input.provisionalAbsenceIds,
            "provisionalPresence" to input.provisionalPresenceIds, "receiptTimed" to input.receiptTimedEventIds,
            "recoveryPresence" to input.recoveryPresenceIds, "rejectedExit" to input.rejectedExitIds,
            "unconfirmedExit" to input.unconfirmedExitIds, "unsafeRecoveryPresence" to input.unsafeRecoveryPresenceIds)
        val observations = "\\{\"fact\":[^}]*\\}".toRegex().findAll(json).map { it.value }.toList()
        assertEquals(5, observations.size)
        data.eventEvidence.forEach { item ->
            val at = item.event.at.toString()
            val exported = observations.single { it.contains("\"eventAt\":\"$at\"") }
            val roles = expected.filterValues { item.event.id in it }.keys.joinToString(",") { "\"$it\"" }
            assertTrue("$at: $exported", exported.endsWith("\"engineRoles\":[$roles]}"))
        }
        assertTrue(observations.single { it.contains("2025-03-09T19:00:00Z") }.contains("\"receiptTimed\""))
        assertTrue(observations.single { it.contains("2025-03-09T19:30:00Z") }
            .contains("\"engineRoles\":[\"recoveryPresence\",\"unsafeRecoveryPresence\"]"))
        assertTrue(observations.single { it.contains("\"eventAt\":\"2025-03-09T20:00:00Z\"") }.contains("\"unconfirmedExit\""))
    }

    @Test fun recoveryWindowThatDecidesAdaptiveSplitsIsIncluded() {
        val coverage = CoverageLedger(lastOutageStartedAt = Instant.parse("2025-03-09T16:00:00Z"),
            lastOutageHealthySince = Instant.parse("2025-03-09T12:00:00Z"),
            outageHealthySince = Instant.parse("2025-03-09T13:00:00Z"))
        val json = dayDiagnostics(snapshot(), day, now, coverage, CaptureStatus(), "test").json
        assertTrue(json.contains("\"lastOutageStartedAt\":\"2025-03-09T16:00:00Z\""))
        assertTrue(json.contains("\"lastOutageHealthySince\":\"2025-03-09T12:00:00Z\""))
        assertTrue(json.contains("\"openOutageHealthySince\":\"2025-03-09T13:00:00Z\""))
    }
}
