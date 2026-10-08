package dev.hamstercage.data

import dev.hamstercage.domain.Transition

/** A routine inside check recorded only to corroborate an open visit whose EXIT was rejected. */
internal const val PRESENCE_CORROBORATION = "BACKGROUND_PRESENCE_CORROBORATION"

/** Every persisted observation source and the transitions it may record. */
internal fun observationSourceAccepts(source: String, transition: Transition): Boolean = when (source) {
    "PLAY_SERVICES_GEOFENCE" -> transition in setOf(Transition.ENTER, Transition.EXIT)
    "FOREGROUND_LOCATION_RECONCILIATION" -> transition == Transition.PRESENCE
    "BACKGROUND_LOCATION_RECONCILIATION" -> transition in setOf(Transition.PRESENCE, Transition.ABSENCE)
    "ADAPTIVE_LOCATION_CONFIRMATION" -> transition in setOf(Transition.PRESENCE, Transition.ABSENCE)
    "ADAPTIVE_RECOVERY_CONFIRMATION" -> transition == Transition.PRESENCE
    EXIT_VERIFY_INSIDE -> transition == Transition.PRESENCE
    EXIT_VERIFY_OUTSIDE -> transition == Transition.ABSENCE
    PRESENCE_CORROBORATION -> transition == Transition.PRESENCE
    else -> false
}
