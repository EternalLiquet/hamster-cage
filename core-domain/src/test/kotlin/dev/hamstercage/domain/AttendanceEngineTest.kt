package dev.hamstercage.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AttendanceEngineTest {
    // Reconstruct absolute intervals here; calendar-window calculations are issue #21.
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 9, 23)
    private val office = Office("a", "Synthetic office", 0.0, 0.0)
    private fun at(time: String, date: LocalDate = day): Instant = date.atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant()
    private fun enter(id: String, time: String, officeId: String = "a", date: LocalDate = day) = RawEvent(id, officeId, Transition.ENTER, at(time, date))
    private fun exit(id: String, time: String, officeId: String = "a", date: LocalDate = day) = RawEvent(id, officeId, Transition.EXIT, at(time, date))
    private fun input(events: List<RawEvent>, now: Instant = at("20:00"), policy: Policy = Policy(), offices: List<Office> = listOf(office), corrections: List<Correction> = emptyList()) =
        AttendanceInput(offices, events, corrections, policy, now, day.minusDays(100))
    private fun minutes(input: AttendanceInput) = AttendanceEngine.derive(input).intervals.sumOf { it.minutes }
    private fun assertMinutes(expected: Double, input: AttendanceInput) = assertEquals(expected, minutes(input), 0.00001)

    @Test fun midnightKeepsOneAbsoluteIntervalAndBothRawBoundaries() {
        val data = input(listOf(enter("in", "23:00"), exit("out", "01:00", date = day.plusDays(1))), now = at("02:00", day.plusDays(1)))
        val result = AttendanceEngine.derive(data)
        assertMinutes(115.0, data)
        assertEquals(at("23:05"), result.intervals.single().start)
        assertEquals(at("01:00", day.plusDays(1)), result.intervals.single().end)
        assertEquals(setOf("in", "out"), result.sessions.single().sourceEventIds)
    }

    @Test fun simultaneousBoundariesAndReplayedIdsCannotInventAnOpenShift() {
        val events = listOf(enter("z", "09:00"), exit("m", "09:00"))
        val data = input(events)
        val replayed = data.copy(events = events + enter("a", "09:00"))
        for (candidate in listOf(data, replayed, replayed.copy(events = replayed.events.reversed()))) {
            assertMinutes(0.0, candidate)
            val result = AttendanceEngine.derive(candidate)
            assertFalse(result.sessions.single().isOpen)
            assertTrue(result.reviews.any { it.reason == ReviewReason.ZERO_LENGTH_SESSION })
        }
    }

    @Test fun simultaneousExitAndNextEnterRetainBothVisitsWithoutDependingOnIds() {
        val events = listOf(enter("first", "09:00"), exit("noon-out", "12:00"), enter("noon-in", "12:00"), exit("last", "15:00"))
        val data = input(events, policy = Policy(shortGapMinutes = 0), offices = listOf(office.copy(entryGraceMinutes = 0, exitGraceMinutes = 0)))
        val result = AttendanceEngine.derive(data)
        assertMinutes(360.0, data)
        assertEquals(2, result.sessions.size)
        assertTrue(result.reviews.isEmpty())
        assertEquals(setOf("first", "noon-out"), result.sessions[0].sourceEventIds)
        assertEquals(setOf("noon-in", "last"), result.sessions[1].sourceEventIds)
        assertEquals(result, AttendanceEngine.derive(data.copy(events = events.reversed())))
        assertMinutes(360.0, data.copy(events = events + events + enter("a-replay", "12:00") + exit("z-replay", "12:00")))
    }

    @Test fun newerMalformedCorrectionCannotDiscardAnEarlierValidEdit() {
        val valid = Correction("valid", "session:in", at("10:00"), at("14:00"), at("18:00"))
        val invalid = valid.copy(id = "invalid", end = at("09:00"), createdAt = at("19:00"))
        val data = input(listOf(enter("in", "09:00"), exit("out", "15:00")), corrections = listOf(valid, invalid))
        val result = AttendanceEngine.derive(data)
        assertMinutes(235.0, data)
        assertEquals("valid", result.sessions.single().correctionId)
        assertTrue(result.reviews.any { it.reason == ReviewReason.INVALID_CORRECTION })
        assertEquals(result, AttendanceEngine.derive(data.copy(corrections = data.corrections.reversed())))
        assertEquals(listOf(valid, invalid), data.corrections)
    }

    @Test fun unpersistableTimesArePreservedForReviewWithoutOverflow() {
        val raw = enter("bad", "09:00").copy(at = Instant.MIN)
        val manual = ManualSession("m", "a", Instant.MIN, at("10:00"), at("18:00"))
        val correction = Correction("c", "session:in", Instant.MIN, at("10:00"), at("18:00"))
        val data = input(listOf(raw, enter("in", "09:00"), exit("out", "10:00")), corrections = listOf(correction))
            .copy(manualSessions = listOf(manual))
        assertMinutes(55.0, data)
        assertEquals(Instant.MIN, data.events.first().at)
        assertTrue(AttendanceEngine.derive(data).reviews.map { it.reason }.containsAll(listOf(
            ReviewReason.INVALID_EVENT, ReviewReason.INVALID_MANUAL_SESSION, ReviewReason.INVALID_CORRECTION)))
    }

    @Test fun extremeRepresentableIntervalDoesNotOverflowDurationArithmetic() {
        val start = Instant.ofEpochMilli(Long.MIN_VALUE)
        val end = Instant.ofEpochMilli(Long.MAX_VALUE)
        val interval = CreditedInterval(start, end, setOf("extreme"))
        assertTrue(interval.minutes.isFinite() && interval.minutes > 0)
        val data = input(listOf(RawEvent("in", "a", Transition.ENTER, start), RawEvent("out", "a", Transition.EXIT, end)), now = end)
        assertTrue(minutes(data).isFinite())
        assertEquals(end, AttendanceEngine.derive(data).intervals.single().end)
    }

    @Test fun conflictingCorrectionIdentityIsNotResolvedByInputOrder() {
        val first = Correction("same", "session:in", at("09:00"), at("14:00"), at("18:00"))
        val second = first.copy(end = at("13:00"))
        val data = input(listOf(enter("in", "09:00"), exit("out", "15:00")), corrections = listOf(first, second))
        val result = AttendanceEngine.derive(data)
        assertMinutes(355.0, data)
        assertTrue(result.reviews.any { it.reason == ReviewReason.INVALID_CORRECTION })
        assertEquals(result, AttendanceEngine.derive(data.copy(corrections = listOf(second, first))))
    }

    @Test fun malformedOfficeAndPolicyNumbersAreRejectedAtTheBoundary() {
        val invalid = listOf<() -> Any>(
            { office.copy(latitude = Double.NaN) }, { office.copy(longitude = Double.POSITIVE_INFINITY) },
            { office.copy(radiusMeters = Float.NaN) }, { office.copy(radiusMeters = 49f) },
            { office.copy(entryGraceMinutes = -1) }, { office.copy(exitGraceMinutes = Int.MAX_VALUE) },
            { Policy(shortGapMinutes = Int.MAX_VALUE) }, { Policy(maxOpenSessionHours = 0) },
        )
        invalid.forEach { construct -> assertThrows(IllegalArgumentException::class.java) { construct() } }
    }

    @Test fun lateDuplicateWithEarlierIdKeepsExistingCorrectionAndRawProvenance() {
        val original = enter("z", "09:00")
        val events = listOf(original, exit("out", "15:00"))
        val correction = Correction("edited", "session:z", at("10:00"), at("14:00"), at("18:00"))
        val initial = input(events, corrections = listOf(correction))
        val replayed = initial.copy(events = events + original.copy(id = "a"))
        val result = AttendanceEngine.derive(replayed)
        assertMinutes(235.0, initial)
        assertMinutes(235.0, replayed)
        assertEquals("edited", result.sessions.single().correctionId)
        assertTrue("session:z" in result.sessions.single().correctionTargetIds)
        assertEquals(setOf("a", "z", "out"), result.sessions.single().sourceEventIds)
        assertFalse(result.reviews.any { it.reason == ReviewReason.ORPHAN_CORRECTION })
        assertEquals(result, AttendanceEngine.derive(replayed.copy(events = replayed.events.reversed())))
        assertEquals(events, initial.events)
    }

    @Test fun lateMissingEnterKeepsCorrectionPreviouslyAttachedToExit() {
        val correction = Correction("repair", "session:out", at("09:30"), at("14:30"), at("18:00"))
        val original = input(listOf(exit("out", "15:00")), corrections = listOf(correction))
        val late = original.copy(events = original.events + enter("in", "09:00"))
        assertMinutes(295.0, original)
        assertMinutes(295.0, late)
        val session = AttendanceEngine.derive(late).sessions.single()
        assertEquals("repair", session.correctionId)
        assertEquals(setOf("in", "out"), session.sourceEventIds)
    }

    @Test fun blankEventIdentityRemainsReviewableWithoutCredit() {
        val data = input(listOf(enter("", "09:00")))
        val result = AttendanceEngine.derive(data)
        assertTrue(result.intervals.isEmpty())
        assertEquals(ReviewReason.INVALID_EVENT, result.reviews.single().reason)
        assertEquals("", data.events.single().id)
    }

    @Test fun zeroLengthManualAndCorrectionCannotCreateWalkingCredit() {
        val manual = ManualSession("m", "a", at("09:00"), at("09:00"), at("18:00"))
        val manualData = input(emptyList()).copy(manualSessions = listOf(manual))
        assertMinutes(0.0, manualData)
        assertEquals(ReviewReason.INVALID_MANUAL_SESSION, AttendanceEngine.derive(manualData).reviews.single().reason)
        val correction = Correction("zero", "session:in", at("09:00"), at("09:00"), at("18:00"))
        val data = input(listOf(enter("in", "09:00"), exit("out", "10:00")), corrections = listOf(correction))
        assertMinutes(55.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.any { it.reason == ReviewReason.INVALID_CORRECTION })
        assertEquals(at("10:00"), AttendanceEngine.derive(data).sessions.single().end)
    }

    @Test fun cleanSingleSessionSeparatesRawFromGrace() {
        val data = input(listOf(enter("e", "09:00"), exit("x", "15:00")))
        val result = AttendanceEngine.derive(data)
        assertMinutes(355.0, data)
        assertEquals(360.0, AttendanceEngine.observedMinutes(result.sessions.single(), data.now), 0.0)
        assertEquals(Confidence.HIGH, result.sessions.single().confidence)
    }
    @Test fun arrivalWalkingGraceCreditsOnlyTimeAfterItsEnd() {
        val entry = enter("entry", "09:00")
        val graceOffice = office.copy(entryGraceMinutes = 5, exitGraceMinutes = 30)
        listOf("09:00" to 0.0, "09:03" to 0.0, "09:05" to 0.0, "09:08" to 3.0).forEach { (exitTime, expected) ->
            val data = input(listOf(entry, exit("exit", exitTime)), now = at("10:00"), offices = listOf(graceOffice))
            val result = AttendanceEngine.derive(data)
            assertEquals(expected, result.intervals.sumOf { it.minutes }, 0.0)
            assertEquals(listOf(entry, exit("exit", exitTime)), data.events)
            if (expected > 0.0) {
                assertEquals(at("09:05"), result.intervals.single().start)
                assertEquals(at(exitTime), result.intervals.single().end)
            } else assertTrue(result.intervals.isEmpty())
        }
        val live = input(listOf(entry), now = at("09:03"), offices = listOf(graceOffice))
        assertMinutes(0.0, live)
        assertEquals(at("09:00"), AttendanceEngine.derive(live).sessions.single().start)
        assertMinutes(3.0, live.copy(now = at("09:08")))
        val noDelay = live.copy(offices = listOf(graceOffice.copy(entryGraceMinutes = 0)))
        assertMinutes(3.0, noDelay)
    }
    @Test fun splitLunchSubtractsEachArrivalGrace() {
        assertMinutes(350.0, input(listOf(enter("1", "09:15"), exit("2", "11:45"), enter("3", "13:30"), exit("4", "17:00"))))
    }
    @Test fun customGracePerOffice() {
        assertMinutes(350.0, input(listOf(enter("1", "09:00"), exit("2", "15:00")), offices = listOf(office.copy(entryGraceMinutes = 10, exitGraceMinutes = 15))))
    }
    @Test fun graceOverlapUnionDoesNotDoubleCount() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "14:00"), enter("3", "14:04"), exit("4", "17:00")))
        assertMinutes(470.0, data)
        val intervals = AttendanceEngine.derive(data).intervals
        assertEquals(2, intervals.size)
        assertEquals(at("14:04"), intervals.first().end)
        assertEquals(at("14:09"), intervals.last().start)
    }
    @Test fun shortGapAtThresholdIsReconciledWithProvenance() {
        val events = listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "12:20"), exit("4", "15:00"))
        val result = AttendanceEngine.derive(input(events, policy = Policy(shortGapMinutes = 20)))
        assertEquals(350.0, result.intervals.sumOf { it.minutes }, 0.0)
        assertEquals(2, result.intervals.size)
        assertTrue(result.intervals.first().reconciledGap)
        assertEquals(setOf("session:1", "session:3"), result.intervals.first().sessionIds)
        assertEquals(at("12:20"), result.intervals.first().end)
        assertEquals(at("12:25"), result.intervals.last().start)
        assertMinutes(330.0, input(events, policy = Policy(shortGapMinutes = 19)))
    }
    @Test fun zeroCreditVisitBlocksGapBridgeAcrossItsArrivalWindow() {
        val events = listOf(enter("a", "09:00"), exit("b", "09:10"),
            enter("c", "09:11"), exit("d", "09:12"),
            enter("e", "09:13"), exit("f", "09:20"))
        val data = input(events, policy = Policy(shortGapMinutes = 10))
        val result = AttendanceEngine.derive(data)
        assertEquals(3, result.sessions.size)
        assertEquals(events, data.events)
        assertEquals(7.0, result.intervals.sumOf { it.minutes }, 0.0)
        assertEquals(listOf(at("09:05") to at("09:10"), at("09:18") to at("09:20")),
            result.intervals.map { it.start to it.end })
        assertTrue(result.intervals.none { it.reconciledGap })
    }
    @Test fun repeatedEnterPreservesEarliestAndFlagsLowConfidence() {
        val data = input(listOf(enter("1", "09:00"), enter("2", "10:00"), exit("3", "15:00")))
        assertMinutes(355.0, data)
        val session = AttendanceEngine.derive(data).sessions.single()
        assertEquals(Confidence.LOW, session.confidence)
        assertTrue(ReviewReason.REPEATED_ENTER in session.reviewReasons)
        assertEquals(setOf("1", "2", "3"), session.sourceEventIds)
    }
    @Test fun firstGeofenceEnterAfterPresenceCorroboratesWithoutHidingLaterRepeatedEnters() {
        val presence = RawEvent("fix", "a", Transition.PRESENCE, at("09:00"))
        val followed = input(listOf(presence, enter("normal", "09:02"), exit("out", "10:00")))
        val session = AttendanceEngine.derive(followed).sessions.single()
        assertEquals(setOf("fix", "normal", "out"), session.sourceEventIds)
        assertFalse(ReviewReason.REPEATED_ENTER in session.reviewReasons)
        assertMinutes(55.0, followed)

        val repeated = followed.copy(events = followed.events.dropLast(1) + enter("repeat", "09:03") + exit("out", "10:00"))
        assertTrue(ReviewReason.REPEATED_ENTER in AttendanceEngine.derive(repeated).sessions.single().reviewReasons)
        val late = input(listOf(presence, enter("late", "09:30"), exit("out", "10:00")))
        assertTrue(ReviewReason.REPEATED_ENTER in AttendanceEngine.derive(late).sessions.single().reviewReasons)
    }
    @Test fun repeatedExitDoesNotInventAnotherSessionStart() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "15:00"), exit("3", "15:05")))
        assertMinutes(355.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.any { it.reason == ReviewReason.MISSING_ENTER })
    }
    @Test fun exitWithoutEnterOrInstallInsideOfficeIsUnknownNotInventedHistory() {
        val data = input(listOf(exit("1", "15:00")))
        assertMinutes(0.0, data)
        val session = AttendanceEngine.derive(data).sessions.single()
        assertNull(session.start)
        assertEquals(Confidence.LOW, session.confidence)
    }
    @Test fun openSessionStartsAfterArrivalGraceAndEndsAtNow() {
        val data = input(listOf(enter("1", "09:00")), now = at("10:00"))
        assertMinutes(55.0, data)
        assertEquals(at("10:00"), AttendanceEngine.derive(data).intervals.single().end)
        assertEquals(1, data.events.size)
    }
    @Test fun closedSessionStopsAtObservedExitWithoutExitGrace() {
        assertMinutes(55.0, input(listOf(enter("1", "09:00"), exit("2", "10:00")), now = at("10:01")))
    }
    @Test fun staleOpenSessionDoesNotAccrueAnUnboundedOvernightShift() {
        val data = input(listOf(enter("1", "09:00", date = day.minusDays(1))))
        assertMinutes(0.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.any { it.reason == ReviewReason.STALE_OPEN_SESSION })
    }
    @Test fun twoEligibleOfficesPoolTheirMinutes() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "13:00", "b"), exit("4", "16:00", "b")), offices = listOf(office, office.copy(id = "b")))
        assertMinutes(350.0, data)
    }
    @Test fun overlappingOfficesAndGraceAreGloballyUnioned() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "11:00", "b"), exit("4", "15:00", "b")), offices = listOf(office, office.copy(id = "b")))
        assertMinutes(355.0, data)
    }
    @Test fun shortTransferGapBetweenDifferentOfficesIsNotCredited() {
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "12:15", "b"), exit("4", "15:00", "b")), offices = listOf(office, office.copy(id = "b")))
        assertMinutes(335.0, data)
    }
    @Test fun disabledAndIneligibleOfficeRemainEvidenceButDoNotCredit() {
        val events = listOf(enter("1", "09:00"), exit("2", "15:00"))
        assertMinutes(0.0, input(events, offices = listOf(office.copy(enabled = false))))
        assertMinutes(0.0, input(events, offices = listOf(office.copy(countsTowardAttendance = false))))
        assertEquals(1, AttendanceEngine.derive(input(events, offices = listOf(office.copy(enabled = false)))).sessions.size)
    }
    @Test fun springForwardUsesRealElapsedTime() {
        val date = LocalDate.of(2026, 3, 8)
        assertMinutes(55.0, input(listOf(enter("1", "01:30", date = date), exit("2", "03:30", date = date)), now = at("12:00", date)))
    }
    @Test fun fallBackUsesRealElapsedTime() {
        val date = LocalDate.of(2026, 11, 1)
        assertMinutes(175.0, input(listOf(enter("1", "00:30", date = date), exit("2", "02:30", date = date)), now = at("12:00", date)))
    }
    @Test fun correctionsRetainRawEventsAndResolveReview() {
        val events = listOf(exit("exitOnly", "15:00"))
        val correction = Correction("c", "session:exitOnly", at("09:00"), at("14:00"), at("18:00"), "Restored missed enter")
        val data = input(events, corrections = listOf(correction))
        val result = AttendanceEngine.derive(data)
        assertMinutes(295.0, data)
        assertEquals(events, data.events)
        assertEquals(setOf("exitOnly"), result.sessions.single().sourceEventIds)
        assertEquals(Confidence.MANUAL, result.sessions.single().confidence)
        assertTrue(result.reviews.isEmpty())
    }
    @Test fun latestCorrectionWinsDeterministically() {
        val early = Correction("c1", "session:1", at("09:00"), at("14:00"), at("18:00"))
        val late = early.copy(id = "c2", end = at("15:00"), createdAt = at("19:00"))
        val data = input(listOf(enter("1", "09:00"), exit("2", "12:00")), corrections = listOf(late, early))
        assertMinutes(355.0, data)
        assertEquals(AttendanceEngine.derive(data), AttendanceEngine.derive(data.copy(corrections = listOf(early, late))))
    }
    @Test fun invalidAndOrphanCorrectionsAreReviewable() {
        val bad = Correction("bad", "session:1", at("15:00"), at("09:00"), at("18:00"))
        val orphan = bad.copy(id = "orphan", sessionId = "missing")
        val data = input(listOf(enter("1", "09:00"), exit("2", "15:00")), corrections = listOf(bad, orphan))
        assertMinutes(355.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.map { it.reason }.containsAll(listOf(ReviewReason.INVALID_CORRECTION, ReviewReason.ORPHAN_CORRECTION)))
    }
    @Test fun duplicatesDoNotIncreaseTimeAndKeepAllSourceIds() {
        val events = listOf(enter("1", "09:00"), exit("2", "15:00"))
        val repeated = input(events + events + enter("replayed", "09:00"))
        assertMinutes(355.0, repeated)
        assertEquals(setOf("1", "2", "replayed"), AttendanceEngine.derive(repeated).sessions.single().sourceEventIds)
        assertEquals(Confidence.MEDIUM, AttendanceEngine.derive(repeated).sessions.single().confidence)
    }
    @Test fun unorderedInputProducesIdenticalOutput() {
        val events = listOf(enter("1", "09:00"), exit("2", "12:00"), enter("3", "13:00"), exit("4", "15:00"))
        assertEquals(AttendanceEngine.derive(input(events)), AttendanceEngine.derive(input(events.reversed())))
    }
    @Test fun futureUnknownOfficeAndConflictingIdsNeverBecomeCredit() {
        val data = input(listOf(enter("future", "21:00"), enter("unknown", "09:00", "missing"), enter("conflict", "09:00"), exit("conflict", "15:00")))
        assertMinutes(0.0, data)
        assertEquals(setOf(ReviewReason.FUTURE_EVENT, ReviewReason.UNKNOWN_OFFICE, ReviewReason.CONFLICTING_EVENT_ID), AttendanceEngine.derive(data).reviews.map { it.reason }.toSet())
    }
    @Test fun unionInvariantsAcrossDeterministicSyntheticIntervals() {
        val random = java.util.Random(42)
        repeat(100) {
            val intervals = (0..30).map { index ->
                val start = at("00:00").plusSeconds(random.nextInt(86400).toLong())
                CreditedInterval(start, start.plusSeconds(random.nextInt(10000).toLong()), setOf(index.toString()))
            }
            val united = AttendanceEngine.union(intervals)
            assertTrue(united.all { it.minutes >= 0.0 })
            assertTrue(united.sumOf { it.minutes } <= intervals.sumOf { it.minutes } + 0.00001)
            assertEquals(united, AttendanceEngine.union(intervals + intervals))
            assertEquals(united, AttendanceEngine.union(intervals.reversed()))
            assertTrue(united.zipWithNext().all { (a, b) -> a.end < b.start })
        }
    }
    @Test fun overlappingManualIntervalCannotConsumeAutomaticExit() {
        val events = listOf(enter("in", "09:00"), exit("out", "17:00"))
        val manual = ManualSession("lunch", "a", at("12:00"), at("13:00"), at("18:00"), "User observation")
        val data = input(events).copy(manualSessions = listOf(manual))
        val result = AttendanceEngine.derive(data)
        assertMinutes(475.0, data)
        assertEquals(2, result.sessions.size)
        val automatic = result.sessions.single { it.id == "session:in" }
        assertEquals(at("17:00"), automatic.end)
        assertEquals(setOf("in", "out"), automatic.sourceEventIds)
        assertNull(automatic.manualSessionId)
        val user = result.sessions.single { it.id == "manual:lunch" }
        assertEquals("lunch", user.manualSessionId)
        assertEquals(Confidence.MANUAL, user.confidence)
        assertTrue(user.sourceEventIds.isEmpty())
        assertTrue(result.reviews.isEmpty())
        assertEquals(events, data.events)
    }
    @Test fun manualCorrectionRetainsStableSourceAndDoesNotTouchAutomaticSession() {
        val manual = ManualSession("m", "a", at("12:00"), at("13:00"), at("18:00"))
        val correction = Correction("c", "manual:m", at("08:00"), at("18:00"), at("19:00"))
        val data = input(listOf(enter("1", "09:00"), exit("2", "17:00")), corrections = listOf(correction))
            .copy(manualSessions = listOf(manual))
        val result = AttendanceEngine.derive(data)
        assertMinutes(595.0, data)
        assertEquals(at("17:00"), result.sessions.single { it.id == "session:1" }.end)
        val edited = result.sessions.single { it.id == "manual:m" }
        assertEquals("m", edited.manualSessionId)
        assertEquals("c", edited.correctionId)
        assertEquals(listOf(manual), data.manualSessions)
        assertTrue(result.reviews.isEmpty())
    }
    @Test fun multipleOverlappingManualIntervalsUnionWithoutDoubleCount() {
        val manuals = listOf(
            ManualSession("a", "a", at("09:00"), at("13:00"), at("18:00")),
            ManualSession("b", "a", at("12:00"), at("15:00"), at("18:00")),
            ManualSession("c", "a", at("14:00"), at("17:00"), at("18:00")),
        )
        val data = input(emptyList()).copy(manualSessions = manuals)
        assertMinutes(475.0, data)
        val result = AttendanceEngine.derive(data)
        assertEquals(setOf("manual:a", "manual:b", "manual:c"), result.intervals.single().sessionIds)
        assertEquals(result, AttendanceEngine.derive(data.copy(manualSessions = manuals.reversed() + manuals)))
    }
    @Test fun openManualSessionIsIndependentAndNeverReceivesFutureGrace() {
        val manual = ManualSession("m", "a", at("09:00"), null, at("09:30"))
        val data = input(emptyList(), now = at("10:00")).copy(manualSessions = listOf(manual))
        assertMinutes(55.0, data)
        val result = AttendanceEngine.derive(data)
        assertEquals(input(emptyList(), now = at("10:00")).now, result.intervals.single().end)
        assertEquals(Confidence.MANUAL, result.sessions.single().confidence)
        assertTrue(data.events.isEmpty())
    }
    @Test fun overlappingClosedManualDoesNotCloseAutomaticOpenSession() {
        val data = input(listOf(enter("1", "09:00")), now = at("14:00")).copy(
            manualSessions = listOf(ManualSession("m", "a", at("12:00"), at("13:00"), at("13:30"))))
        val result = AttendanceEngine.derive(data)
        assertMinutes(295.0, data)
        assertTrue(result.sessions.single { it.id == "session:1" }.isOpen)
    }
    @Test fun staleManualOpenRequiresReviewInsteadOfUnboundedAccrual() {
        val manual = ManualSession("m", "a", at("09:00", day.minusDays(1)), null, at("09:30", day.minusDays(1)))
        val data = input(emptyList()).copy(manualSessions = listOf(manual))
        assertMinutes(0.0, data)
        assertTrue(AttendanceEngine.derive(data).reviews.any { it.reason == ReviewReason.STALE_OPEN_SESSION })
    }
    @Test fun invalidAndConflictingManualEvidenceIsFlaggedWithoutCredit() {
        val original = ManualSession("m", "a", at("09:00"), at("12:00"), at("18:00"))
        val conflict = original.copy(end = at("13:00"))
        val bad = original.copy(id = "bad", start = at("15:00"))
        val future = original.copy(id = "future", end = at("21:00"))
        val data = input(emptyList()).copy(manualSessions = listOf(original, conflict, bad, future))
        assertMinutes(0.0, data)
        val reasons = AttendanceEngine.derive(data).reviews.map { it.reason }.toSet()
        assertEquals(setOf(ReviewReason.CONFLICTING_MANUAL_SESSION_ID, ReviewReason.INVALID_MANUAL_SESSION), reasons)
    }
}
