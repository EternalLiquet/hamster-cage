package dev.hamstercage.data

import dev.hamstercage.domain.Transition
import java.time.Duration
import java.time.Instant

/** Source metadata, not mutable attendance: replay yields the same candidate decision. */
internal data class ExitVerificationState(
    val candidateIds: Set<String>, val rejectedIds: Set<String>,
    val provisionalAbsenceIds: Set<String>, val provisionalPresenceIds: Set<String>,
    val uncertainIds: Set<String>, val delayedIds: Set<String>,
)

internal const val EXIT_VERIFY_INSIDE = "EXIT_VERIFICATION_INSIDE"
internal const val EXIT_VERIFY_OUTSIDE = "EXIT_VERIFICATION_OUTSIDE"
internal val decisivePresenceSources = setOf(EXIT_VERIFY_INSIDE,
    "ADAPTIVE_LOCATION_CONFIRMATION", "ADAPTIVE_RECOVERY_CONFIRMATION",
    "FOREGROUND_LOCATION_RECONCILIATION", "BACKGROUND_LOCATION_RECONCILIATION", PRESENCE_CORROBORATION)

/** Verification checks are accepted only for this long after the EXIT was received. */
internal val EXIT_VERIFICATION_WINDOW: Duration = Duration.ofMinutes(5)

/**
 * A phantom EXIT may be rejected only by continuous inside evidence after it: no stretch
 * from the EXIT's own observation to the first inside sample, or between consecutive
 * samples, may exceed the engine's three-minute adaptive confirmation window, and the
 * evidence must reach at least four minutes past the EXIT. Comparing observation times
 * accounts for both platform delivery latency (documented as typically up to two to three
 * minutes) and late or bunched WorkManager execution. Four of the five scheduled checks
 * suffice, so one ambiguous indoor fix does not by itself prevent a rejection.
 */
internal val EXIT_VERIFICATION_MAX_UNOBSERVED: Duration = Duration.ofMinutes(3)
internal val EXIT_VERIFICATION_MIN_SPAN: Duration = Duration.ofMinutes(4)
internal const val EXIT_VERIFICATION_MIN_INSIDE = 4

