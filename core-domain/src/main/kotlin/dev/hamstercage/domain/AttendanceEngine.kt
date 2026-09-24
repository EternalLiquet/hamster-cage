package dev.hamstercage.domain

import java.time.Duration
import java.time.Instant
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
            val correction = original.correctionTargetIds.flatMap { corrections[it].orEmpty() }
                .maxWithOrNull(compareBy<Correction> { it.createdAt }.thenBy { it.id })
            if (correction == null) original else if (
                correction.id.isBlank() || !correction.createdAt.isStorageTime() || !correction.start.isStorageTime() ||
                (correction.end != null && !correction.end.isStorageTime()) || correction.createdAt > input.now || correction.start > input.now ||
                (correction.end != null && (correction.end <= correction.start || correction.end > input.now))
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

    private fun Instant.isStorageTime(): Boolean = try { toEpochMilli(); true } catch (_: ArithmeticException) { false }
}
