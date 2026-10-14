package dev.hamstercage.ui

import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.FixMetadata
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.data.RegistrationAttempt
import dev.hamstercage.data.RegistrationCycle
import dev.hamstercage.data.RegistrationDiagnostics
import dev.hamstercage.data.RegistrationKind
import dev.hamstercage.data.RegistrationOutcome
import dev.hamstercage.data.RegistrationReason
import dev.hamstercage.data.RegistrationStep
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Schema v2 registration and fix-metadata export. 2025-03-09 is a DST day in America/New_York. */
class DayDiagnosticsRegistrationTest {
    private val day = LocalDate.of(2025, 3, 9)
    private val now = Instant.parse("2025-03-10T10:00:00Z")
    private val a = Office("office-a-secret-id", "Private place A", 39.123456, -82.654321)
    private val b = Office("office-b-secret-id", "Private place B", 40.111111, -83.222222)
    private val c = Office("office-c-secret-id", "Private place C", 41.333333, -84.444444)

    private fun fact(id: String, office: Office, transition: Transition, at: String, fix: FixMetadata? = null) =
        RecordedEvent(RawEvent(id, office.id, transition, Instant.parse(at)), Instant.parse(at).plusSeconds(5),
            Instant.parse(at), "PLAY_SERVICES_GEOFENCE", 10f, fix)

    private fun snapshot(vararg facts: RecordedEvent) = AppSnapshot(listOf(a, b, c), facts.toList(), emptyList(),
        emptyList(), Policy(zoneId = ZoneId.of("America/New_York")))

    private fun cycle(id: String, at: String, kind: RegistrationKind = RegistrationKind.SYNC,
        reason: RegistrationReason = RegistrationReason.PROCESS_START,
        outcome: RegistrationOutcome = RegistrationOutcome.SUCCEEDED, cleanup: RegistrationStep? = null,
        error: String? = null) = RegistrationCycle(id, kind, reason, 3, 2, Instant.parse(at),
        Instant.parse(at).plusSeconds(1), outcome, RegistrationStep.REMOVED, cleanup, error)

    private fun attempt(id: String, cycle: String, office: Office, at: String, trigger: Int = 0,
        status: RegistrationOutcome = RegistrationOutcome.SUCCEEDED, error: String? = null) =
        RegistrationAttempt(id, cycle, office.id, 4, trigger, Instant.parse(at),
            if (status == RegistrationOutcome.INTERRUPTED) null else Instant.parse(at).plusMillis(400), status, error)

    @Test fun bothOfficesKeepTheirOwnRegistrationFromJustBeforeMidnight() {
        val data = snapshot(fact("a-in", a, Transition.ENTER, "2025-03-09T14:00:00Z"),
            fact("b-in", b, Transition.ENTER, "2025-03-09T18:00:00Z"))
        val registrations = RegistrationDiagnostics(
            cycles = listOf(cycle("ancient-cycle", "2025-02-20T12:00:00Z"),
                cycle("older-cycle", "2025-03-08T10:00:00Z"),
                cycle("late-cycle", "2025-03-09T04:58:59Z"),
                cycle("resync-cycle", "2025-03-09T13:00:00Z", RegistrationKind.RESYNC, RegistrationReason.REJECTED_EXIT_RESYNC)),
            attempts = listOf(attempt("b-ancient", "ancient-cycle", b, "2025-02-20T12:00:00Z"),
                attempt("a-older", "older-cycle", a, "2025-03-08T10:00:00Z"),
                // 23:59 local on the previous day, one per office.
                attempt("b-late", "late-cycle", b, "2025-03-09T04:59:00Z"),
                attempt("a-late", "late-cycle", a, "2025-03-09T04:59:30Z"),
                attempt("a-day", "resync-cycle", a, "2025-03-09T13:00:00Z", trigger = 1)))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", registrations)
        val json = report.json

        assertEquals(1, report.registrationCount)
        assertEquals(2, report.registrationContextCount)
        assertEquals(LocalDate.of(2025, 3, 8), report.firstIncludedDay)
        // office-a sorts first, so it is office-1; each office's own last pre-midnight attempt is present.
        assertTrue(json, json.contains("\"registration\":\"reg-1\",\"cycle\":\"cycle-1\",\"office\":\"office-2\""))
        assertTrue(json, json.contains("\"registration\":\"reg-2\",\"cycle\":\"cycle-1\",\"office\":\"office-1\""))
        assertTrue(json, json.contains("\"registration\":\"reg-3\",\"cycle\":\"cycle-2\",\"office\":\"office-1\",\"officeVersion\":4,\"initialTrigger\":1"))
        assertTrue(json.contains("\"startedAt\":\"2025-03-09T04:59:00Z\""))
        assertTrue(json.contains("\"kind\":\"RESYNC\",\"reason\":\"REJECTED_EXIT_RESYNC\""))
        assertTrue(json.contains("\"status\":\"SUCCEEDED\",\"errorCode\":null,\"context\":true"))
        assertTrue(json.contains("\"status\":\"SUCCEEDED\",\"errorCode\":null,\"context\":false"))
        // Older attempts for an office that already has a later one, and anything past the
        // lookback, are not disclosed.
        assertFalse(json.contains("2025-03-08T10:00:00Z"))
        assertFalse(json.contains("2025-02-20"))
        assertTrue(json.contains("\"precedingLookbackDays\":7"))
        assertTrue(json.contains("\"retentionDays\":30"))
        // The oldest retained attempt is outside the disclosed span, so only its side is shown.
        assertTrue(json.contains("\"oldestRetainedAttemptAt\":\"beforeSpan\""))
    }

