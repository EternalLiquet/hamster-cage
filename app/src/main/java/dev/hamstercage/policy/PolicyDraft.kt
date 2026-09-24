package dev.hamstercage.policy

import dev.hamstercage.data.PolicySettings
import dev.hamstercage.domain.Policy
import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.ZoneId

data class PolicyDraft(
    val targetMinutes: String, val zone: String, val gapMinutes: String,
    val maxOpenHours: String, val weekdays: Set<DayOfWeek>,
) {
    fun validated(): PolicySettings {
        val target = targetMinutes.trim().toIntOrNull()
        val gap = gapMinutes.trim().toIntOrNull()
        val maxOpen = maxOpenHours.trim().toIntOrNull()
        require(target != null && target in 1..1440) { "Target must be 1 to 1440 whole minutes." }
        require(gap != null && gap in 0..120) { "Short gap must be 0 to 120 whole minutes." }
        require(maxOpen != null && maxOpen in 1..24) { "Open-session review must be 1 to 24 whole hours." }
        require(weekdays.isNotEmpty()) { "Choose at least one expected weekday." }
        val policyZone = try { ZoneId.of(zone.trim()) }
        catch (_: DateTimeException) { throw IllegalArgumentException("Enter a valid policy timezone, such as America/New_York.") }
        return PolicySettings(policyZone, target, weekdays.toSet(), gap, maxOpen)
    }

    companion object {
        fun from(policy: Policy) = PolicyDraft(policy.targetMinutesPerDay.toString(), policy.zoneId.id,
            policy.shortGapMinutes.toString(), policy.maxOpenSessionHours.toString(), policy.expectedWeekdays.toSet())
    }
}
