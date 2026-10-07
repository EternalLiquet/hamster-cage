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
    "FOREGROUND_LOCATION_RECONCILIATION", "BACKGROUND_LOCATION_RECONCILIATION")

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
            Duration.between(exits.first().event.at, exits.first().receivedAt).seconds <= 120 &&
            inside.size >= 5 &&
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
            if (!sustainedInside && !quickReturn) provisionalPresence += inside.filter {
                confirmedOutsideAt?.let { confirmed -> it.event.at < confirmed } ?: true
            }.map { it.event.id }
            when {
                decisiveOutside && sustainedInside -> uncertain += exits.map { it.event.id }
                decisiveOutside -> Unit // Raw EXIT supplies the earliest observed departure boundary.
                sustainedInside || quickReturn -> rejected += exits.map { it.event.id }
                terminal?.event?.transition == Transition.ABSENCE -> Unit // Decisive later outside check.
                terminal == null -> {
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