    @Test fun registrationOnlyOfficeIsAliasedAndNoIdentifiersOrPayloadsLeak() {
        val data = snapshot(fact("a-in", a, Transition.ENTER, "2025-03-09T14:00:00Z"))
        val registrations = RegistrationDiagnostics(
            cycles = listOf(cycle("cycle-uuid-secret", "2025-03-09T15:00:00Z", outcome = RegistrationOutcome.FAILED,
                cleanup = RegistrationStep.REMOVED, error = "java.lang.SecurityException: /data/user/0/secret")),
            attempts = listOf(attempt("attempt-uuid-1", "cycle-uuid-secret", a, "2025-03-09T15:00:00Z"),
                attempt("attempt-uuid-2", "cycle-uuid-secret", c, "2025-03-09T15:00:01Z",
                    status = RegistrationOutcome.FAILED, error = "API_1000")))
        val json = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", registrations).json
        listOf("office-a-secret-id", "office-c-secret-id", "Private place", "39.123456", "41.333333",
            "cycle-uuid-secret", "attempt-uuid", "SecurityException", "/data/user").forEach {
            assertFalse("Leaked $it", json.contains(it))
        }
        // Office C appears only through its registration and still receives a per-export alias.
        assertTrue(json.contains("{\"alias\":\"office-2\",\"radiusMeters\":200.0"))
        assertTrue(json.contains("\"office\":\"office-2\",\"officeVersion\":4,\"initialTrigger\":0"))
        assertTrue(json.contains("\"status\":\"FAILED\",\"errorCode\":\"API_1000\""))
        // A partial batch shows the earlier success and that the cycle's cleanup removed it.
        assertTrue(json.contains("\"outcome\":\"FAILED\",\"removal\":\"REMOVED\",\"cleanup\":\"REMOVED\",\"errorCode\":\"OTHER\""))
        assertTrue(json.contains("\"status\":\"SUCCEEDED\""))
    }

