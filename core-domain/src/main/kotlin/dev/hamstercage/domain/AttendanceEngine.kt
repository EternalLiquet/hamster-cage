package dev.hamstercage.domain

import java.time.Duration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

/** Pure deterministic derivation. Inputs, raw observations and corrections are never mutated. */
object AttendanceEngine {
    private data class Observation(val event: RawEvent, val ids: Set<String>, val duplicate: Boolean)
    /** A prompt opposite signal is boundary jitter, not a second observed visit. */
    private val boundaryBounceWindow = Duration.ofSeconds(60)

    fun derive(input: AttendanceInput): AttendanceResult {
        require(input.now.isStorageTime()) { "Current time must fit the persisted epoch-millisecond representation" }
        val reviews = mutableListOf<ReviewItem>()
        val sessions = mutableListOf<Session>()
        val offices = input.offices.associateBy { it.id }
        require(offices.size == input.offices.size) { "Office IDs must be unique" }
        val conflictingIds = input.events.groupBy { it.id }.filterValues { it.distinct().size > 1 }.keys
        conflictingIds.sorted().forEach { reviews += ReviewItem(ReviewReason.CONFLICTING_EVENT_ID, setOf(it)) }
        val usable = input.events.filter { event ->
            when {
                event.id in conflictingIds -> false
                event.id.isBlank() || !event.at.isStorageTime() -> { reviews += ReviewItem(ReviewReason.INVALID_EVENT, setOf(event.id)); false }
                event.officeId !in offices -> { reviews += ReviewItem(ReviewReason.UNKNOWN_OFFICE, setOf(event.id)); false }
                event.at > input.now -> { reviews += ReviewItem(ReviewReason.FUTURE_EVENT, setOf(event.id)); false }
                else -> true
            }
        }
        usable.groupBy { it.officeId }.toSortedMap().forEach { (officeId, events) ->
            val observations = events.groupBy { Pair(it.at, it.transition) }.map { (_, copies) ->
                Observation(copies.minBy { it.id }, copies.map { it.id }.toSet(), copies.size > 1)
            }.sortedWith(compareBy<Observation> { it.event.at }.thenBy { it.event.transition }.thenBy { it.event.id })
            var open: Observation? = null
            var pendingExit: Observation? = null
            val ids = linkedSetOf<String>()
            val flags = linkedSetOf<ReviewReason>()
            fun addSession(exit: Observation?) {
                val enter = open
                val sourceIds = ids.toSet()
                val sessionId = "session:${enter?.event?.id ?: exit!!.event.id}"
                val reasons = flags.toSet() + if (enter != null && exit != null && enter.event.at == exit.event.at)
                    setOf(ReviewReason.ZERO_LENGTH_SESSION) else emptySet()
                sessions += Session(sessionId, officeId, enter?.event?.at, exit?.event?.at, sourceIds,
                    when {
                        reasons.any { it != ReviewReason.DUPLICATE_EVENT && it != ReviewReason.OPEN_SESSION } -> Confidence.LOW
                        ReviewReason.DUPLICATE_EVENT in reasons -> Confidence.MEDIUM
                        enter != null && exit == null -> Confidence.MEDIUM
                        else -> Confidence.HIGH
                    }, reasons)
                reasons.forEach { reviews += ReviewItem(it, sourceIds, sessionId) }
                open = null
                ids.clear()
                flags.clear()
            }
            observations.groupBy { it.event.at }.values.forEach { simultaneous ->
                // Close a preceding visit before starting another at a simultaneous boundary.
                // Without a preceding visit, ENTER+EXIT remains a zero-length observation.
                val ordered = if (open != null) simultaneous.sortedWith(compareBy<Observation> {
                    when (it.event.transition) { Transition.EXIT, Transition.ABSENCE -> 0; Transition.PRESENCE -> 1; Transition.ENTER -> 2 }
                }.thenBy { it.event.id }) else simultaneous.sortedWith(compareBy<Observation> {
                    when (it.event.transition) { Transition.PRESENCE -> 0; Transition.ENTER -> 1; Transition.EXIT, Transition.ABSENCE -> 2 }
                }.thenBy { it.event.id })
                ordered.forEach { observation ->
                    val pending = pendingExit
                    if (pending != null) {
                        val elapsed = Duration.between(pending.event.at, observation.event.at)
                        val otherOfficeBetween = usable.any { it.officeId != officeId &&
                            it.at >= pending.event.at && it.at <= observation.event.at }
                        if (observation.event.transition in setOf(Transition.ENTER, Transition.PRESENCE) &&
                            observation.ids.none { it in input.recoveryPresenceIds } &&
                            !otherOfficeBetween && !elapsed.isNegative && elapsed <= boundaryBounceWindow) {
                            // Both immutable observations stay attached to the original
                            // session. Neither a second grace window nor a departure is
                            // derived from this near-immediate opposite pair.
                            ids += observation.ids
                            if (observation.duplicate) flags += ReviewReason.DUPLICATE_EVENT
                            flags.remove(ReviewReason.TRANSIENT_BOUNDARY)
                            pendingExit = null
                            return@forEach
                        }
                        pendingExit = null
                        addSession(pending)
                    }
                    ids += observation.ids
                    if (observation.duplicate) flags += ReviewReason.DUPLICATE_EVENT
                    when (observation.event.transition) {
                        Transition.ENTER -> when {
                            open == null -> open = observation
                            // Same-office arrival observations corroborate an already open
                            // visit. The first opening timestamp remains the grace anchor.
                            else -> Unit
                        }
                        Transition.EXIT -> {
                            if (open == null) {
                                flags += ReviewReason.MISSING_ENTER
                                addSession(observation)
                            } else {
                                if (Duration.between(open!!.event.at, observation.event.at) <= boundaryBounceWindow)
                                    flags += ReviewReason.TRANSIENT_BOUNDARY
                                pendingExit = observation
                            }
                        }
                        Transition.ABSENCE -> {
                            // A check proves only that the user is outside now. The exit
                            // could have happened at any time since the last inside fact.
                            if (open != null) {
                                flags += ReviewReason.UNCONFIRMED_GAP
                                addSession(observation)
                            } else ids.clear()
                        }
                        Transition.PRESENCE -> {
                            if (open != null) {
                                if (observation.ids.any { it in input.recoveryPresenceIds }) {
                                    // #88 appends this foreground fact only when its
                                    // recovery gate found an unsafe earlier opening.
                                    ids.removeAll(observation.ids)
                                    flags += ReviewReason.UNCONFIRMED_GAP
                                    addSession(observation)
                                    ids += observation.ids
                                    open = observation
                                }
                                // Routine current-state checks otherwise corroborate the
                                // opening without restarting arrival grace.
                            } else {
                                open = observation
                            }
                        }
                    }
                }
            }
            pendingExit?.let { addSession(it) }
            open?.let {
                flags += ReviewReason.OPEN_SESSION
                if (Duration.between(it.event.at, input.now) > Duration.ofHours(input.policy.maxOpenSessionHours.toLong()))
                    flags += ReviewReason.STALE_OPEN_SESSION
                addSession(null)
            }
        }

        // Manual intervals must never enter geofence reconstruction: an overlapping user interval
        // could otherwise consume a device EXIT and shorten the actual observed session.
        input.manualSessions.groupBy { it.id }.toSortedMap().forEach { (id, copies) ->
            val sessionId = "manual:$id"
            val manual = copies.first()
            when {
                copies.distinct().size > 1 -> reviews += ReviewItem(ReviewReason.CONFLICTING_MANUAL_SESSION_ID, sessionId = sessionId)
                manual.officeId !in offices -> reviews += ReviewItem(ReviewReason.UNKNOWN_OFFICE, sessionId = sessionId)
                id.isBlank() || !manual.createdAt.isStorageTime() || !manual.start.isStorageTime() ||
                    (manual.end != null && !manual.end.isStorageTime()) || manual.createdAt > input.now || manual.start > input.now ||
                    (manual.end != null && (manual.end <= manual.start || manual.end > input.now)) ->
                    reviews += ReviewItem(ReviewReason.INVALID_MANUAL_SESSION, sessionId = sessionId)
                else -> {
                    val flags = if (manual.end == null) {
                        if (Duration.between(manual.start, input.now) > Duration.ofHours(input.policy.maxOpenSessionHours.toLong()))
                            setOf(ReviewReason.OPEN_SESSION, ReviewReason.STALE_OPEN_SESSION)
                        else setOf(ReviewReason.OPEN_SESSION)
                    } else emptySet()
                    sessions += Session(sessionId, manual.officeId, manual.start, manual.end, emptySet(),
                        Confidence.MANUAL, flags, manualSessionId = id)
                    flags.forEach { reviews += ReviewItem(it, sessionId = sessionId) }
                }
            }
        }

        val conflictingCorrectionIds = input.corrections.groupBy { it.id }.filterValues { it.distinct().size > 1 }.keys
        input.corrections.filter { it.id in conflictingCorrectionIds }.forEach {
            reviews += ReviewItem(ReviewReason.INVALID_CORRECTION, sessionId = it.sessionId)
        }
        val corrections = input.corrections.filter { it.id !in conflictingCorrectionIds }.groupBy { it.sessionId }
        val sessionIds = sessions.flatMap { it.correctionTargetIds }.toSet()
        corrections.keys.filter { it !in sessionIds }.sorted().forEach {
            reviews += ReviewItem(ReviewReason.ORPHAN_CORRECTION, sessionId = it)
        }
        val effective = sessions.map { original ->
            val candidates = original.correctionTargetIds.flatMap { corrections[it].orEmpty() }
            val (valid, invalid) = candidates.partition { correction ->
                correction.id.isNotBlank() && correction.appendSequence >= 0 && correction.createdAt.isStorageTime() && correction.start.isStorageTime() &&
                    correction.createdAt <= input.now && correction.start <= input.now &&
                    (correction.end == null || (correction.end.isStorageTime() && correction.end <= input.now &&
                        (correction.revertToOriginal || correction.end > correction.start)))
            }
            if (invalid.isNotEmpty())
                reviews += ReviewItem(ReviewReason.INVALID_CORRECTION, original.sourceEventIds, original.id)
            val correction = valid.maxWithOrNull(compareBy<Correction> { it.appendSequence }.thenBy { it.createdAt }.thenBy { it.id })
            if (correction == null) {
                if (invalid.isEmpty()) original else original.copy(confidence = Confidence.LOW,
                    reviewReasons = original.reviewReasons + ReviewReason.INVALID_CORRECTION)
            } else if (correction.revertToOriginal) {
                // A marker restores reconstruction from retained facts, including missing
                // boundaries and original review reasons. It never fabricates a raw event.
                original.copy(correctionId = correction.id, correctionReverted = true,
                    confidence = if (invalid.isEmpty()) original.confidence else Confidence.LOW,
                    reviewReasons = original.reviewReasons + if (invalid.isEmpty()) emptySet() else setOf(ReviewReason.INVALID_CORRECTION))
            } else {
                val openFlags = if (correction.end == null) {
                    if (Duration.between(correction.start, input.now) > Duration.ofHours(input.policy.maxOpenSessionHours.toLong()))
                        setOf(ReviewReason.OPEN_SESSION, ReviewReason.STALE_OPEN_SESSION)
                    else setOf(ReviewReason.OPEN_SESSION)
                } else emptySet()
                val flags = openFlags + if (invalid.isNotEmpty()) setOf(ReviewReason.INVALID_CORRECTION) else emptySet()
                // The original evidence remains in input/events; old warnings are resolved by explicit correction.
                reviews.removeAll { it.sessionId == original.id && it.reason != ReviewReason.INVALID_CORRECTION }
                flags.forEach { reviews += ReviewItem(it, original.sourceEventIds, original.id) }
                original.copy(start = correction.start, end = correction.end, confidence = Confidence.MANUAL,
                    reviewReasons = flags, correctionId = correction.id)
            }
        }.sortedWith(compareBy<Session> { it.start ?: it.end }.thenBy { it.id })

        val credited = effective.mapNotNull { session ->
            val office = offices.getValue(session.officeId)
            val start = session.start
            if (start == null || !office.enabled || !office.countsTowardAttendance ||
                ReviewReason.STALE_OPEN_SESSION in session.reviewReasons || ReviewReason.ZERO_LENGTH_SESSION in session.reviewReasons ||
                ReviewReason.UNCONFIRMED_GAP in session.reviewReasons || ReviewReason.TRANSIENT_BOUNDARY in session.reviewReasons) null
            else {
                val end = minOf(session.end ?: input.now, input.now)
                val creditStart = start.plusSeconds(office.entryGraceMinutes * 60L)
                if (end <= creditStart) null else CreditedInterval(creditStart, end, setOf(session.id))
            }
        }
        // Reconcile only the observed gap between visits at the same office. The next
        // visit's arrival window remains uncredited even when that gap is short.
        val creditBySession = credited.associateBy { it.sessionIds.single() }
        val reconciled = effective.groupBy { it.officeId }.values.flatMap { officeSessions ->
                val officeIntervals = officeSessions.mapNotNull { creditBySession[it.id] }
                // Include visits that earned no credit in adjacency. Reconcile only
                // between two positive-credit visits; a brief uncredited visit blocks
                // both neighboring gaps and cannot be skipped as a continuity bridge.
                val ordered = officeSessions.sortedWith(compareBy<Session> { it.start ?: it.end }.thenBy { it.id })
                val gapCredits = ordered.zipWithNext().mapNotNull { (before, after) ->
                    val previousCredit = creditBySession[before.id]
                    val nextCredit = creditBySession[after.id]
                    val nextEntry = after.start
                    val contradictoryGap = nextEntry != null && before.end != null &&
                        (usable.any { it.officeId != before.officeId && it.at >= before.end && it.at <= nextEntry } ||
                            after.sourceEventIds.any { it in input.recoveryPresenceIds })
                    if (previousCredit == null || nextCredit == null || nextEntry == null ||
                        contradictoryGap || previousCredit.end >= nextEntry || Duration.between(previousCredit.end, nextEntry) >
                        Duration.ofMinutes(input.policy.shortGapMinutes.toLong())) null
                    else CreditedInterval(previousCredit.end, nextEntry, setOf(before.id, after.id), reconciledGap = true)
                }
                union(officeIntervals + gapCredits)
            }
        return AttendanceResult(effective, union(reconciled), reviews.distinct().sortedWith(
            compareBy<ReviewItem> { it.sessionId ?: "" }.thenBy { it.reason.name }.thenBy { it.sourceEventIds.sorted().joinToString() }))
    }

