package dev.hamstercage.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.max

/** Pure deterministic derivation. Inputs, raw observations and corrections are never mutated. */
object AttendanceEngine {
    private data class Observation(val event: RawEvent, val ids: Set<String>, val duplicate: Boolean)

    fun derive(input: AttendanceInput): AttendanceResult {
        val reviews = mutableListOf<ReviewItem>()
        val sessions = mutableListOf<Session>()
        val offices = input.offices.associateBy { it.id }
        require(offices.size == input.offices.size) { "Office IDs must be unique" }
        val conflictingIds = input.events.groupBy { it.id }.filterValues { it.distinct().size > 1 }.keys
        conflictingIds.sorted().forEach { reviews += ReviewItem(ReviewReason.CONFLICTING_EVENT_ID, setOf(it)) }
        val usable = input.events.filter { event ->
            when {
                event.id in conflictingIds -> false
                event.officeId !in offices -> { reviews += ReviewItem(ReviewReason.UNKNOWN_OFFICE, setOf(event.id)); false }
                event.at > input.now -> { reviews += ReviewItem(ReviewReason.FUTURE_EVENT, setOf(event.id)); false }
                else -> true
            }
        }
        usable.groupBy { it.officeId }.toSortedMap().forEach { (officeId, events) ->
            val observations = events.groupBy { Pair(it.at, it.transition) }.map { (_, copies) ->
                Observation(copies.minBy { it.id }, copies.map { it.id }.toSet(), copies.size > 1)
            }.sortedWith(compareBy<Observation> { it.event.at }.thenBy { it.event.id })
            var open: Observation? = null
            val ids = linkedSetOf<String>()
            val flags = linkedSetOf<ReviewReason>()
            fun addSession(exit: Observation?) {
                val enter = open
                val sourceIds = ids.toSet()
                val sessionId = "session:${enter?.event?.id ?: exit!!.event.id}"
                val reasons = flags.toSet()
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
            observations.forEach { observation ->
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
                id.isBlank() || manual.createdAt > input.now || manual.start > input.now ||
                    (manual.end != null && (manual.end < manual.start || manual.end > input.now)) ->
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

        val corrections = input.corrections.groupBy { it.sessionId }
        val sessionIds = sessions.map { it.id }.toSet()
        corrections.keys.filter { it !in sessionIds }.sorted().forEach {
            reviews += ReviewItem(ReviewReason.ORPHAN_CORRECTION, sessionId = it)
        }
        val effective = sessions.map { original ->
            val correction = corrections[original.id]?.maxWithOrNull(compareBy<Correction> { it.createdAt }.thenBy { it.id })
            if (correction == null) original else if (
                correction.createdAt > input.now || correction.start > input.now ||
                (correction.end != null && (correction.end < correction.start || correction.end > input.now))
            ) {
                reviews += ReviewItem(ReviewReason.INVALID_CORRECTION, original.sourceEventIds, original.id)
                original.copy(confidence = Confidence.LOW, reviewReasons = original.reviewReasons + ReviewReason.INVALID_CORRECTION)
            } else {
                val flags = if (correction.end == null) {
                    if (Duration.between(correction.start, input.now) > Duration.ofHours(input.policy.maxOpenSessionHours.toLong()))
                        setOf(ReviewReason.OPEN_SESSION, ReviewReason.STALE_OPEN_SESSION)
                    else setOf(ReviewReason.OPEN_SESSION)
                } else emptySet()
                // The original evidence remains in input/events; old warnings are resolved by explicit correction.
                reviews.removeAll { it.sessionId == original.id }
                flags.forEach { reviews += ReviewItem(it, original.sourceEventIds, original.id) }
                original.copy(start = correction.start, end = correction.end, confidence = Confidence.MANUAL,
                    reviewReasons = flags, correctionId = correction.id)
            }
        }.sortedWith(compareBy<Session> { it.start ?: it.end }.thenBy { it.id })

        val credited = effective.mapNotNull { session ->
            val office = offices.getValue(session.officeId)
            val start = session.start
            if (start == null || !office.enabled || !office.countsTowardAttendance || ReviewReason.STALE_OPEN_SESSION in session.reviewReasons) null
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
        max(0.0, Duration.between(it, minOf(session.end ?: now, now)).toMillis() / 60000.0)
    } ?: 0.0

    /** Union of reconstructed raw bounds, without corrections, walking grace or gap credit. */
    fun observedDailyMinutes(input: AttendanceInput, date: LocalDate): Double {
        val rawInput = input.copy(corrections = emptyList(), manualSessions = emptyList(),
            offices = input.offices.map { it.copy(entryGraceMinutes = 0, exitGraceMinutes = 0) },
            policy = input.policy.copy(shortGapMinutes = 0))
        return daily(rawInput, derive(rawInput), date).creditedMinutes
    }

    fun daily(input: AttendanceInput, result: AttendanceResult, date: LocalDate): PeriodSummary =
        period(input, result, date, date, includeFutureRequirements = false)

    fun summary(input: AttendanceInput, result: AttendanceResult, target: TargetWindow): PeriodSummary {
        val (start, end) = window(input, target)
        return period(input, result, start, end, target == TargetWindow.FULL_WEEK)
    }

    fun period(input: AttendanceInput, result: AttendanceResult, startDate: LocalDate, endDate: LocalDate,
               includeFutureRequirements: Boolean = false): PeriodSummary {
        require(endDate >= startDate)
        require(java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) <= 36600) { "Period too large" }
        val today = input.now.atZone(input.policy.zoneId).toLocalDate()
        val expected = generateSequence(startDate) { it.plusDays(1) }.takeWhile { it <= endDate }
            .filter { input.policy.isExpected(it) && (includeFutureRequirements || it <= today) }.toList()
        val future = expected.count { it > today }
        val unknown = expected.count { it <= today && (input.historyStartDate == null || it < input.historyStartDate || it in input.unknownDates) }
        val start = startDate.atStartOfDay(input.policy.zoneId).toInstant()
        val end = minOf(endDate.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant(), input.now)
        val credited = if (end <= start) 0.0 else result.intervals.sumOf {
            val clippedStart = maxOf(start, it.start)
            val clippedEnd = minOf(end, it.end)
            if (clippedEnd <= clippedStart) 0.0 else Duration.between(clippedStart, clippedEnd).toMillis() / 60000.0
        }
        val required = expected.size * input.policy.targetMinutesPerDay
        return PeriodSummary(startDate, endDate, credited, expected.size, required, credited - required,
            if (expected.isEmpty()) null else credited / expected.size, expected.size - unknown - future, unknown, future)
    }

    fun departure(input: AttendanceInput, result: AttendanceResult, target: TargetWindow): DepartureEstimate {
        val summary = summary(input, result, target)
        val remaining = max(0.0, summary.requiredMinutes - summary.creditedMinutes)
        fun outcome(status: DepartureStatus) = DepartureEstimate(target, status, remaining)
        if (!summary.hasCompleteHistory) return outcome(DepartureStatus.INCOMPLETE_HISTORY)
        if (remaining == 0.0) return outcome(DepartureStatus.TARGET_SATISFIED)
        val current = result.sessions.filter { it.isOpen && input.offices.any { office -> office.id == it.officeId && office.enabled && office.countsTowardAttendance } }
        if (current.isEmpty()) return outcome(DepartureStatus.NOT_IN_OFFICE)
        if (current.size != 1 || current.single().reviewReasons.any { it != ReviewReason.OPEN_SESSION && it != ReviewReason.DUPLICATE_EVENT })
            return outcome(DepartureStatus.NEEDS_REVIEW)
        val session = current.single()
        val office = input.offices.single { it.id == session.officeId }
        val windowStart = summary.startDate.atStartOfDay(input.policy.zoneId).toInstant()
        val windowEnd = summary.endDate.plusDays(1).atStartOfDay(input.policy.zoneId).toInstant()
        if (input.now < windowStart || input.now >= windowEnd) return outcome(DepartureStatus.OUTSIDE_WINDOW)
        val targetAt = input.now.plusMillis(kotlin.math.ceil(remaining * 60000.0).toLong())
        val exitAt = maxOf(input.now, targetAt.minusSeconds(office.exitGraceMinutes * 60L))
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
}
