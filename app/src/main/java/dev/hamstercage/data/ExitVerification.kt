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
private val decisivePresenceSources = setOf(EXIT_VERIFY_INSIDE,
    "ADAPTIVE_LOCATION_CONFIRMATION", "ADAPTIVE_RECOVERY_CONFIRMATION",
    "FOREGROUND_LOCATION_RECONCILIATION", "BACKGROUND_LOCATION_RECONCILIATION", PRESENCE_CORROBORATION)

/** Verification checks are accepted only for this long after the EXIT was received. */
internal val EXIT_VERIFICATION_WINDOW: Duration = Duration.ofMinutes(5)

/**
 * A phantom EXIT may be rejected only when inside sampling began soon enough after the
 * EXIT's own observation that an unobserved departure and return in between is no longer
 * than the engine's three-minute adaptive confirmation window. This compares observation
 * times, so platform delivery latency (documented as typically up to two to three minutes)
 * and late WorkManager execution are both accounted for by the first sample's time.
 */
internal val EXIT_VERIFICATION_MAX_UNOBSERVED: Duration = Duration.ofMinutes(3)

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
        fun hasSustainedInside(): Boolean = exits.isNotEmpty() && outside.isEmpty() &&
            inside.size >= 5 &&
            Duration.between(exits.first().event.at, inside.first().event.at) <= EXIT_VERIFICATION_MAX_UNOBSERVED &&
            Duration.between(inside.first().event.at, inside.last().event.at).toMinutes() >= 4
        fun settle(terminal: RecordedEvent? = null) {
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
            // reconciliation, and its samples count as ordinary observations at their times.
            val expired = terminal == null &&
                now > exits.maxOf { it.receivedAt }.plus(EXIT_VERIFICATION_WINDOW)
            val pendingVerdict = terminal == null && !decisiveOutside && !sustainedInside && !quickReturn
            if (!sustainedInside && !quickReturn && !(pendingVerdict && expired)) provisionalPresence += inside.filter {
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
            when {
                fact.source == EXIT_VERIFY_INSIDE && fact.event.transition == Transition.PRESENCE &&
                    exits.isNotEmpty() && fact.event.at > exits.first().event.at -> inside += fact
                fact.source == EXIT_VERIFY_OUTSIDE && fact.event.transition == Transition.ABSENCE &&
                    exits.isNotEmpty() && fact.event.at > exits.first().event.at -> outside += fact
                fact.source == "PLAY_SERVICES_GEOFENCE" && fact.event.transition == Transition.EXIT -> {
                    if (hasSustainedInside()) {
                        settle()
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
