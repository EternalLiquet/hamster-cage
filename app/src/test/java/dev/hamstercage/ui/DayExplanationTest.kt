package dev.hamstercage.ui

import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DayExplanationTest {
    private val day = LocalDate.of(2026, 9, 23)
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val office = Office("synthetic", "Synthetic office", 0.0, 0.0)
    private fun at(time: String) = Instant.parse("2026-09-23T${time}:00Z")
    private fun input(vararg events: RawEvent) = AttendanceInput(listOf(office), events.toList(),
        policy = Policy(zoneId = ZoneId.of("UTC")), now = now, historyStartDate = day)
    private fun raw(id: String, transition: Transition, time: String) = RawEvent(id, office.id, transition, at(time))
    private fun explain(input: AttendanceInput, date: LocalDate = day) = explainDay(input, AttendanceEngine.derive(input), date).also {
        assertEquals(AttendanceEngine.daily(input, AttendanceEngine.derive(input), date), it.summary)
        assertEquals(it.summary.creditedMinutes, it.intervals.sumOf { interval -> interval.minutes }, 0.00001)
    }

    @Test fun splitDayKeepsRawBoundsAndGraceSeparateWithMatchingTotal() {
        val data = input(raw("a", Transition.ENTER, "09:15"), raw("b", Transition.EXIT, "11:45"),
            raw("c", Transition.ENTER, "13:30"), raw("d", Transition.EXIT, "17:00"))
        val detail = explain(data)
        assertEquals(350.0, detail.summary.creditedMinutes, 0.001)
        assertEquals(at("09:15"), detail.originalSessions.first().start)
        assertEquals(at("09:20"), detail.intervals.first().start)
        assertEquals(data.events, detail.rawEvents)
        assertTrue(detail.denominator.contains("1 day × 360 minutes"))
    }

    @Test fun laterOutsideCheckKeepsUnknownExitAndUncreditedSpanVisible() {
        val data = input(raw("in", Transition.ENTER, "09:00"),
            raw("outside", Transition.ABSENCE, "10:00"))
        val detail = explain(data)
        assertEquals(0.0, detail.summary.creditedMinutes, 0.001)
        assertEquals(listOf("in", "outside"), detail.rawEvents.map { it.id })
        assertEquals(at("10:00"), detail.sessions.single().end)
        assertTrue(ReviewReason.UNCONFIRMED_GAP in detail.sessions.single().reviewReasons)
        val explanation = reviewExplanation(ReviewReason.UNCONFIRMED_GAP)
        assertTrue(explanation.contains("exit time is unknown"))
        assertFalse(explanation.contains("restarted presence"))
    }

    @Test fun overlapAndReconciledGapKeepAllProvenanceWithoutDoubleCredit() {
        val data = input(raw("a", Transition.ENTER, "09:00"), raw("b", Transition.EXIT, "14:00"),
            raw("c", Transition.ENTER, "14:04"), raw("d", Transition.EXIT, "17:00"))
        val detail = explain(data)
        assertEquals(470.0, detail.summary.creditedMinutes, 0.001)
        assertEquals(2, detail.intervals.size)
        assertEquals(setOf("session:a", "session:c"), detail.intervals.first().sessionIds)
        val gap = explain(data.copy(events = data.events.map { if (it.id == "c") it.copy(at = at("14:05")) else it }))
        assertTrue(gap.intervals.first().reconciledGap)
        assertEquals(at("14:05"), gap.intervals.first().end)
        assertEquals(at("14:10"), gap.intervals.last().start)
        assertEquals(470.0, gap.summary.creditedMinutes, 0.001)
    }

    @Test fun correctionMovingAcrossDaysRetainsRawEvidenceAndAuditOnBothDays() {
        val data = input(raw("a", Transition.ENTER, "09:00"), raw("b", Transition.EXIT, "10:00")).copy(
            corrections = listOf(Correction("edit", "session:a", at("11:00").minusSeconds(86400), at("12:00").minusSeconds(86400), now)))
        val originalDay = explain(data)
        val movedDay = explain(data, day.minusDays(1))
        assertEquals(0.0, originalDay.summary.creditedMinutes, 0.001)
        assertEquals(55.0, movedDay.summary.creditedMinutes, 0.001)
        listOf(originalDay, movedDay).forEach {
            assertEquals(data.events, it.rawEvents)
            assertEquals(data.corrections, it.corrections)
            assertEquals(at("09:00"), it.originalSessions.single().start)
            assertEquals(data.corrections.single().start, it.sessions.single().start)
        }
    }

    @Test fun malformedAndDuplicateCopiesAreRetainedWithPlainReviewReasons() {
        val data = input(raw("exit", Transition.EXIT, "08:00"), raw("a", Transition.ENTER, "09:00"),
            raw("dup", Transition.ENTER, "09:00"), raw("b", Transition.EXIT, "10:00"),
            raw("conflict", Transition.ENTER, "11:00"), raw("conflict", Transition.EXIT, "12:00"))
        val detail = explain(data)
        assertEquals(6, detail.rawEvents.size)
        assertTrue(detail.reviews.any { it.reason == ReviewReason.MISSING_ENTER })
        assertTrue(detail.reviews.any { it.reason == ReviewReason.DUPLICATE_EVENT })
        assertTrue(detail.reviews.any { it.reason == ReviewReason.CONFLICTING_EVENT_ID })
        assertTrue(reviewExplanation(ReviewReason.MISSING_ENTER).contains("No start"))
        assertTrue(reviewExplanation(ReviewReason.DUPLICATE_EVENT).contains("no duplicate credit"))
    }

    @Test fun holidayAndWfhKeepCreditButExplainZeroRequirement() {
        val base = input(raw("a", Transition.ENTER, "09:00"), raw("b", Transition.EXIT, "10:00"))
        val detail = explain(base.copy(policy = base.policy.copy(excludedDates = listOf(ExcludedDate(day, ExclusionReason.BANK_HOLIDAY)), wfhDates = setOf(day))))
        assertEquals(0, detail.summary.requiredMinutes)
        assertEquals(55.0, detail.summary.creditedMinutes, 0.001)
        assertTrue(detail.denominator.contains("BANK HOLIDAY"))
        assertTrue(detail.denominator.contains("WFH is a label"))
    }

    @Test fun graceAcrossMidnightRetainsContributingSessionsAndSourceEvents() {
        val data = input(RawEvent("a", office.id, Transition.ENTER, Instant.parse("2026-09-23T23:53:00Z")),
            RawEvent("b", office.id, Transition.EXIT, Instant.parse("2026-09-24T01:00:00Z")))
        val detail = explain(data)
        assertEquals(2.0, detail.summary.creditedMinutes, 0.001)
        assertEquals(data.events, detail.rawEvents)
        assertEquals(1, detail.sessions.size)
        assertEquals(Instant.parse("2026-09-24T00:00:00Z"), detail.intervals.single().end)
    }

    @Test fun unlinkedRejectedEvidenceAndDisplaySanitizationDoNotLoseStoredFacts() {
        val correction = Correction("orphan", "session:missing", at("09:00"), at("10:00"), now, "note\u202e\u0000")
        val manual = ManualSession("invalid", "missing-office", at("11:00"), at("12:00"), now)
        val data = input().copy(corrections = listOf(correction), manualSessions = listOf(manual))
        val detail = explain(data)
        assertEquals(listOf(correction), detail.corrections)
        assertEquals(listOf(manual), detail.manualSessions)
        assertTrue(detail.reviews.any { it.reason == ReviewReason.ORPHAN_CORRECTION })
        assertTrue(detail.reviews.any { it.reason == ReviewReason.UNKNOWN_OFFICE })
        assertEquals("note", evidenceText(correction.note))
        assertEquals("note\u202e\u0000", data.corrections.single().note)
    }

    @Test fun dstDayUsesRealPolicyLocalBoundariesInsteadOfTwentyFourHours() {
        val date = LocalDate.of(2025, 3, 9)
        val data = input().copy(offices = listOf(office.copy(entryGraceMinutes = 0, exitGraceMinutes = 0)),
            events = listOf(RawEvent("a", office.id, Transition.ENTER, Instant.parse("2025-03-09T05:00:00Z")),
                RawEvent("b", office.id, Transition.EXIT, Instant.parse("2025-03-10T04:00:00Z"))),
            policy = Policy(zoneId = ZoneId.of("America/New_York")), now = Instant.parse("2025-03-10T12:00:00Z"))
        val detail = explain(data, date)
        assertEquals(23 * 60.0, detail.summary.creditedMinutes, 0.001)
        assertEquals(data.events.first().at, detail.intervals.single().start)
        assertEquals(data.events.last().at, detail.intervals.single().end)
    }
}
