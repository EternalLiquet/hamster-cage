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
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean = false,
    openOffices: () -> Unit = {}, openHistory: () -> Unit = {},
) {
    val today = input.now.atZone(input.policy.zoneId).toLocalDate()
    val daily = AttendanceEngine.daily(input, result, today)
    val presence = dashboardPresence(input, result, trackingReady)
    Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Panel(warm = true) {
            Text("TODAY", style = MaterialTheme.typography.labelMedium, color = CageStyle.Amber)
            Text(presence.label, Modifier.testTag("office_state"), style = MaterialTheme.typography.titleMedium)
            Text(minutesText(daily.creditedMinutes), Modifier.fillMaxWidth().testTag("today_credit"), style = MaterialTheme.typography.displaySmall)
            Text(if (daily.hasCompleteHistory) "Credited office time" else "Provisional credit · coverage incomplete",
                style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            DashboardMetric("Target today", minutesText(daily.requiredMinutes.toDouble()), "today_target")
            DashboardMetric("Balance", if (daily.hasCompleteHistory) balanceText(daily.balanceMinutes) else "Unknown", "today_balance", true)
            DashboardMetric("Device-observed time", minutesText(AttendanceEngine.observedDailyMinutes(input, today)), "today_observed")
            presence.sessionStarted?.let { DashboardMetric("Session started", instantText(it, input.policy.zoneId), "session_start") }
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
            Text("Projections include office exit grace; they do not add future attendance.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
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
                }
                DashboardMetric(estimate.targetName, value, "${target.name}_departure")
            }
        }
        Text("THE BIGGER PICTURE", style = MaterialTheme.typography.labelMedium, color = CageStyle.Secondary)
        listOf("This week" to TargetWindow.WEEK_TO_DATE, "Rolling 30 days" to TargetWindow.ROLLING_30,
            "Rolling 90 days" to TargetWindow.ROLLING_90).forEach { (title, target) ->
            val summary = AttendanceEngine.summary(input, result, target)
            Panel {
                Text(title, style = MaterialTheme.typography.titleLarge)
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                Text("${summary.startDate.format(formatter)} – ${summary.endDate.format(formatter)}", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                DashboardMetric("Credited", minutesText(summary.creditedMinutes), "${target.name}_credit")
                DashboardMetric("Expected workdays", summary.expectedWorkdays.toString(), "${target.name}_days")
                DashboardMetric("Required through today", minutesText(summary.requiredMinutes.toDouble()), "${target.name}_required")
                DashboardMetric("Average / expected day", if (summary.hasCompleteHistory)
                    summary.averageMinutes?.let(::minutesText) ?: "No expected days" else "Unknown", "${target.name}_average")
                DashboardMetric("Balance", if (summary.hasCompleteHistory) balanceText(summary.balanceMinutes) else "Unknown", "${target.name}_balance", true)
                if (target == TargetWindow.WEEK_TO_DATE) {
                    val full = AttendanceEngine.summary(input, result, TargetWindow.FULL_WEEK)
                    DashboardMetric("Full-week projected requirement", minutesText(full.requiredMinutes.toDouble()), "full_week_required")
                    Text("Includes ${full.projectedExpectedWorkdays} future expected workdays.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                }
                if (!summary.hasCompleteHistory)
                    Text("${summary.unknownCalendarDays} calendar days have no reliable coverage.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            }
        }
        Text("Personal estimates. Geofence delivery can be delayed; grace is a policy allowance, not an observed crossing.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, tag: String, emphasis: Boolean = false) {
    Box(Modifier.testTag(tag).semantics(mergeDescendants = true) {}) { MetricRow(label, value, emphasis) }
}