internal fun exitVerificationState(evidence: List<RecordedEvent>, now: Instant): ExitVerificationState {
    val candidate = mutableSetOf<String>()
    val rejected = mutableSetOf<String>()
    val provisional = mutableSetOf<String>()
    val provisionalPresence = mutableSetOf<String>()
    val uncertain = mutableSetOf<String>()
    val delayed = mutableSetOf<String>()
    evidence.groupBy { it.event.officeId }.values.forEach { officeFacts ->
        val ordered = officeFacts.filter { it.event.at <= now }
            .sortedWith(compareBy<RecordedEvent> { it.event.at }.thenBy { it.event.id })
        var insideKnown = false
        var exits = mutableListOf<RecordedEvent>()
        var inside = mutableListOf<RecordedEvent>()
        var outside = mutableListOf<RecordedEvent>()
        // Evidence must follow the latest departure signal in the epoch; samples taken before
        // a later EXIT cannot vouch for the time after it.
        fun hasSustainedInside(): Boolean {
            if (exits.isEmpty() || outside.isNotEmpty()) return false
            val lastExit = exits.maxOf { it.event.at }
            val after = inside.map { it.event.at }.filter { it > lastExit }.sorted()
            if (after.size < EXIT_VERIFICATION_MIN_INSIDE) return false
            var previous = lastExit
            after.forEach { at ->
                if (Duration.between(previous, at) > EXIT_VERIFICATION_MAX_UNOBSERVED) return false
                previous = at
            }
            return Duration.between(lastExit, after.last()) >= EXIT_VERIFICATION_MIN_SPAN
        }
        fun epochDeadline(): Instant = exits.maxOf { it.receivedAt }.plus(EXIT_VERIFICATION_WINDOW)
        fun settle(terminal: RecordedEvent? = null, asOf: Instant = now) {
            if (exits.isEmpty()) return
            val first = exits.minOf { it.event.at }
            val delayedDelivery = Duration.between(first, exits.first().receivedAt).seconds > 120
            if (delayedDelivery) delayed += exits.map { it.event.id }
            var precedingOutside: RecordedEvent? = null
            var confirmedOutsideAt: Instant? = null
            (inside + outside).sortedWith(compareBy<RecordedEvent> { it.event.at }
                .thenBy { it.event.id }).forEach { sample ->
                if (confirmedOutsideAt != null) return@forEach
                if (sample.event.transition == Transition.PRESENCE) precedingOutside = null
                else {
                    if (precedingOutside != null &&
                        Duration.between(precedingOutside!!.event.at, sample.event.at).seconds >= 45)
                        confirmedOutsideAt = sample.event.at
                    precedingOutside = sample
                }
            }
            val otherOfficeAt = evidence.filter { other ->
                other.event.officeId != officeFacts.first().event.officeId &&
                    other.event.transition == Transition.PRESENCE &&
                    other.source in decisivePresenceSources &&
                    other.event.at > first && other.event.at <= (terminal?.event?.at ?: now)
            }.minOfOrNull { it.event.at }
            if (otherOfficeAt != null &&
                (confirmedOutsideAt?.let { otherOfficeAt < it } ?: true))
                confirmedOutsideAt = otherOfficeAt
            val decisiveOutside = confirmedOutsideAt != null
            val sustainedInside = hasSustainedInside()
            val quickReturn = !delayedDelivery && terminal != null &&
                terminal.source == "PLAY_SERVICES_GEOFENCE" &&
                terminal.event.transition == Transition.ENTER &&
                terminal.event.at <= first.plusSeconds(60) && outside.isEmpty()
            // No check can be accepted after the window, so an undecided EXIT is no longer
            // awaiting confirmation: it becomes an unresolved departure for review and later
            // reconciliation. Its inside samples stay evidence only: they neither restore the
            // closed visit nor open a new one, so an early inside fix after a genuine departure
            // cannot start live credit. A later routine check or platform ENTER establishes
            // current presence at its own time.
            val expired = terminal == null && asOf > epochDeadline()
            if (!sustainedInside && !quickReturn) provisionalPresence += inside.filter {
                confirmedOutsideAt?.let { confirmed -> it.event.at < confirmed } ?: true
            }.map { it.event.id }
            when {
                decisiveOutside && sustainedInside -> uncertain += exits.map { it.event.id }
                decisiveOutside -> Unit // Raw EXIT supplies the earliest observed departure boundary.
                sustainedInside || quickReturn -> rejected += exits.map { it.event.id }
                terminal?.event?.transition == Transition.ABSENCE -> Unit // Decisive later outside check.
                terminal == null && !expired -> {
                    candidate += exits.map { it.event.id }
                    provisional += outside.map { it.event.id }
                }
                else -> uncertain += exits.map { it.event.id }
            }
            exits = mutableListOf(); inside = mutableListOf(); outside = mutableListOf()
        }
        ordered.forEach { fact ->
            // No check is accepted after an epoch's deadline, so a later fact cannot belong to
            // it. A later EXIT closes the epoch first (as expired if undecided), carrying
            // forward only what its own samples last showed. Later inside or outside facts,
            // including stray verification samples, settle it as ordinary observations.
            val pastDeadline = exits.isNotEmpty() && fact.event.at > epochDeadline()
            if (pastDeadline && fact.source == "PLAY_SERVICES_GEOFENCE" && fact.event.transition == Transition.EXIT) {
                val lastSample = (inside + outside).maxWithOrNull(compareBy<RecordedEvent> { it.event.at }.thenBy { it.event.id })
                val wasRejected = hasSustainedInside()
                settle(asOf = fact.event.at)
                insideKnown = wasRejected || lastSample?.event?.transition == Transition.PRESENCE
            }
            when {
                !pastDeadline && fact.source == EXIT_VERIFY_INSIDE && fact.event.transition == Transition.PRESENCE &&
                    exits.isNotEmpty() && fact.event.at > exits.first().event.at -> inside += fact
                !pastDeadline && fact.source == EXIT_VERIFY_OUTSIDE && fact.event.transition == Transition.ABSENCE &&
                    exits.isNotEmpty() && fact.event.at > exits.first().event.at -> outside += fact
                fact.source == "PLAY_SERVICES_GEOFENCE" && fact.event.transition == Transition.EXIT -> {
                    if (hasSustainedInside()) {
                        settle(asOf = fact.event.at)
                        insideKnown = true
                    }
                    if (insideKnown || exits.isNotEmpty()) exits += fact
                    insideKnown = false
                }
                fact.event.transition == Transition.ENTER || fact.event.transition == Transition.PRESENCE -> {
                    settle(fact)
                    insideKnown = true
                }
                fact.event.transition == Transition.ABSENCE -> {
                    settle(fact)
                    insideKnown = false
                }
            }
        }
        settle()
    }
    return ExitVerificationState(candidate, rejected, provisional, provisionalPresence,
        uncertain, delayed)
}

/** EXITs that a newly appended verification sample has just rejected. */
internal fun newlyRejectedExitIds(before: ExitVerificationState, after: ExitVerificationState): Set<String> =
    after.rejectedIds - before.rejectedIds

/**
 * After a rejected EXIT, the platform may still consider the device outside and so report
 * no further EXIT for a real departure. Routine inside checks are then worth recording,
 * because they are the only evidence that bounds that visit's credit.
 */
internal fun needsPresenceCorroboration(snapshot: AppSnapshot, now: Instant, officeId: String): Boolean {
    val rejected = snapshot.input(now).rejectedExitIds
    if (rejected.isEmpty()) return false
    return snapshot.derive(now).sessions.any { it.isOpen && it.officeId == officeId &&
        it.manualSessionId == null && it.sourceEventIds.any { id -> id in rejected } }
}
