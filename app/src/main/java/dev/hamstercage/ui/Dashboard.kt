package dev.hamstercage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hamstercage.domain.*
import java.time.DayOfWeek
import java.time.Duration
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(input: AttendanceInput, result: AttendanceResult, trackingReady: Boolean, health: @Composable () -> Unit, openOffices: () -> Unit, openHistory: () -> Unit) {
    val today = input.now.atZone(input.policy.zoneId).toLocalDate()
    val daily = AttendanceEngine.daily(input,result,today)
    val open = result.sessions.filter { it.isOpen }
    val ambiguous = result.reviews.any { it.reason != ReviewReason.OPEN_SESSION && it.reason != ReviewReason.DUPLICATE_EVENT }
    val officeState = when {
        ambiguous -> "Needs review"
        open.isNotEmpty() -> "In ${open.mapNotNull { s -> input.offices.find { it.id == s.officeId }?.name }.distinct().joinToString(" + ")}"
        !trackingReady -> "Office state unknown"
        else -> "Outside office"
    }
    ScreenColumn {
        PageHeading("Your time, clearly.",today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Tag("LOCAL ONLY",true); Tag("PERSONAL RECORD") }
        Panel(warm=true) {
            Text("TODAY",style=MaterialTheme.typography.labelMedium,color=CageStyle.Amber)
            Text(officeState,style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(minutesText(daily.creditedMinutes),style=MaterialTheme.typography.displaySmall)
            }
            Text(if(daily.hasCompleteHistory) "Credited office time" else "Recorded credit · history incomplete",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            HorizontalDivider(color=CageStyle.Outline)
            MetricRow("Target today",minutesText(daily.requiredMinutes.toDouble()))
            MetricRow("Balance",if(daily.hasCompleteHistory) balanceText(daily.balanceMinutes) else "Unknown",true)
            MetricRow("Device-observed time",minutesText(AttendanceEngine.observedDailyMinutes(input,today)))
            open.mapNotNull { it.start }.minOrNull()?.let { MetricRow("Session started",instantText(it,input.policy.zoneId)) }
            if(open.any { it.manualSessionId != null }) Tag("ACTIVE MANUAL SESSION")
            if(!daily.hasCompleteHistory) Text("Missing coverage is unknown, never a confirmed absence. Review or add a missing session in History.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        }
        if(input.offices.isEmpty()) Notice("Set your first office", "Add your office location to enable automatic attendance. Westerville and Polaris are suggested names; no location is preloaded.","Add office",openOffices)
        else health()
        if(ambiguous) Notice("A session needs a second look","The original events are saved. Review the timeline before relying on departure estimates.","Review history",openHistory)
        Panel {
            Text("When can I leave?",style=MaterialTheme.typography.titleLarge)
            Text("Separate targets. Estimates include your office’s exit grace.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            listOf("Daily target" to TargetWindow.TODAY,"Week through today" to TargetWindow.WEEK_TO_DATE,"30-day target" to TargetWindow.ROLLING_30,"90-day target" to TargetWindow.ROLLING_90).forEach { (label,target) ->
                val estimate = AttendanceEngine.departure(input,result,target)
                MetricRow(label,when(estimate.status) {
                    DepartureStatus.TARGET_SATISFIED -> "Already satisfied"
                    DepartureStatus.ESTIMATED -> estimate.estimatedExitAt?.let { "About ${instantText(it,input.policy.zoneId)}" } ?: "Needs review"
                    DepartureStatus.NOT_IN_OFFICE -> "No active office session"
                    DepartureStatus.INCOMPLETE_HISTORY -> "History incomplete"
                    DepartureStatus.NEEDS_REVIEW -> "Needs review"
                    DepartureStatus.OUTSIDE_WINDOW -> "Outside target window"
                    DepartureStatus.UNREACHABLE_IN_WINDOW -> "Beyond this window"
                },estimate.status == DepartureStatus.ESTIMATED)
            }
        }
        Text("THE BIGGER PICTURE",Modifier.padding(top=12.dp),style=MaterialTheme.typography.labelMedium,color=CageStyle.Secondary)
        listOf("This week" to TargetWindow.WEEK_TO_DATE,"Rolling 30 days" to TargetWindow.ROLLING_30,"Rolling 90 days" to TargetWindow.ROLLING_90).forEach { (title,target) ->
            val summary = AttendanceEngine.summary(input,result,target)
            Panel {
                Text(title,style=MaterialTheme.typography.titleLarge)
                Text("${summary.startDate.format(DateTimeFormatter.ofPattern("MMM d"))} – ${summary.endDate.format(DateTimeFormatter.ofPattern("MMM d"))}",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                MetricRow("Credited",minutesText(summary.creditedMinutes))
                MetricRow("Expected workdays",summary.expectedWorkdays.toString())
                MetricRow("Required through today",minutesText(summary.requiredMinutes.toDouble()))
                MetricRow("Average / expected day",if(summary.hasCompleteHistory) summary.averageMinutes?.let(::minutesText) ?: "No expected days" else "Unknown")
                MetricRow("Balance",if(summary.hasCompleteHistory) balanceText(summary.balanceMinutes) else "Unknown",true)
                if(target == TargetWindow.WEEK_TO_DATE) {
                    val full=AttendanceEngine.summary(input,result,TargetWindow.FULL_WEEK)
                    MetricRow("Full-week requirement",minutesText(full.requiredMinutes.toDouble()))
                }
                if(!summary.hasCompleteHistory) Text("${summary.unknownExpectedWorkdays} expected ${if(summary.unknownExpectedWorkdays == 1) "day has" else "days have"} no reliable coverage.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            }
        }
        Text("Personal estimates, not official employer attendance. Geofence delivery can be delayed.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
    }
}

