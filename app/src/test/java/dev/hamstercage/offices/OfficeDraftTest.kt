package dev.hamstercage.offices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficeDraftTest {
    @Test fun defaultsAndBoundaryValuesAreAccepted() {
        val office = OfficeDraft(name = "  Synthetic  ", latitude = "-90", longitude = "180",
            radiusMeters = "50", entryGraceMinutes = "0", exitGraceMinutes = "120").toOffice("office-a")
        assertEquals("Synthetic", office.name)
        assertEquals(-90.0, office.latitude, 0.0)
        assertEquals(180.0, office.longitude, 0.0)
        assertEquals(50f, office.radiusMeters, 0f)
        assertEquals(0, office.entryGraceMinutes)
        assertEquals(120, office.exitGraceMinutes)
        val defaults = OfficeDraft(name = "Synthetic", latitude = "0", longitude = "0").toOffice("office-b")
        assertEquals(150f, defaults.radiusMeters, 0f)
        assertEquals(5, defaults.entryGraceMinutes)
        assertEquals(5, defaults.exitGraceMinutes)
    }

    @Test fun invalidCoordinatesRadiusAndGraceCannotBecomeAnOffice() {
        val base = OfficeDraft(name = "Synthetic", latitude = "0", longitude = "0")
        for (draft in listOf(
            base.copy(latitude = "90.0001"), base.copy(latitude = "NaN"),
            base.copy(longitude = "-180.0001"), base.copy(longitude = "Infinity"),
            base.copy(radiusMeters = "49.9"), base.copy(radiusMeters = "5000.1"),
            base.copy(entryGraceMinutes = "-1"), base.copy(entryGraceMinutes = "121"),
            base.copy(exitGraceMinutes = "-1"), base.copy(exitGraceMinutes = "121"),
            base.copy(name = "  "),
        )) assertThrows(IllegalArgumentException::class.java) { draft.toOffice("office") }
    }
}
