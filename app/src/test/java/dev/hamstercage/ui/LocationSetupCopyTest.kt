package dev.hamstercage.ui

import dev.hamstercage.capture.RegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocationSetupCopyTest {
    @Test fun unknownRegistrationIsUnconfirmedNotASetupFailure() {
        val unknown = registrationStatusText(RegistrationStatus.UNKNOWN)
        assertNotEquals(registrationStatusText(RegistrationStatus.NEEDS_SETUP), unknown)
        assertFalse(unknown.contains("not running"))
        assertFalse(unknown.contains("not set up"))
    }

    @Test fun persistedCheckResultsMapToPlainLabelsWithAnHonestFallback() {
        assertEquals("Inside an office area", lastCheckText("Inside office"))
        assertEquals("Outside your office areas", lastCheckText("Outside offices"))
        assertEquals("Office detection could not be set up", lastCheckText("Office registration unavailable"))
        assertEquals("Device location is off", lastCheckText("Device location is off"))
        assertEquals("Unknown; the last result could not be read", lastCheckText("Unavailable"))
        assertEquals("Unknown result", lastCheckText("internal-state-xyz"))
    }

    @Test fun noMappedLabelRepeatsTheTechnicalBoundaryWording() {
        listOf("Stale location; retry later", "Boundary check delayed; later checks may recover",
            "Boundary check unavailable; retry from the app", "Boundary confirmation unavailable; later checks may recover",
            "Newer boundary event; retry later", "Office boundary refresh skipped; later checks may recover",
            "Office boundary refresh unavailable; later checks may recover", "Office boundary uncertain").forEach {
            assertFalse(it, lastCheckText(it).contains("boundary", ignoreCase = true))
        }
    }
}
