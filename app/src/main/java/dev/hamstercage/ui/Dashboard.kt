package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import dev.hamstercage.domain.AttendanceEngine
import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.AttendanceResult
import dev.hamstercage.domain.DepartureStatus
import dev.hamstercage.domain.TargetWindow
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Dashboard reading order (#119), which is also the TalkBack order because everything sits in one column:
 * 1. Today: credited time, what is still needed, the leave status, current-visit state and one primary action.
 * 2. Setup/review notices, whose button becomes the primary action when they apply.
 * 3. Collapsed by default: all departure targets, then week/30/90-day detail. Expanding shows the unchanged
 *    values and warnings; nothing in the Today group depends on longer-term history being known.
 */
@Composable
fun DashboardScreen(
    input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean = false,
    openOffices: () -> Unit = {}, openHistory: () -> Unit = {}, openToday: () -> Unit = {},
) {
    val today = input.now.atZone(input.policy.zoneId).toLocalDate()
    val daily = AttendanceEngine.daily(input, result, today)
    val presence = dashboardPresence(input, result, trackingReady)
    val todayDeparture = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
    val currentOffice = result.sessions.singleOrNull { session -> session.isOpen &&
        input.offices.any { it.id == session.officeId && it.enabled && it.countsTowardAttendance }
    }?.let { session -> input.offices.single { it.id == session.officeId } }
    val noEligibleOffice = input.offices.none { it.enabled && it.countsTowardAttendance }
    // A setup or review notice carries the next step when it applies; otherwise the timeline is the next step.
    val noticeLeads = noEligibleOffice || !trackingReady || presence.needsReview
    Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Panel(warm = true) {
            // Default view answers three questions: how much counts, how much is left, and what to do next.
            // Technical detail stays one tap away. Uncertain numbers are always labelled as estimates.
            val liveUnconfirmed = !trackingReady && presence.sessionStarted != null
            val confident = todayLeaveConfident(input, result, trackingReady)
            Text("TODAY", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium, color = CageStyle.Amber)
            Column(verticalArrangement = Arrangement.spacedBy(CageStyle.Tight)) {
                Text(minutesText(daily.creditedMinutes), Modifier.fillMaxWidth().testTag("today_credit"), style = MaterialTheme.typography.displaySmall)
                Text(todayCreditNote(daily.hasCompleteHistory, liveUnconfirmed), Modifier.testTag("today_credit_note"),
                    style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(CageStyle.Small)) {
                DashboardMetric("Still needed", todayRemainingText(daily, liveUnconfirmed), "today_balance", true)
                DashboardMetric("Today's goal", minutesText(daily.requiredMinutes.toDouble()), "today_target")
            }
            Text(todayLeaveText(todayDeparture, trackingReady, input.now, input.policy.zoneId, confident),
                Modifier.fillMaxWidth().testTag("today_leave"), style = MaterialTheme.typography.titleLarge)
            if (!daily.hasCompleteHistory)
                Text("Part of today wasn't tracked, so we can't tell whether you were at the office then. Check today's timeline before relying on these numbers.",
                    Modifier.testTag("today_untracked"), style = MaterialTheme.typography.bodyMedium)
            val open = result.sessions.filter { it.isOpen && input.offices.any { office -> office.id == it.officeId && office.enabled && office.countsTowardAttendance } }
            val grace = open.singleOrNull()?.let { session -> input.offices.single { it.id == session.officeId }.entryGraceMinutes }
            val creditStart = open.singleOrNull()?.let { it.start!!.plusSeconds((grace ?: 0) * 60L) }
            Column(verticalArrangement = Arrangement.spacedBy(CageStyle.Tight)) {
                Text(presence.label, Modifier.testTag("office_state"), style = MaterialTheme.typography.titleMedium)
                if (creditStart != null) {
                    val counting = input.now >= creditStart
                    Text(if (counting) "Counting since ${instantText(creditStart, input.policy.zoneId)}."
                        else "Time starts counting at ${instantText(creditStart, input.policy.zoneId)}.",
                        Modifier.testTag("arrival_credit_start"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                    if (!counting) {
                        val minutesLeft = (Duration.between(input.now, creditStart).seconds + 59) / 60
                        Text("${minutesLeft}m until time starts counting, if you stay.", Modifier.testTag("arrival_countdown"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (presence.manual) Tag("INCLUDES TIMES YOU ENTERED")
            val timelineModifier = Modifier.fillMaxWidth().testTag("open_today_timeline")
            if (noticeLeads) TextButton(onClick = openToday, modifier = timelineModifier.defaultMinSize(minHeight = CageStyle.TouchTarget)) {
                Text("View today's timeline", textAlign = TextAlign.Center)
            } else CageButton("View today's timeline", openToday, timelineModifier)
            var details by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().testTag("today_details_toggle")
                .defaultMinSize(minHeight = CageStyle.TouchTarget)
                .semantics { stateDescription = if (details) "Expanded" else "Collapsed" }) {
                Text(if (details) "Hide details" else "How is this worked out?", textAlign = TextAlign.Center)
            }
            if (details) Column(Modifier.testTag("today_details"), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
                DashboardMetric("Time your phone saw you at the office", minutesText(AttendanceEngine.observedDailyMinutes(input, today)), "today_observed")
                presence.sessionStarted?.let { DashboardMetric("Arrival noticed at", instantText(it, input.policy.zoneId), "session_start") }
                if (grace != null)
                    Text("The first ${grace}m after arriving don't count, to allow for walking in.",
                        style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                if (todayDeparture.status == DepartureStatus.ESTIMATED && trackingReady && currentOffice != null)
                    Text("The leave time is when to be outside the office area. It's up to ${currentOffice.exitGraceMinutes}m " +
                        "before your counted time reaches the goal, to give you time to walk out. Those minutes don't count as office time.",
                        Modifier.testTag("today_leave_boundary"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                Text("Times come from when your phone notices you arriving at or leaving the office area. Phones can be a few minutes " +
                    "late to notice, so treat these times as close estimates. Today's numbers may change as your records are checked.",
                    style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            }
        }
        if (noEligibleOffice)
            Notice("Set up an eligible office", "Add or enable an office to begin automatic attendance setup. No location is preloaded.", "Open office setup", openOffices)
        else if (!trackingReady)
            Notice("Detection is not confirmed", "Review office setup and location permissions. A configured zone alone does not prove you are outside.", "Open office setup", openOffices)
        // When setup and review notices overlap, fixing tracking setup is the one primary action; review stays secondary.
        if (presence.needsReview)
            Notice("A session needs review", "Original observations are retained. Review uncertain boundaries before relying on these estimates.",
                "Review history", openHistory, primaryAction = !(noEligibleOffice || !trackingReady))
        // Other days' reviews stay reachable but never lead or question today's numbers.
        else if (presence.otherReview)
            Notice("Other records to check", "Today's numbers don't depend on them, but week and 30- or 90-day totals may.",
                "Review history", openHistory, primaryAction = false)
        if (input.events.isEmpty() && input.manualSessions.isEmpty())
            Text("No attendance recorded yet.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        Panel {
            var targets by rememberSaveable { mutableStateOf(false) }
            Text("Departure targets", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            SectionToggle(if (targets) "Hide all departure targets" else "Show all departure targets",
                targets, "departure_targets_toggle") { targets = !targets }
            if (targets) Column(Modifier.testTag("departure_targets"), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
                Text("Departure estimates may use office exit grace; it never adds recorded attendance.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                TargetWindow.entries.forEach { target ->
                    val estimate = AttendanceEngine.departure(input, result, target)
                    DashboardMetric(estimate.targetName, departureTargetText(estimate, trackingReady, input.now,
                        input.policy.zoneId), "${target.name}_departure")
                }
            }
        }
        var periods by rememberSaveable { mutableStateOf(false) }
        Panel {
            Text("THE BIGGER PICTURE", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium, color = CageStyle.Secondary)
            Text("This week, rolling 30 days and rolling 90 days", style = MaterialTheme.typography.titleMedium)
            SectionToggle(if (periods) "Hide week, 30-day and 90-day details" else "Show week, 30-day and 90-day details",
                periods, "periods_toggle") { periods = !periods }
        }
        if (periods) Column(Modifier.testTag("periods"), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
            listOf("This week" to TargetWindow.WEEK_TO_DATE, "Rolling 30 days" to TargetWindow.ROLLING_30,
                "Rolling 90 days" to TargetWindow.ROLLING_90).forEach { (title, target) -> PeriodCard(input, result, title, target) }
        }
        Text("Personal estimates. Geofence delivery can be delayed; arrival walking grace is an uncredited delay, not a confirmed building entry.",
            style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
    }
}

@Composable
private fun PeriodCard(input: AttendanceInput, result: AttendanceResult, title: String, target: TargetWindow) {
    val summary = AttendanceEngine.summary(input, result, target)
    val coverage = AttendanceEngine.reportingCoverage(input, result, summary.startDate, summary.endDate)
    Panel {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
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

/** A full-width, wrapping show/hide control whose expanded state is announced to accessibility services. */
@Composable
private fun SectionToggle(label: String, expanded: Boolean, tag: String, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag(tag)
        .defaultMinSize(minHeight = CageStyle.TouchTarget)
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, tag: String, emphasis: Boolean = false) {
    Box(Modifier.testTag(tag).semantics(mergeDescendants = true) {}) { MetricRow(label, value, emphasis) }
}
