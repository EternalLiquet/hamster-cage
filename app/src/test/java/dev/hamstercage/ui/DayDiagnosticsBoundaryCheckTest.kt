package dev.hamstercage.ui

import dev.hamstercage.capture.BoundaryCheckEntry
import dev.hamstercage.capture.BoundaryCheckOutcome
import dev.hamstercage.capture.CaptureStatus
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.data.RecordedEvent
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** #135: the day export explains what each started EXIT check attempt did. */
class DayDiagnosticsBoundaryCheckTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val now = Instant.parse("2026-10-20T02:00:00Z")
    private val office = Office("office-secret-id", "Private place", 39.123456, -82.654321)

    private fun fact(id: String, transition: Transition, at: String) =
        RecordedEvent(RawEvent(id, office.id, transition, Instant.parse(at)), Instant.parse(at).plusSeconds(2),
            Instant.parse(at), "PLAY_SERVICES_GEOFENCE", 12f)

    private val data = AppSnapshot(listOf(office), listOf(
        fact("arrival-secret", Transition.ENTER, "2026-10-19T13:00:00Z"),
        fact("exit-secret", Transition.EXIT, "2026-10-19T18:00:00Z")),
        emptyList(), emptyList(), Policy(zoneId = ZoneId.of("America/New_York")))

    private fun check(id: String, attempt: Int, at: String, outcome: BoundaryCheckOutcome,
        accuracy: Float? = null, age: Long? = null) = BoundaryCheckEntry(id, attempt, Instant.parse(at),
        Instant.parse(at).plusSeconds(3), outcome, accuracy, age)

    @Test fun attemptsForIncludedFactsAreAliasedOrderedAndCounted() {
        val checks = listOf(
            check("exit-secret", 2, "2026-10-19T18:01:40Z", BoundaryCheckOutcome.FIX_IMPRECISE, 180f, 900),
            check("exit-secret", 1, "2026-10-19T18:00:40Z", BoundaryCheckOutcome.FIX_CACHED, 25f, 95_000),
            // A check for a fact outside this export is never disclosed.
            check("other-day-secret", 1, "2026-10-12T18:00:40Z", BoundaryCheckOutcome.RECORDED_OUTSIDE))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", boundaryChecks = checks)
        val json = report.json

        assertEquals(2, report.boundaryCheckCount)
        val exitAlias = "fact-2" // arrival-secret sorts before exit-secret
        val first = json.indexOf("\"fact\":\"$exitAlias\",\"attempt\":1,\"startedAt\":\"2026-10-19T18:00:40Z\"")
        val second = json.indexOf("\"fact\":\"$exitAlias\",\"attempt\":2,\"startedAt\":\"2026-10-19T18:01:40Z\"")
        assertTrue(json, first >= 0 && second > first)
        assertTrue(json, json.contains("\"outcome\":\"FIX_CACHED\",\"accuracyMeters\":25.0,\"fixAgeMs\":95000"))
        assertTrue(json, json.contains("\"outcome\":\"FIX_IMPRECISE\",\"accuracyMeters\":180.0,\"fixAgeMs\":900"))
        assertTrue(json, json.contains("\"attemptCount\":2"))
        assertFalse(json, json.contains("RECORDED_OUTSIDE"))
        // The unrelated row is retained, but only its side of the disclosed span is shown.
        assertTrue(json, json.contains("\"oldestRetainedCheckAt\":\"beforeSpan\""))
        listOf("secret", "Private place", "39.123456", "-82.654321").forEach { assertFalse(it, json.contains(it)) }
    }

    // Review finding 1: an absent row says nothing about whether the attempt ran.
    @Test fun missingAttemptsAreReportedAsUnknownNotAsNeverRun() {
        val checks = listOf(
            check("exit-secret", 1, "2026-10-19T18:00:40Z", BoundaryCheckOutcome.FIX_IMPRECISE, 180f, 900),
            check("exit-secret", 3, "2026-10-19T18:02:40Z", BoundaryCheckOutcome.DEADLINE_PASSED_DURING_PREPARATION),
            check("exit-secret", 4, "2026-10-19T18:03:40Z", BoundaryCheckOutcome.PAST_DEADLINE_BEFORE_REQUEST))
        val report = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", boundaryChecks = checks)
        val json = report.json

        assertEquals(3, report.boundaryCheckCount)
        // No row is fabricated for attempts 2 and 5.
        assertEquals(3, "\"attempt\":".toRegex().findAll(json).count())
        assertFalse(json, json.contains("\"attempt\":2"))
        assertFalse(json, json.contains("\"attempt\":5"))
        assertTrue(json, json.contains("\"missingAttemptOutcome\":\"UNKNOWN\""))
        assertTrue(json, json.contains("missing attempt number has an unknown outcome"))
        assertTrue(json, json.contains("cancelled while running"))
        assertTrue(json, json.contains("failed to save its row"))
        listOf("never started", "never ran", "that started").forEach { assertFalse(it, json.contains(it)) }
        // Both deadline labels and the ambiguous legacy label are exported as stored.
        assertTrue(json, json.contains("\"outcome\":\"DEADLINE_PASSED_DURING_PREPARATION\""))
        assertTrue(json, json.contains("\"outcome\":\"PAST_DEADLINE_BEFORE_REQUEST\""))
    }

    @Test fun unreadableLogIsExplicitAndEmptyLogIsNot() {
        val unknown = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", boundaryChecks = null).json
        assertTrue(unknown, unknown.contains("\"boundaryCheckDiagnostics\":null,\"boundaryChecks\":[]"))
        val empty = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", boundaryChecks = emptyList()).json
        assertTrue(empty, empty.contains("\"attemptCount\":0,\"oldestRetainedCheckAt\":null,\"missingAttemptOutcome\":\"UNKNOWN\""))
    }

    @Test fun checksNeverChangeAttendanceResults() {
        fun tail(json: String) = json.substring(json.indexOf("\"sessions\":"))
        val without = dayDiagnostics(data, day, now, null, CaptureStatus(), "test").json
        val with = dayDiagnostics(data, day, now, null, CaptureStatus(), "test", boundaryChecks = listOf(
            check("exit-secret", 1, "2026-10-19T18:00:40Z", BoundaryCheckOutcome.RECORDED_INSIDE, 8f, 1_200))).json
        assertEquals(tail(without), tail(with))
    }
}
