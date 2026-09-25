package dev.hamstercage.offices

import dev.hamstercage.domain.Office
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeRadiusGuidanceTest {
    @Test fun smallRadiusWarningPreservesChoice() {
        assertTrue(OfficeRadiusGuidance.isUnusuallySmall(50f))
        assertFalse(OfficeRadiusGuidance.isUnusuallySmall(150f))
        assertEquals(50f, OfficeDraft("Small", "0", "0", "50").toOffice("small").radiusMeters)
    }

    @Test fun touchingEligibleOfficesWarnButDistantOrDisabledOfficesDoNot() {
        val a = Office("a", "A", 0.0, 0.0, 200f)
        val b = Office("b", "B", 0.0, 0.003, 200f)
        val c = Office("c", "C", 0.0, 0.01, 200f)
        assertEquals(listOf(b), OfficeRadiusGuidance.touching(a, listOf(a, b, c)))
        assertTrue(OfficeRadiusGuidance.touching(a, listOf(b.copy(enabled = false), c)).isEmpty())
        assertTrue(OfficeRadiusGuidance.touching(a, listOf(b.copy(countsTowardAttendance = false))).isEmpty())
    }
}
