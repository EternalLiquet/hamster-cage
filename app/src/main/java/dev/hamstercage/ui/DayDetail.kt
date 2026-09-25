package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.hamstercage.domain.*
import dev.hamstercage.data.RecordedEvent
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DayDetailScreen(input: AttendanceInput, result: AttendanceResult, date: LocalDate, back: () -> Unit,
    edit: ((Session) -> Unit)? = null, eventEvidence: List<RecordedEvent> = emptyList(),
    backLabel: String = "Back to daily history", trackingReady: Boolean = true) {
    val detail = remember(input, result, date) { explainDay(input, result, date) }
    val timeline = remember(input, detail) { dayTimeline(input, detail) }
    val beforeTracking = remember(input, result, date) {
        date in AttendanceEngine.reportingCoverage(input, result, date, date).unavailableBeforeTracking
    }
    val evidenceById = remember(eventEvidence) { eventEvidence.associateBy { it.event.id } }
    val offices = input.offices.associateBy { it.id }
    fun at(time: Instant?) = time?.atZone(input.policy.zoneId)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: "Missing boundary"
    fun office(id: String) = evidenceText(offices[id]?.name ?: "Unknown office")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        OutlinedButton(onClick = back) { Text(backLabel) }
        Text("Explain $date", style = MaterialTheme.typography.headlineSmall)
        Text("${input.policy.zoneId.id} · evaluated ${at(input.now)}", style = MaterialTheme.typography.bodyMedium)
        Panel {
            Text("Recorded credit: ${minutesText(detail.summary.creditedMinutes)}", Modifier.testTag("detail_credit"), style = MaterialTheme.typography.titleLarge)
            Text("${if (detail.sessions.any { ReviewReason.UNCONFIRMED_GAP in it.reviewReasons }) "Confirmed in-zone time" else "Device-observed time"}: ${minutesText(AttendanceEngine.observedDailyMinutes(input, date))}", Modifier.testTag("detail_observed"))
            if (beforeTracking) {
                Text("Before tracking began. No attendance requirement or balance applies to this date.",
                    Modifier.testTag("detail_before_tracking"))
                if (detail.reviews.isNotEmpty()) Text("A saved attendance record for this date needs review. The evidence is below.")
            } else {
                Text("Required: ${minutesText(detail.summary.requiredMinutes.toDouble())}", Modifier.testTag("detail_required"))
                Text(detail.denominator)
                if (!detail.summary.hasCompleteHistory) Notice("Unknown coverage", "The recorded credit is provisional. Missing history is not proof of zero attendance.")
                Text("These are the same daily engine totals used by Dashboard and History. Overlapping intervals count only once.")
            }
        }
        if (date == input.now.atZone(input.policy.zoneId).toLocalDate() && !beforeTracking) {
            val departure = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
            Panel {
                Text(todayLeaveText(departure, trackingReady, input.now, input.policy.zoneId),
                    Modifier.testTag("detail_leave"), style = MaterialTheme.typography.titleMedium)
                Text("Today's ${minutesText(detail.summary.requiredMinutes.toDouble())} target. The estimate can change as observations arrive; office-area exit and building exit may differ.")
            }
        }
        Text("Office-area timeline", style = MaterialTheme.typography.titleLarge)
        if (timeline.isEmpty()) Text(if (beforeTracking)
            "Tracking had not begun on this date. No attendance was expected from the app's record."
            else "No office-area session was recorded for this date. Missing coverage is unknown, not confirmed absence.",
            Modifier.testTag("day_timeline_empty"))
        val offeredEdit = mutableSetOf<String>()
        timeline.forEachIndexed { index, row ->
            Panel {
                Text("${instantText(row.at, input.policy.zoneId)} · ${row.title}",
                    Modifier.testTag("day_timeline_$index"), style = MaterialTheme.typography.titleMedium)
                Text(row.detail)
                val session = detail.sessions.find { it.id == row.sessionId }
                if (session != null && session.reviewReasons.any { it != ReviewReason.OPEN_SESSION && it != ReviewReason.DUPLICATE_EVENT })
                    Text("This session needs review: ${session.reviewReasons.filter { it != ReviewReason.OPEN_SESSION }.joinToString { it.name.replace('_', ' ').lowercase() }}.")
                if (session != null && offeredEdit.add(session.id)) edit?.let { action ->
                    OutlinedButton(onClick = { action(session) }, modifier = Modifier.testTag("timeline_edit_${session.id}")) {
                        Text("Review or correct this session")
                    }
                }
            }
        }
        EvidenceSection("Credited intervals", detail.intervals) { interval ->
            Text("${at(interval.start)} → ${at(interval.end)}")
            Text("${minutesText(interval.minutes)} · sessions ${interval.sessionIds.sorted().joinToString(transform = ::evidenceId)}")
            if (interval.reconciledGap) Text("Includes a same-office gap reconciled by the ${input.policy.shortGapMinutes}-minute policy.")
            Text("Clipped to this policy-local day after uncredited arrival delay, gap reconciliation and overlap union.")
        }
        EvidenceSection("Reconstructed sessions", detail.sessions) { session ->
            Text("${office(session.officeId)} · ${evidenceId(session.id)}", style = MaterialTheme.typography.titleMedium)
            val original = detail.originalSessions.find { it.id == session.id }
            Text("Original bounds: ${at(original?.start)} → ${at(original?.end)}")
            if (original?.reviewReasons?.contains(ReviewReason.UNCONFIRMED_GAP) == true)
                Text("The listed end is a later current-location check, not an observed EXIT. The exit time is unknown, and this earlier segment earns no credit until corrected.")
            if (session.correctionId != null && !session.correctionReverted) original?.reviewReasons?.forEach {
                Text("Original evidence: ${reviewExplanation(it)} The applied correction supplies effective bounds.")
            }
            Text("Effective bounds: ${at(session.start)} → ${at(session.end)}")
            if (original?.manualSessionId == null) {
                if (original?.start != null)
                    Text(if (ReviewReason.UNCONFIRMED_GAP in original.reviewReasons)
                        "Original elapsed span between opening and later current-location check (continuity unconfirmed): ${minutesText(AttendanceEngine.observedMinutes(original, input.now))}"
                        else "Device-observed in-zone time: ${minutesText(AttendanceEngine.observedMinutes(original, input.now))}")
                else Text("Device-observed in-zone duration unknown: no ENTER was recorded.")
            } else Text("Original manually entered duration: ${minutesText(AttendanceEngine.observedMinutes(original, input.now))}")
            Text(if (ReviewReason.UNCONFIRMED_GAP in session.reviewReasons)
                "Effective elapsed span (continuity unconfirmed): ${minutesText(AttendanceEngine.observedMinutes(session, input.now))}."
                else "Effective session duration: ${minutesText(AttendanceEngine.observedMinutes(session, input.now))}${if (session.correctionId != null && !session.correctionReverted) " after correction" else ""}.")
            if (session.isOpen) Text("Open: evaluated through ${at(input.now)}; no future EXIT is assumed.")
            offices[session.officeId]?.let { value ->
                session.start?.let { start ->
                    val creditStart = start.plusSeconds(value.entryGraceMinutes * 60L)
                    Text("Arrival walking grace: ${value.entryGraceMinutes}m uncredited. Credit starts at ${at(creditStart)}.")
                    if (session.isOpen && input.now < creditStart) {
                        val minutesLeft = (Duration.between(input.now, creditStart).seconds + 59) / 60
                        Text("${minutesLeft}m until credit starts if the observed visit continues.")
                    }
                    if (session.end?.let { it <= creditStart } == true) Text("Visit ended before arrival grace; 0m credited for this visit.")
                }
                Text("Exit/departure grace: ${value.exitGraceMinutes}m for departure projections only; it adds no recorded credit.")
                if (!value.enabled || !value.countsTowardAttendance) Text("This office is disabled or excluded from attendance credit.")
            }
            Text("Confidence: ${session.confidence.name}. Source events: ${session.sourceEventIds.sorted().joinToString(transform = ::evidenceId).ifEmpty { "Manual interval" }}")
            session.correctionId?.let { Text(if (session.correctionReverted) "Reverted to original by audit entry: ${evidenceId(it)}."
                else "Applied correction: ${evidenceId(it)}. Original evidence is retained below.") }
            session.reviewReasons.forEach { Text(reviewExplanation(it)) }
            edit?.let { action -> OutlinedButton(onClick = { action(session) }) { Text("Correct ${evidenceId(session.id)}") } }
        }
        EvidenceSection("Raw observations", detail.rawEvents) { event ->
            val currentFix = evidenceById[event.id]?.source in setOf("FOREGROUND_LOCATION_RECONCILIATION", "BACKGROUND_LOCATION_RECONCILIATION")
            Text("${when {
                currentFix && event.transition == Transition.ABSENCE -> "Current-location outside"
                currentFix && event.transition == Transition.PRESENCE -> "Current-location presence"
                else -> event.transition.name
            }} · ${office(event.officeId)} · ${at(event.at)}")
            if (currentFix) Text(if (event.transition == Transition.ABSENCE)
                "One-shot precise fix establishes outside at this check. The exit time is unknown; the old span earns no credit until reviewed."
                else "One-shot precise fix. The session starts at this observation, not at a guessed arrival.")
            Text("Source event: ${evidenceId(event.id)}", style = MaterialTheme.typography.bodySmall)
        }
        EvidenceSection("Manual source intervals", detail.manualSessions) { manual ->
            Text("${office(manual.officeId)} · ${evidenceId(manual.id)}")
            Text("${at(manual.start)} → ${at(manual.end)} · entered ${at(manual.createdAt)}")
            if (manual.note.isNotBlank()) Text(evidenceText(manual.note))
        }
        EvidenceSection("Correction audit", detail.corrections) { correction ->
            Text("${evidenceId(correction.id)} → ${evidenceId(correction.sessionId)}")
            if (correction.revertToOriginal) Text("Revert to original reconstruction · entered ${at(correction.createdAt)}")
            else Text("${at(correction.start)} → ${at(correction.end)} · entered ${at(correction.createdAt)}")
            Text(if (detail.sessions.any { it.correctionId == correction.id })
                if (correction.revertToOriginal) "Currently restores original reconstruction." else "Applied to effective bounds."
                else "Retained audit entry; not the currently applied correction.")
            if (correction.note.isNotBlank()) Text(evidenceText(correction.note))
        }
        EvidenceSection("Review explanations", detail.reviews) { review ->
            Text(review.reason.name.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
            Text(reviewExplanation(review.reason))
            Text("Session: ${review.sessionId?.let(::evidenceId) ?: "Unlinked"}; events: ${review.sourceEventIds.sorted().joinToString(transform = ::evidenceId).ifEmpty { "None" }}")
        }
    }
}

@Composable
private fun <T> EvidenceSection(title: String, entries: List<T>, row: @Composable (T) -> Unit) {
    var offset by rememberSaveable(title) { mutableIntStateOf(0) }
    val page = offset.coerceAtMost(((entries.size - 1).coerceAtLeast(0) / 10) * 10)
    Text("$title (${entries.size})", style = MaterialTheme.typography.titleLarge)
    if (entries.isEmpty()) Text("No entries for this day.", color = CageStyle.Secondary)
    if (page > 0) TextButton(onClick = { offset = page - 10 }) { Text("Previous $title") }
    entries.drop(page).take(10).forEach { entry -> Panel { row(entry) } }
    if (page + 10 < entries.size) TextButton(onClick = { offset = page + 10 }) { Text("More $title") }
}
