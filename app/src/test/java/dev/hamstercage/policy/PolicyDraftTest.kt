package dev.hamstercage.policy

import dev.hamstercage.data.PolicySettings
import dev.hamstercage.domain.Policy
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class PolicyDraftTest {
    private val draft = PolicyDraft.from(Policy())
    @Test fun defaultsMatchTheProductRules() {
        assertEquals(PolicySettings(), draft.validated())
        assertEquals(360, draft.validated().targetMinutesPerDay)
        assertEquals(ZoneId.of("America/New_York"), draft.validated().zoneId)
        assertEquals((1..5).map(DayOfWeek::of).toSet(), draft.validated().expectedWeekdays)
        assertEquals(10, draft.validated().shortGapMinutes)
    }
    @Test fun rejectsMalformedAndOutOfRangeValues() {
        listOf("", "0", "1441", "1.5", "99999999999999999").forEach { value -> rejected(draft.copy(targetMinutes = value)) }
        listOf("-1", "121", "NaN").forEach { rejected(draft.copy(gapMinutes = it)) }
        listOf("0", "25", "1.1").forEach { rejected(draft.copy(maxOpenHours = it)) }
        rejected(draft.copy(zone = "Not/A_Zone")); rejected(draft.copy(weekdays = emptySet()))
    }
    @Test fun acceptedBoundaryValuesRemainExplicit() {
        assertEquals(1, draft.copy(targetMinutes = "1", gapMinutes = "0", maxOpenHours = "1").validated().targetMinutesPerDay)
        val upper = draft.copy(targetMinutes = "1440", gapMinutes = "120", maxOpenHours = "24", weekdays = setOf(DayOfWeek.SUNDAY)).validated()
        assertEquals(1440, upper.targetMinutesPerDay); assertEquals(120, upper.shortGapMinutes); assertEquals(24, upper.maxOpenSessionHours)
    }
    @Test fun deviceTimezoneTravelDoesNotChangeThePolicyZone() {
        val before = TimeZone.getDefault()
        try { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")); assertEquals(ZoneId.of("America/New_York"), draft.validated().zoneId) }
        finally { TimeZone.setDefault(before) }
    }
    private fun rejected(value: PolicyDraft) {
        try { value.validated(); fail("Expected invalid policy to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
