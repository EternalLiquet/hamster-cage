package dev.hamstercage.data

import dev.hamstercage.domain.Transition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationSourcesTest {
    @Test fun everySourceTheAppWritesIsAcceptedForItsTransitions() {
        // Exit verification writes inside PRESENCE and outside ABSENCE samples; a repository
        // that rejected them would silently drop every bounded verification check.
        assertTrue(observationSourceAccepts(EXIT_VERIFY_INSIDE, Transition.PRESENCE))
        assertTrue(observationSourceAccepts(EXIT_VERIFY_OUTSIDE, Transition.ABSENCE))
        assertTrue(observationSourceAccepts(PRESENCE_CORROBORATION, Transition.PRESENCE))
        assertTrue(observationSourceAccepts("PLAY_SERVICES_GEOFENCE", Transition.ENTER))
        assertTrue(observationSourceAccepts("PLAY_SERVICES_GEOFENCE", Transition.EXIT))
        assertTrue(observationSourceAccepts("ADAPTIVE_RECOVERY_CONFIRMATION", Transition.PRESENCE))
    }

    @Test fun sourcesCannotRecordOtherTransitions() {
        assertFalse(observationSourceAccepts(EXIT_VERIFY_INSIDE, Transition.ABSENCE))
        assertFalse(observationSourceAccepts(EXIT_VERIFY_OUTSIDE, Transition.PRESENCE))
        assertFalse(observationSourceAccepts(EXIT_VERIFY_INSIDE, Transition.ENTER))
        assertFalse(observationSourceAccepts(PRESENCE_CORROBORATION, Transition.ABSENCE))
        assertFalse(observationSourceAccepts("PLAY_SERVICES_GEOFENCE", Transition.PRESENCE))
        assertFalse(observationSourceAccepts("UNKNOWN", Transition.PRESENCE))
    }
}
