package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.AttendanceResult
import dev.hamstercage.domain.TargetWindow
import dev.hamstercage.domain.DepartureStatus
import java.time.Duration
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean = false,
    openOffices: () -> Unit = {}, openHistory: () -> Unit = {},
) {
    val today = input.now.atZone(input.policy.zoneId).toLocalDate()
    val daily = AttendanceEngine.daily(input, result, today)
    val presence = dashboardPresence(input, result, trackingReady)
    val todayDeparture = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
    val currentOffice = result.sessions.singleOrNull { session -> session.isOpen &&
        input.offices.any { it.id == session.officeId && it.enabled && it.countsTowardAttendance }
    }?.let { session -> input.offices.single { it.id == session.officeId } }
    Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Panel(warm = true) {
            Text("TODAY", style = MaterialTheme.typography.labelMedium, color = CageStyle.Amber)
            Text(presence.label, Modifier.testTag("office_state"), style = MaterialTheme.typography.titleMedium)
            Text(minutesText(daily.creditedMinutes), Modifier.fillMaxWidth().testTag("today_credit"), style = MaterialTheme.typography.displaySmall)
            Text(if (daily.hasCompleteHistory) "Credited office time" else "Provisional credit · coverage incomplete",
                style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            DashboardMetric("Target today", minutesText(daily.requiredMinutes.toDouble()), "today_target")
            Text(todayLeaveText(todayDeparture, trackingReady, input.now, input.policy.zoneId),
                Modifier.fillMaxWidth().testTag("today_leave"), style = MaterialTheme.typography.titleLarge)
            Text("Today's ${minutesText(daily.requiredMinutes.toDouble())} target · provisional until today's coverage is reviewed.",
                Modifier.testTag("today_leave_context"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            if (todayDeparture.status == DepartureStatus.ESTIMATED && trackingReady && currentOffice != null) {
                Text("Leave the office geofence at this time. Building exit and detected geofence EXIT may differ; " +
                    "${currentOffice.exitGraceMinutes}m exit grace is projection only.",
                    Modifier.testTag("today_leave_boundary"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            }
            DashboardMetric("Balance", if (daily.hasCompleteHistory) balanceText(daily.balanceMinutes) else "Unknown", "today_balance", true)
            DashboardMetric("Device-observed time", minutesText(AttendanceEngine.observedDailyMinutes(input, today)), "today_observed")
            presence.sessionStarted?.let { DashboardMetric("Session started", instantText(it, input.policy.zoneId), "session_start") }
            val open = result.sessions.filter { it.isOpen && input.offices.any { office -> office.id == it.officeId && office.enabled && office.countsTowardAttendance } }
            if (open.size == 1) {
                val session = open.single()
                val grace = input.offices.single { it.id == session.officeId }.entryGraceMinutes
                val creditStart = session.start!!.plusSeconds(grace * 60L)
                Text("Arrival walking grace: ${grace}m uncredited. Credit starts at ${instantText(creditStart, input.policy.zoneId)}.",
                    Modifier.testTag("arrival_credit_start"), style = MaterialTheme.typography.bodyMedium)
                if (input.now < creditStart) {
                    val minutesLeft = (Duration.between(input.now, creditStart).seconds + 59) / 60
                    Text("${minutesLeft}m until credit starts (if the observed visit continues).", Modifier.testTag("arrival_countdown"))
                }
            }
            if (presence.manual) Tag("MANUAL BOUNDS")
            if (!trackingReady && presence.sessionStarted != null)
                Text("Live office state is unconfirmed. Open-session totals are estimates until reviewed.", style = MaterialTheme.typography.bodyMedium)
            if (!daily.hasCompleteHistory)
                Text("Missing coverage is unknown, never a confirmed absence. Review the timeline before relying on the balance.", style = MaterialTheme.typography.bodyMedium)
        }
        if (input.offices.none { it.enabled && it.countsTowardAttendance })
            Notice("Set up an eligible office", "Add or enable an office to begin automatic attendance setup. No location is preloaded.", "Open office setup", openOffices)
        else if (!trackingReady)
            Notice("Detection is not confirmed", "Review office setup and location permissions. A configured zone alone does not prove you are outside.", "Open office setup", openOffices)
        if (presence.needsReview)
            Notice("A session needs review", "Original observations are retained. Review uncertain boundaries before relying on these estimates.", "Review history", openHistory)
        if (input.events.isEmpty() && input.manualSessions.isEmpty())
            Text("No attendance recorded yet.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        Panel {
            Text("Departure targets", style = MaterialTheme.typography.titleLarge)
            Text("Departure estimates may use office exit grace; it never adds recorded attendance.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            TargetWindow.entries.forEach { target ->
                val estimate = AttendanceEngine.departure(input, result, target)
                val value = when (estimate.status) {
                    DepartureStatus.TARGET_SATISFIED -> "Already satisfied"
                    DepartureStatus.ESTIMATED -> if (!trackingReady) "Detection unconfirmed" else
                        "About ${departureTimeText(estimate.estimatedExitAt!!, input.now, input.policy.zoneId)}"
                    DepartureStatus.NOT_IN_OFFICE -> if (!trackingReady) "Office state unknown" else "No active session"
                    DepartureStatus.NEEDS_REVIEW -> "Needs review"
                    DepartureStatus.INCOMPLETE_HISTORY -> "History incomplete"
                    DepartureStatus.OUTSIDE_WINDOW -> "Outside target window"
                    DepartureStatus.UNREACHABLE_IN_WINDOW -> "Beyond this window"
                    DepartureStatus.OVERLAPPING_SESSIONS -> "Overlapping active sessions"
                }
                DashboardMetric(estimate.targetName, value, "${target.name}_departure")
            }
        }
        Text("THE BIGGER PICTURE", style = MaterialTheme.typography.labelMedium, color = CageStyle.Secondary)
        listOf("This week" to TargetWindow.WEEK_TO_DATE, "Rolling 30 days" to TargetWindow.ROLLING_30,
            "Rolling 90 days" to TargetWindow.ROLLING_90).forEach { (title, target) ->
            val summary = AttendanceEngine.summary(input, result, target)
            val coverage = AttendanceEngine.reportingCoverage(input, result, summary.startDate, summary.endDate)
            Panel {
                Text(title, style = MaterialTheme.typography.titleLarge)
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                Text("${summary.startDate.format(formatter)} – ${summary.endDate.format(formatter)}", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                if (coverage.firstReliableDay == null) {
                    Text("Earlier days predate reliable tracking. Rolling trends will appear as attendance is captured; no balance can be calculated yet.",
                        Modifier.testTag("${target.name}_coverage"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                } else {
                    DashboardMetric("Credited on covered days", minutesText(coverage.coveredCreditedMinutes), "${target.name}_credit")
                    DashboardMetric("Expected workdays with coverage", coverage.coveredExpectedWorkdays.toString(), "${target.name}_days")
                    DashboardMetric("Required on covered days since tracking began", minutesText(coverage.coveredRequiredMinutes.toDouble()), "${target.name}_required")
                }
                if (summary.creditedMinutes > coverage.coveredCreditedMinutes + 0.0001)
                    Text("${minutesText(summary.creditedMinutes - coverage.coveredCreditedMinutes)} recorded on dates without reliable coverage; review in History. Excluded from covered-day progress.",
                        Modifier.testTag("${target.name}_provisional_credit"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                DashboardMetric("Average on covered expected days", if (summary.hasCompleteHistory)
                    summary.averageMinutes?.let(::minutesText) ?: "No expected days" else "Unknown", "${target.name}_average")
                DashboardMetric("Balance", if (summary.hasCompleteHistory) balanceText(summary.balanceMinutes) else "Unknown", "${target.name}_balance", true)
                if (target == TargetWindow.WEEK_TO_DATE && summary.hasCompleteHistory) {
                    val full = AttendanceEngine.summary(input, result, TargetWindow.FULL_WEEK)
                    DashboardMetric("Full-week projected requirement", minutesText(full.requiredMinutes.toDouble()), "full_week_required")
                    Text("Includes ${full.projectedExpectedWorkdays} future expected workdays.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                }
                if (coverage.firstReliableDay != null && coverage.unavailableBeforeTracking.isNotEmpty()) {
                    val firstUnavailable = coverage.unavailableBeforeTracking.minOrNull()!!
                    val lastUnavailable = coverage.unavailableBeforeTracking.maxOrNull()!!
                    Text("${coverage.unavailableBeforeTracking.size} earlier dates outside reliable tracking (${firstUnavailable.format(formatter)} – ${lastUnavailable.format(formatter)}) are excluded; recorded days in that span count separately. No rolling balance can be calculated yet.",
                        Modifier.testTag("${target.name}_pretracking"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                }
                if (coverage.unknownAfterTracking.isNotEmpty()) {
                    val firstUnknown = coverage.unknownAfterTracking.minOrNull()!!
                    val lastUnknown = coverage.unknownAfterTracking.maxOrNull()!!
                    val dates = if (firstUnknown == lastUnknown) firstUnknown.format(formatter) else
                        "${firstUnknown.format(formatter)} – ${lastUnknown.format(formatter)}"
                    Text("${coverage.unknownAfterTracking.size} later calendar ${if (coverage.unknownAfterTracking.size == 1) "day has" else "days have"} unknown coverage ($dates); excluded from the requirement above. Balance and average remain Unknown.",
                        Modifier.testTag("${target.name}_unknown"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                }
            }
        }
        Text("Personal estimates. Geofence delivery can be delayed; arrival walking grace is an uncredited delay, not a confirmed building entry.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, tag: String, emphasis: Boolean = false) {
    Box(Modifier.testTag(tag).semantics(mergeDescendants = true) {}) { MetricRow(label, value, emphasis) }
}
