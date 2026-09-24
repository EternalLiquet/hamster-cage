package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import dev.hamstercage.domain.AttendanceInput
import dev.hamstercage.domain.AttendanceResult
import dev.hamstercage.data.RecordedEvent
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.Duration

@Composable
fun HistoryScreen(input: AttendanceInput, result: AttendanceResult, correctionActions: CorrectionActions? = null,
    eventEvidence: List<RecordedEvent> = emptyList()) {
    var offsetDays by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingManual by rememberSaveable { mutableStateOf(false) }
    if (correctionActions != null && (editingSessionId != null || addingManual)) {
        val session = result.sessions.find { it.id == editingSessionId }
        if (addingManual || session != null) {
            CorrectionEditor(input, session, correctionActions) { editingSessionId = null; addingManual = false }
            return
        }
    }
    selectedDay?.let { day ->
        DayDetailScreen(input, result, LocalDate.parse(day), back = { selectedDay = null },
            edit = if (correctionActions == null) null else { session -> editingSessionId = session.id },
            eventEvidence = eventEvidence)
        return
    }
    val days = remember(input, result, offsetDays) { historyDays(input, result, offsetDays) }
    val earliest = remember(input) { earliestHistoryDate(input) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Text("Every total comes from your local record.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        if (correctionActions != null) OutlinedButton(onClick = { addingManual = true }) { Text("Add manual attendance") }
        Text("${days.last().date} – ${days.first().date} · ${input.policy.zoneId.id}", Modifier.testTag("history_range"), style = MaterialTheme.typography.bodyMedium)
        if (input.events.isEmpty() && input.manualSessions.isEmpty())
            Notice("No attendance recorded yet", "Expected days still appear below. Unknown coverage is not proof that you were absent.")
        if (days.any { !it.summary.hasCompleteHistory })
            Text("Recorded credit is provisional on days with unknown coverage. A balance is shown only for complete history.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        if (offsetDays > 0) OutlinedButton(onClick = { offsetDays = (offsetDays - HISTORY_PAGE_DAYS).coerceAtLeast(0) }, modifier = Modifier.fillMaxWidth()) { Text("Newer 14 days") }
        days.forEach { day ->
            Panel {
                Text(day.date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")), Modifier.testTag("history_date_${day.date}"), style = MaterialTheme.typography.titleMedium)
                // Vertical badges remain readable at large font sizes and never rely on color.
                day.badges.forEach { Tag(it, warm = it == "REVIEW") }
                Column(Modifier.testTag("history_credit_${day.date}").semantics(mergeDescendants = true) {}) {
                    MetricRow("Recorded credit", minutesText(day.summary.creditedMinutes))
                }
                MetricRow("Device-observed time", minutesText(day.observedMinutes))
                if (day.date == input.now.atZone(input.policy.zoneId).toLocalDate()) {
                    val active = result.sessions.filter { it.isOpen && input.offices.any { office -> office.id == it.officeId && office.enabled && office.countsTowardAttendance } }
                    if (active.size == 1) {
                        val session = active.single()
                        val grace = input.offices.single { it.id == session.officeId }.entryGraceMinutes
                        val creditStart = session.start!!.plusSeconds(grace * 60L)
                        Text("Arrival walking grace: ${grace}m uncredited. Credit starts at ${instantText(creditStart, input.policy.zoneId)}.")
                        if (input.now < creditStart) {
                            val minutesLeft = (Duration.between(input.now, creditStart).seconds + 59) / 60
                            Text("${minutesLeft}m until credit starts if the observed visit continues.")
                        }
                    }
                }
                Column(Modifier.testTag("history_required_${day.date}").semantics(mergeDescendants = true) {}) {
                    MetricRow("Required", minutesText(day.summary.requiredMinutes.toDouble()))
                }
                Column(Modifier.testTag("history_balance_${day.date}").semantics(mergeDescendants = true) {}) {
                    MetricRow("Balance", if (day.summary.hasCompleteHistory) balanceText(day.summary.balanceMinutes) else "Unknown", true)
                }
                OutlinedButton(onClick = { selectedDay = day.date.toString() }) { Text("Explain ${day.date}") }
            }
        }
        if (days.last().date > earliest && offsetDays <= Int.MAX_VALUE - HISTORY_PAGE_DAYS)
            OutlinedButton(onClick = { offsetDays += HISTORY_PAGE_DAYS }, modifier = Modifier.fillMaxWidth()) { Text("Earlier 14 days") }
    }
}