    /** Plain union never adds time. Reconciled observed gaps arrive as explicit intervals. */
    fun union(intervals: List<CreditedInterval>): List<CreditedInterval> {
        val result = mutableListOf<CreditedInterval>()
        intervals.filter { it.end > it.start }.sortedWith(compareBy<CreditedInterval> { it.start }.thenBy { it.end }).forEach { interval ->
            val previous = result.lastOrNull()
            if (previous != null && interval.start <= previous.end) {
                result[result.lastIndex] = previous.copy(end = maxOf(previous.end, interval.end),
                    sessionIds = previous.sessionIds + interval.sessionIds,
                    reconciledGap = previous.reconciledGap || interval.reconciledGap)
            } else result += interval
        }
        return result
    }

    fun observedMinutes(session: Session, now: Instant): Double = session.start?.let {
        max(0.0, Duration.between(it, minOf(session.end ?: now, now)).elapsedMinutes())
    } ?: 0.0

    fun observedDailyMinutes(input: AttendanceInput, date: LocalDate): Double =
        observedDailyMinutes(input, listOf(date)).getValue(date)

    fun observedDailyMinutes(input: AttendanceInput, dates: List<LocalDate>): Map<LocalDate, Double> {
        val raw = input.copy(corrections = emptyList(), manualSessions = emptyList(),
            offices = input.offices.map { it.copy(entryGraceMinutes = 0, exitGraceMinutes = 0) },
            policy = input.policy.copy(shortGapMinutes = 0))
        val result = derive(raw)
        return dates.associateWith { daily(raw, result, it).creditedMinutes }
    }

