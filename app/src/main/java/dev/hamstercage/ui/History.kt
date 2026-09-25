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
    val freshRecord = input.historyStartDate == null && input.events.isEmpty() &&
        input.manualSessions.isEmpty() && input.corrections.isEmpty()
    // A full privacy reset changes this key and discards stale paging/detail/browse state.
    var offsetDays by rememberSaveable(freshRecord) { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable(freshRecord) { mutableStateOf<String?>(null) }
    var editingSessionId by rememberSaveable(freshRecord) { mutableStateOf<String?>(null) }
    var addingManual by rememberSaveable(freshRecord) { mutableStateOf(false) }
    var browseBeforeTracking by rememberSaveable(freshRecord) { mutableStateOf(false) }
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
    val beforeTracking = days.filter { it.coverage == HistoryCoverage.BEFORE_TRACKING && "REVIEW" !in it.badges }
    val visibleDays = historyVisibleDays(days, browseBeforeTracking)
    val needsReview = historyNeedsReview(days)
    val recordReviewDays = needsReview.count { "REVIEW" in it.badges }
    val missingCoverageDays = needsReview.count { "REVIEW" !in it.badges }
    val hasAttendance = input.events.isNotEmpty() || input.manualSessions.isNotEmpty() || input.corrections.isNotEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Text("Every total comes from your local record.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        if (freshRecord)
            Notice("No attendance recorded yet", "History begins when attendance is captured. Earlier dates were not tracked and need no review.")
        if (needsReview.isNotEmpty()) {
            val alert = when {
                recordReviewDays > 0 && missingCoverageDays > 0 ->
                    "${needsReview.size} days need attention: saved attendance records need review, and some days have missing coverage."
                recordReviewDays > 0 ->
                    "${needsReview.size} ${if (needsReview.size == 1) "day has a saved attendance record" else "days have saved attendance records"} to review."
                else -> "${needsReview.size} ${if (needsReview.size == 1) "day has" else "days have"} missing attendance coverage."
            }
            Text(alert,
                Modifier.testTag("history_review_alert"), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { selectedDay = needsReview.first().date.toString() },
                modifier = Modifier.fillMaxWidth().testTag("history_review_action")) { Text("Review ${needsReview.first().date}") }
        }
        if (correctionActions != null) {
            Text("Optional: add attendance you remember. This does not imply that other earlier days were captured.",
                style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            OutlinedButton(onClick = { addingManual = true }) { Text("Add manual attendance") }
        }
        if (visibleDays.isNotEmpty())
            Text("${days.last().date} – ${days.first().date} · ${input.policy.zoneId.id}", Modifier.testTag("history_range"), style = MaterialTheme.typography.bodyMedium)
        if (days.any { it.coverage == HistoryCoverage.UNKNOWN_AFTER_TRACKING })
            Text("Recorded credit is provisional on days with unknown coverage. A balance is shown only for complete history.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        if (beforeTracking.isNotEmpty() && !browseBeforeTracking)
            OutlinedButton(onClick = { browseBeforeTracking = true }, modifier = Modifier.fillMaxWidth().testTag("history_browse_before")) {
                Text("Browse ${beforeTracking.size} earlier ${if (beforeTracking.size == 1) "date" else "dates"} before tracking")
            }
        if (browseBeforeTracking && beforeTracking.isNotEmpty())
            Text("Before tracking · ordinary dates were not captured and need no review. Saved attendance records that need review stay visible. Add attendance only if you choose.",
                Modifier.testTag("history_before_explanation"), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        if (offsetDays > 0) OutlinedButton(onClick = { offsetDays = (offsetDays - HISTORY_PAGE_DAYS).coerceAtLeast(0) }, modifier = Modifier.fillMaxWidth()) { Text("Newer 14 days") }
        visibleDays.forEach { day ->
            Panel {
                Text(day.date.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")), Modifier.testTag("history_date_${day.date}"), style = MaterialTheme.typography.titleMedium)
                // Vertical badges remain readable at large font sizes and never rely on color.
                day.badges.forEach { Tag(it, warm = it == "REVIEW") }
                if (day.coverage == HistoryCoverage.BEFORE_TRACKING) {
                    Text(if ("REVIEW" in day.badges)
                        "Tracking had not begun for this date. A saved attendance record needs review."
                    else "No attendance was captured for this date. No attendance coverage review is required.",
                        Modifier.testTag("history_before_message_${day.date}"),
                        style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
                } else {
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
                }
                OutlinedButton(onClick = { selectedDay = day.date.toString() }) { Text("Explain ${day.date}") }
            }
        }
        if (days.last().date > earliest && offsetDays <= Int.MAX_VALUE - HISTORY_PAGE_DAYS)
            OutlinedButton(onClick = { offsetDays += HISTORY_PAGE_DAYS }, modifier = Modifier.fillMaxWidth()) { Text("Earlier 14 days") }
    }
}