    @Test fun unreadableDiagnosticsAreExplicitAndInterruptedAttemptsAreNotSuccess() {
        val data = snapshot(fact("a-in", a, Transition.ENTER, "2025-03-09T14:00:00Z"))
        val unknown = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", null).json
        assertTrue(unknown.contains("\"registrationDiagnostics\":null"))
        assertTrue(unknown.contains("\"registrations\":[]"))

        val interrupted = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", RegistrationDiagnostics(
            listOf(cycle("c", "2025-03-09T15:00:00Z", outcome = RegistrationOutcome.INTERRUPTED)),
            listOf(attempt("x", "c", a, "2025-03-09T15:00:00Z", status = RegistrationOutcome.INTERRUPTED)))).json
        assertTrue(interrupted.contains("\"completedAt\":null,\"status\":\"INTERRUPTED\""))
        assertFalse(interrupted.contains("\"status\":\"SUCCEEDED\""))
    }

    @Test fun cycleWithoutAttemptsInTheWindowIsIncluded() {
        val data = snapshot(fact("a-in", a, Transition.ENTER, "2025-03-09T14:00:00Z"))
        val json = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", RegistrationDiagnostics(
            listOf(cycle("removal", "2025-03-09T16:00:00Z", RegistrationKind.REMOVE, RegistrationReason.FOREGROUND_CHECK)),
            emptyList())).json
        assertTrue(json.contains("\"cycle\":\"cycle-1\",\"kind\":\"REMOVE\",\"reason\":\"FOREGROUND_CHECK\""))
    }


    @Test fun precedingRegistrationOnlyOfficesAreIncludedEvenWithoutAnyCallbacks() {
        val report = dayDiagnostics(snapshot(), day, now, null, CaptureStatus(), "test", RegistrationDiagnostics(
            listOf(cycle("before", "2025-03-09T04:58:00Z")),
            listOf(attempt("a", "before", a, "2025-03-09T04:59:00Z"),
                attempt("b", "before", b, "2025-03-09T04:59:30Z"))))
        assertEquals(2, report.registrationContextCount)
        assertEquals(day.minusDays(1), report.firstIncludedDay)
        assertTrue(report.json.contains("\"office\":\"office-1\""))
        assertTrue(report.json.contains("\"office\":\"office-2\""))
        assertFalse(report.json.contains(a.id))
        assertFalse(report.json.contains(b.id))
    }

    @Test fun predecessorContextIncludesInterveningRemovalAndFailedSyncWithoutAttempts() {
        val registrations = RegistrationDiagnostics(listOf(
            cycle("before-bound", "2025-02-28T20:00:00Z", RegistrationKind.REMOVE),
            cycle("add", "2025-03-07T10:00:00Z"),
            cycle("remove", "2025-03-08T10:00:00Z", RegistrationKind.REMOVE),
            cycle("failed", "2025-03-09T04:00:00Z", outcome = RegistrationOutcome.FAILED)),
            listOf(attempt("add-a", "add", a, "2025-03-07T10:00:01Z")))
        val report = dayDiagnostics(snapshot(fact("arrival", a, Transition.ENTER, "2025-03-09T14:00:00Z")),
            day, now, null, CaptureStatus(), "test", registrations)
        assertTrue(report.json.contains("2025-03-08T10:00:00Z"))
        assertTrue(report.json.contains("2025-03-09T04:00:00Z"))
        assertFalse(report.json.contains("2025-02-28"))
        assertEquals(day.minusDays(2), report.firstIncludedDay)
        assertEquals(3, "\"cycle\":\"cycle-".toRegex().findAll(
            report.json.substringAfter("\"registrationCycles\":[").substringBefore("],\"registrations\"")).count())
    }

    @Test fun latestCycleWithoutAttemptsExplainsNoRegistrationInTheLookback() {
        val report = dayDiagnostics(snapshot(), day, now, null, CaptureStatus(), "test", RegistrationDiagnostics(
            listOf(cycle("too-old", "2025-02-28T20:00:00Z", RegistrationKind.REMOVE),
                cycle("remove", "2025-03-08T20:00:00Z", RegistrationKind.REMOVE)), emptyList()))
        assertTrue(report.json.contains("2025-03-08T20:00:00Z"))
        assertFalse(report.json.contains("2025-02-28"))
        assertEquals(day.minusDays(1), report.firstIncludedDay)
    }

    @Test fun fixMetadataIsExportedAsStoredAndLegacyRowsStayUnknown() {
        val data = snapshot(
            fact("legacy", a, Transition.ENTER, "2025-03-09T14:00:00Z"),
            fact("fresh", a, Transition.EXIT, "2025-03-09T15:00:00Z", FixMetadata(850, "fused", false)),
            fact("stale", a, Transition.ENTER, "2025-03-09T16:00:00Z", FixMetadata(null, "other", true)))
        val json = dayDiagnostics(data, day, now, null, CaptureStatus(), "test").json
        assertTrue(json.contains("\"accuracyMeters\":10.0,\"fixAgeMs\":null,\"provider\":null,\"isMock\":null"))
        assertTrue(json.contains("\"fixAgeMs\":850,\"provider\":\"fused\",\"isMock\":false"))
        assertTrue(json.contains("\"fixAgeMs\":null,\"provider\":\"other\",\"isMock\":true"))
    }

    @Test fun registrationsNeverChangeAttendanceResults() {
        val data = snapshot(fact("a-in", a, Transition.ENTER, "2025-03-09T14:00:00Z"),
            fact("a-out", a, Transition.EXIT, "2025-03-09T20:00:00Z"))
        val without = dayDiagnostics(data, day, now, null, CaptureStatus(), "test").json
        val with = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", RegistrationDiagnostics(
            listOf(cycle("c", "2025-03-09T14:30:00Z")), listOf(attempt("x", "c", a, "2025-03-09T14:30:00Z")))).json
        fun tail(json: String) = json.substring(json.indexOf("\"sessions\":"))
        assertEquals(tail(without), tail(with))
    }
}