    fun daily(input: AttendanceInput, result: AttendanceResult, date: LocalDate): PeriodSummary =
        period(input, result, date, date)

    fun summary(input: AttendanceInput, result: AttendanceResult, target: TargetWindow): PeriodSummary {
        val (start, end) = window(input, target)
        return period(input, result, start, end, includeFutureRequirements = target == TargetWindow.FULL_WEEK)
    }

    /** A display baseline cannot turn an orphan observation or a pre-install day into owed time.
     * Coverage ledger days are continuous except marked outages; a valid session supplies only
     * its own policy-local dates when continuous coverage has not been established.
     */
    fun reportingCoverage(input: AttendanceInput, result: AttendanceResult,
                          startDate: LocalDate, endDate: LocalDate): ReportingCoverage {
        val span = ChronoUnit.DAYS.between(startDate, endDate)
        require(span in 0..36600 && endDate < LocalDate.MAX) { "Invalid or oversized calendar period" }
        val today = input.now.atZone(input.policy.zoneId).toLocalDate()
        val eligible = input.offices.filter { it.enabled && it.countsTowardAttendance }.map { it.id }.toSet()
        val benign = setOf(ReviewReason.OPEN_SESSION, ReviewReason.DUPLICATE_EVENT)
        val reliable = result.sessions.filter { it.officeId in eligible && it.start != null &&
            it.reviewReasons.all(benign::contains) && (it.end == null || it.end > it.start) }
        val sessionStart = reliable.minOfOrNull { it.start!!.atZone(input.policy.zoneId).toLocalDate() }
        // Retained raw ENTER proves when capture began even if a later correction makes
        // the effective session MANUAL or moves its bounds. Manual-only intervals and
        // corrected orphan EXITs have no ENTER and remain isolated backfill islands.
        val enterById = input.events.filter { it.transition == Transition.ENTER }.associateBy { it.id }
        val observedStart = reliable.flatMap { session ->
            session.sourceEventIds.mapNotNull { id -> enterById[id]?.takeIf { it.officeId == session.officeId } }
        }.minOfOrNull { it.at.atZone(input.policy.zoneId).toLocalDate() }
        val continuousStart = listOfNotNull(input.historyStartDate, observedStart).minOrNull()
        val ledgerStart = input.historyStartDate?.let { start ->
            var candidate = start
            while (candidate <= today && candidate in input.unknownDates) candidate = candidate.plusDays(1)
            candidate.takeIf { it <= today }
        }
        val first = listOfNotNull(sessionStart, ledgerStart).minOrNull()
        val dates = (0..span.toInt()).map { startDate.plusDays(it.toLong()) }.filter { it <= today }
        val sessionDates = dates.filterTo(mutableSetOf()) { date ->
            val dayStart = date.atStartOfDay(input.policy.zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
            reliable.any { it.start!! < dayEnd && (it.end ?: input.now) > dayStart }
        }
        val correctedDates = dates.filterTo(mutableSetOf()) { date ->
            val dayStart = date.atStartOfDay(input.policy.zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
            reliable.any { it.confidence == Confidence.MANUAL && it.start!! < dayEnd &&
                (it.end ?: input.now) > dayStart }
        }
        val covered = dates.filterTo(mutableSetOf()) { date ->
            date in correctedDates || (date !in input.unknownDates &&
                ((input.historyStartDate != null && date >= input.historyStartDate) || date in sessionDates))
        }
        // An isolated manual backfill is a covered island, not the start of continuous
        // capture. Dates between it and the coverage ledger are still pretracking.
        val before = dates.filterTo(mutableSetOf()) { date ->
            date !in covered && (continuousStart == null || date < continuousStart)
        }
        val after = dates.filterTo(mutableSetOf()) { it !in before && it !in covered }
        val expected = covered.count(input.policy::isExpected)
        // Pair displayed credit with the same covered dates as the displayed requirement.
        // Unknown-date intervals may still exist in the full summary for review, but cannot
        // silently inflate progress against a covered-day target.
        val coveredCredit = covered.sumOf { daily(input, result, it).creditedMinutes }
        return ReportingCoverage(first, covered, before, after, expected,
            expected * input.policy.targetMinutesPerDay, coveredCredit)
    }

    fun period(input: AttendanceInput, result: AttendanceResult, startDate: LocalDate, endDate: LocalDate,
               includeFutureRequirements: Boolean = false): PeriodSummary {
        val span = ChronoUnit.DAYS.between(startDate, endDate)
        require(span in 0..36600 && endDate < LocalDate.MAX) { "Invalid or oversized calendar period" }
        require(input.now.isStorageTime()) { "Current time must fit persisted epoch milliseconds" }
        val today = input.now.atZone(input.policy.zoneId).toLocalDate()
        val dates = (0..span.toInt()).map { startDate.plusDays(it.toLong()) }
        val expected = dates.filter { input.policy.isExpected(it) && (includeFutureRequirements || it <= today) }
        fun isUnknown(date: LocalDate) = input.historyStartDate == null || date < input.historyStartDate || date in input.unknownDates
        val future = expected.count { it > today }
        val unknownExpected = expected.count { it <= today && isUnknown(it) }
        // Weekends and exclusions can still contain attendance; missing coverage there is unknown too.
        val unknownCalendar = dates.count { it <= today && isUnknown(it) }
        val start = startDate.atStartOfDay(input.policy.zoneId).toInstant()
        val end = minOf(endDate.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant(), input.now)
        val clipped = if (end <= start) emptyList() else result.intervals.mapNotNull { interval ->
            val clippedStart = maxOf(start, interval.start)
            val clippedEnd = minOf(end, interval.end)
            if (clippedEnd <= clippedStart) null else interval.copy(start = clippedStart, end = clippedEnd)
        }
        val credited = union(clipped).sumOf { it.minutes }
        val required = expected.size * input.policy.targetMinutesPerDay
        return PeriodSummary(startDate, endDate, credited, expected.size, required, credited - required,
            if (expected.isEmpty()) null else credited / expected.size,
            expected.size - unknownExpected - future, unknownExpected, future, unknownCalendar)
    }

    /** Estimate against the selected window as it stands now; never mutate historical credit. */
    fun departure(input: AttendanceInput, result: AttendanceResult, target: TargetWindow): DepartureEstimate {
        val summary = summary(input, result, target)
        val remaining = max(0.0, summary.requiredMinutes - summary.creditedMinutes)
        fun outcome(status: DepartureStatus, reasons: Set<ReviewReason> = emptySet()) =
            DepartureEstimate(target, status, remaining, reviewReasons = reasons)
        // Today's projection is anchored to observed credit through now and the active
        // session. Unknown coverage makes it provisional, but prior history cannot
        // contribute a deficit to a single-day target.
        if (target != TargetWindow.TODAY && !summary.hasCompleteHistory)
            return outcome(DepartureStatus.INCOMPLETE_HISTORY)
        val windowStart = summary.startDate.atStartOfDay(input.policy.zoneId).toInstant()
        val windowEnd = summary.endDate.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
        val eligibleOffices = input.offices.filter { it.enabled && it.countsTowardAttendance }.associateBy { it.id }
        val relevant = result.sessions.filter { session ->
            val office = eligibleOffices[session.officeId]
            office != null && (session.end == null || session.end >= windowStart) &&
                (session.start == null || session.start < windowEnd)
        }
        val current = relevant.filter { it.isOpen }
        val benign = setOf(ReviewReason.OPEN_SESSION, ReviewReason.DUPLICATE_EVENT)
        // A fresh same-office PRESENCE can leave an older, uncredited segment for
        // review. It cannot contribute to today's projected credit, so that one
        // resolved boundary does not make the new open visit unsafe to project.
        val repairedGapIds = if (target == TargetWindow.TODAY && current.size == 1 &&
            input.events.any { it.id in current.single().sourceEventIds && it.transition == Transition.PRESENCE }) {
            relevant.filter { it.officeId == current.single().officeId && it.end == current.single().start &&
                ReviewReason.UNCONFIRMED_GAP in it.reviewReasons }.map { it.id }.toSet()
        } else emptySet()
        fun blocks(reason: ReviewReason, sessionId: String?) = reason !in benign &&
            !(reason == ReviewReason.UNCONFIRMED_GAP && sessionId in repairedGapIds)
        val relevantIds = relevant.map { it.id }.toSet()
        val allSessionIds = result.sessions.map { it.id }.toSet()
        // Ambiguity wins even when the provisional credit exceeds the target. A bad clock,
        // unresolved boundary or competing open offices must never produce a confident exit.
        if (current.size > 1) return outcome(DepartureStatus.OVERLAPPING_SESSIONS)
        val blockingReasons = relevant.flatMap { session -> session.reviewReasons.filter { blocks(it, session.id) } }.toSet() +
            result.reviews.filter { review -> blocks(review.reason, review.sessionId) &&
                (if (target == TargetWindow.TODAY)
                    reviewAffectsDay(input, review, allSessionIds, relevantIds, windowStart, windowEnd)
                else review.sessionId !in allSessionIds || review.sessionId in relevantIds)
            }.map { it.reason }
        if (blockingReasons.isNotEmpty()) return outcome(DepartureStatus.NEEDS_REVIEW, blockingReasons)
        if (remaining == 0.0) return outcome(DepartureStatus.TARGET_SATISFIED)
        if (current.isEmpty()) return outcome(DepartureStatus.NOT_IN_OFFICE)
        if (input.now < windowStart || input.now >= windowEnd) return outcome(DepartureStatus.OUTSIDE_WINDOW)
        val session = current.single()
        val office = eligibleOffices.getValue(session.officeId)
        val arrivalCreditStart = session.start!!.plusSeconds(office.entryGraceMinutes * 60L)
        val targetAt = maxOf(input.now, arrivalCreditStart).plusMillis(kotlin.math.ceil(remaining * 60000.0).toLong())
        val exitAt = maxOf(input.now, targetAt.minusSeconds(office.exitGraceMinutes * 60L))
        // A continuing visit earns one minute per minute, regardless of overlapping offices.
        // Do not roll today's/rolling window forward to make an otherwise unreachable target fit.
        if (targetAt > windowEnd || exitAt > session.start.plusSeconds(input.policy.maxOpenSessionHours * 3600L))
            return outcome(DepartureStatus.UNREACHABLE_IN_WINDOW)
        return DepartureEstimate(target, DepartureStatus.ESTIMATED, remaining, targetAt, exitAt)
    }

    /** A past orphan or malformed fact cannot make an otherwise bounded Today session ambiguous. */
    private fun reviewAffectsDay(input: AttendanceInput, review: ReviewItem, allSessionIds: Set<String>,
                                 relevantIds: Set<String>, start: Instant, end: Instant): Boolean {
        val id = review.sessionId
        if (id in allSessionIds) return id in relevantIds
        val events = input.events.filter { it.id in review.sourceEventIds }
        if (events.isNotEmpty()) return events.any { it.at >= start && it.at < end }
        val corrections = input.corrections.filter { it.sessionId == id }
        if (corrections.isNotEmpty()) return corrections.any { it.start < end && (it.end == null || it.end >= start) }
        val manual = input.manualSessions.filter { "manual:${it.id}" == id }
        if (manual.isNotEmpty()) return manual.any { it.start < end && (it.end == null || it.end >= start) }
        // If the source is genuinely unlocatable, suppress the projection rather than guess.
        return true
    }

    private fun window(input: AttendanceInput, target: TargetWindow): Pair<LocalDate, LocalDate> {
        val today = input.now.atZone(input.policy.zoneId).toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (target) {
            TargetWindow.TODAY -> today to today
            TargetWindow.WEEK_TO_DATE -> monday to today
            TargetWindow.FULL_WEEK -> monday to monday.plusDays(6)
            TargetWindow.ROLLING_30 -> today.minusDays(29) to today
            TargetWindow.ROLLING_90 -> today.minusDays(89) to today
        }
    }

    private fun Instant.isStorageTime(): Boolean = try { toEpochMilli(); true } catch (_: ArithmeticException) { false }
}
