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
                val ordered = if (open != null) simultaneous.sortedByDescending { it.event.transition } else simultaneous
                ordered.forEach { observation ->
                    ids += observation.ids
                    if (observation.duplicate) flags += ReviewReason.DUPLICATE_EVENT
                    when (observation.event.transition) {
                        Transition.ENTER -> if (open == null) open = observation else flags += ReviewReason.REPEATED_ENTER
                        Transition.EXIT -> {
                            if (open == null) flags += ReviewReason.MISSING_ENTER
                            addSession(observation)
                        }
                    }
                }
            }
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
                correction.id.isNotBlank() && correction.createdAt.isStorageTime() && correction.start.isStorageTime() &&
                    correction.createdAt <= input.now && correction.start <= input.now &&
                    (correction.end == null || (correction.end.isStorageTime() && correction.end > correction.start && correction.end <= input.now))
            }
            if (invalid.isNotEmpty())
                reviews += ReviewItem(ReviewReason.INVALID_CORRECTION, original.sourceEventIds, original.id)
            val correction = valid.maxWithOrNull(compareBy<Correction> { it.createdAt }.thenBy { it.id })
            if (correction == null) {
                if (invalid.isEmpty()) original else original.copy(confidence = Confidence.LOW,
                    reviewReasons = original.reviewReasons + ReviewReason.INVALID_CORRECTION)
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
                ReviewReason.STALE_OPEN_SESSION in session.reviewReasons || ReviewReason.ZERO_LENGTH_SESSION in session.reviewReasons) null
            else {
                val end = session.end?.plusSeconds(office.exitGraceMinutes * 60L)?.coerceAtMost(input.now) ?: input.now
                CreditedInterval(start.minusSeconds(office.entryGraceMinutes * 60L), end, setOf(session.id))
            }
        }
        // Reconcile wobble only inside the same office; a transfer between offices is not attendance.
        val bySession = effective.associateBy { it.id }
        val reconciled = credited.groupBy { bySession.getValue(it.sessionIds.first()).officeId }.values
            .flatMap { union(it, input.policy.shortGapMinutes) }
        return AttendanceResult(effective, union(reconciled), reviews.distinct().sortedWith(
            compareBy<ReviewItem> { it.sessionId ?: "" }.thenBy { it.reason.name }.thenBy { it.sourceEventIds.sorted().joinToString() }))
    }

    /** Gap reconciliation may add the configured gap; plain union never adds time. */
    fun union(intervals: List<CreditedInterval>, gapMinutes: Int = 0): List<CreditedInterval> {
        require(gapMinutes >= 0)
        val result = mutableListOf<CreditedInterval>()
        intervals.filter { it.end > it.start }.sortedWith(compareBy<CreditedInterval> { it.start }.thenBy { it.end }).forEach { interval ->
            val previous = result.lastOrNull()
            if (previous != null && Duration.between(previous.end, interval.start) <= Duration.ofMinutes(gapMinutes.toLong())) {
                result[result.lastIndex] = previous.copy(end = maxOf(previous.end, interval.end),
                    sessionIds = previous.sessionIds + interval.sessionIds,
                    reconciledGap = previous.reconciledGap || interval.reconciledGap || interval.start > previous.end)
            } else result += interval
        }
        return result
    }

    fun observedMinutes(session: Session, now: Instant): Double = session.start?.let {
        max(0.0, Duration.between(it, minOf(session.end ?: now, now)).elapsedMinutes())
    } ?: 0.0

    fun observedDailyMinutes(input: AttendanceInput, date: LocalDate): Double {
        val raw = input.copy(corrections = emptyList(), manualSessions = emptyList(),
            offices = input.offices.map { it.copy(entryGraceMinutes = 0, exitGraceMinutes = 0) },
            policy = input.policy.copy(shortGapMinutes = 0))
        return daily(raw, derive(raw), date).creditedMinutes
    }

    fun daily(input: AttendanceInput, result: AttendanceResult, date: LocalDate): PeriodSummary =
        period(input, result, date, date)

    fun summary(input: AttendanceInput, result: AttendanceResult, target: TargetWindow): PeriodSummary {
        val (start, end) = window(input, target)
        return period(input, result, start, end, includeFutureRequirements = target == TargetWindow.FULL_WEEK)
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
        fun outcome(status: DepartureStatus) = DepartureEstimate(target, status, remaining)
        if (!summary.hasCompleteHistory) return outcome(DepartureStatus.INCOMPLETE_HISTORY)
        val windowStart = summary.startDate.atStartOfDay(input.policy.zoneId).toInstant()
        val windowEnd = summary.endDate.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
        val eligibleOffices = input.offices.filter { it.enabled && it.countsTowardAttendance }.associateBy { it.id }
        val relevant = result.sessions.filter { session ->
            val office = eligibleOffices[session.officeId]
            office != null && (session.end == null || session.end.plusSeconds(office.exitGraceMinutes * 60L) >= windowStart) &&
                (session.start == null || session.start.minusSeconds(office.entryGraceMinutes * 60L) < windowEnd)
        }
        val current = relevant.filter { it.isOpen }
        val benign = setOf(ReviewReason.OPEN_SESSION, ReviewReason.DUPLICATE_EVENT)
        val relevantIds = relevant.map { it.id }.toSet()
        val allSessionIds = result.sessions.map { it.id }.toSet()
        // Ambiguity wins even when the provisional credit exceeds the target. A bad clock,
        // unresolved boundary or competing open offices must never produce a confident exit.
        if (current.size > 1 || relevant.any { session -> session.reviewReasons.any { it !in benign } } ||
            result.reviews.any { it.reason !in benign && (it.sessionId !in allSessionIds || it.sessionId in relevantIds) })
            return outcome(DepartureStatus.NEEDS_REVIEW)
        if (remaining == 0.0) return outcome(DepartureStatus.TARGET_SATISFIED)
        if (current.isEmpty()) return outcome(DepartureStatus.NOT_IN_OFFICE)
        if (input.now < windowStart || input.now >= windowEnd) return outcome(DepartureStatus.OUTSIDE_WINDOW)
        val session = current.single()
        val office = eligibleOffices.getValue(session.officeId)
        val targetAt = input.now.plusMillis(kotlin.math.ceil(remaining * 60000.0).toLong())
        val exitAt = maxOf(input.now, targetAt.minusSeconds(office.exitGraceMinutes * 60L))
        // A continuing visit earns one minute per minute, regardless of overlapping offices.
        // Do not roll today's/rolling window forward to make an otherwise unreachable target fit.
        if (targetAt > windowEnd || exitAt > session.start!!.plusSeconds(input.policy.maxOpenSessionHours * 3600L))
            return outcome(DepartureStatus.UNREACHABLE_IN_WINDOW)
        return DepartureEstimate(target, DepartureStatus.ESTIMATED, remaining, targetAt, exitAt)
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
