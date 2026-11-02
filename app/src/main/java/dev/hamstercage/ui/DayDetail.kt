package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    backLabel: String = "Back to daily history", trackingReady: Boolean = true,
    prepareDayShare: (() -> DayDiagnostics)? = null, shareDay: ((DayDiagnostics) -> Unit)? = null,
    shareError: String? = null, saveDay: ((DayDiagnostics) -> Unit)? = null, shareNotice: String? = null) {
    var sharePreview by remember(date) { mutableStateOf<DayDiagnostics?>(null) }
    val detail = remember(input, result, date) { explainDay(input, result, date) }
    val timeline = remember(input, detail) { dayTimeline(input, detail) }
    val beforeTracking = remember(input, result, date) {
        date in AttendanceEngine.reportingCoverage(input, result, date, date).unavailableBeforeTracking
    }
    val evidenceById = remember(eventEvidence) { eventEvidence.associateBy { it.event.id } }
    val offices = input.offices.associateBy { it.id }
    fun at(time: Instant?) = time?.atZone(input.policy.zoneId)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) ?: "Not recorded"
    fun office(id: String) = evidenceText(offices[id]?.name ?: "Unknown office")
    fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        OutlinedButton(onClick = back) { Text(backLabel) }
        Text("Explain $date", style = MaterialTheme.typography.headlineSmall)
        Text("Times in ${input.policy.zoneId.id} · as of ${DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(input.policy.zoneId).format(input.now)}",
            style = MaterialTheme.typography.bodyMedium)
        if (prepareDayShare != null && shareDay != null) {
            OutlinedButton(onClick = { sharePreview = prepareDayShare() }, modifier = Modifier.testTag("share_day_data")) {
                Text("Share this day's data")
            }
            sharePreview?.let { prepared ->
                AlertDialog(onDismissRequest = { sharePreview = null },
                    title = { Text("Share $date?") },
                    text = { Text("${prepared.observationCount} observations, ${prepared.contextCount} nearby or linked context, ${prepared.registrationCount} geofence registration attempts and ${prepared.registrationContextCount} earlier registration attempts, ${prepared.registrationCycleCount} registration cycles and ${prepared.registrationCycleContextCount} earlier cycle context, ${prepared.boundaryCheckCount} recorded boundary check attempts. Evidence dates span ${prepared.firstIncludedDay} through ${prepared.lastIncludedDay}; includes observation, registration, boundary check and edit times with engine classifications, location-fix age and provider, manual intervals, office settings, the day's policy and result, app version, capture status within that span, and when this file was prepared. Office names, coordinates, notes and error messages are left out. " +
                        if (saveDay == null) "Choose where to send it next."
                        else "Share it to an app, or save a copy to a location you choose. A saved copy is outside Hamster Cage: deleting history here won't remove it, and a location that syncs will upload it.") },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(CageStyle.Small)) {
                            if (saveDay != null) TextButton(onClick = { sharePreview = null; saveDay(prepared) },
                                modifier = Modifier.testTag("save_day_data")) { Text("Save to file") }
                            TextButton(onClick = { sharePreview = null; shareDay(prepared) },
                                modifier = Modifier.testTag("share_day_choose_app")) { Text("Choose app") }
                        }
                    },
                    dismissButton = { TextButton(onClick = { sharePreview = null }) { Text("Cancel") } })
            }
            shareError?.let { FormError(it) }
            shareNotice?.let { Text(it, Modifier.testTag("share_day_notice"), style = MaterialTheme.typography.bodyMedium) }
        }
        Panel {
            Text("Recorded credit: ${minutesText(detail.summary.creditedMinutes)}", Modifier.testTag("detail_credit"), style = MaterialTheme.typography.titleLarge)
            Text("${if (detail.sessions.any { ReviewReason.UNCONFIRMED_GAP in it.reviewReasons }) "Confirmed time in office area" else "Time your phone recorded"}: ${minutesText(AttendanceEngine.observedDailyMinutes(input, date))}", Modifier.testTag("detail_observed"))
            if (beforeTracking) {
                Text("Before tracking began. No attendance requirement or balance applies to this date.",
                    Modifier.testTag("detail_before_tracking"))
                if (detail.reviews.isNotEmpty()) Text("A saved attendance record for this date needs review. The evidence is below.")
            } else {
                Text("Required: ${minutesText(detail.summary.requiredMinutes.toDouble())}", Modifier.testTag("detail_required"))
                Text(detail.denominator)
                if (!detail.summary.hasCompleteHistory) Notice("Some history is missing", "This credit is provisional. Missing history does not mean you were absent.")
                Text("Dashboard and History use these same totals. Overlapping time counts once.")
            }
        }
        if (date == input.now.atZone(input.policy.zoneId).toLocalDate() && !beforeTracking) {
            val departure = AttendanceEngine.departure(input, result, TargetWindow.TODAY)
            Panel {
                Text(todayLeaveText(departure, trackingReady, input.now, input.policy.zoneId, todayLeaveConfident(input, result, trackingReady)),
                    Modifier.testTag("detail_leave"), style = MaterialTheme.typography.titleMedium)
                Text("Today's target is ${minutesText(detail.summary.requiredMinutes.toDouble())}. The estimate can change as new records arrive, and leaving the office area may not match leaving the building.")
            }
        }
        Text("Office-area timeline", style = MaterialTheme.typography.titleLarge)
        if (timeline.isEmpty()) Text(if (beforeTracking)
            "Tracking had not begun on this date. No attendance was expected from the app's record."
            else "No office-area visit was recorded for this date. Missing time is unknown and does not confirm you were away.",
            Modifier.testTag("day_timeline_empty"))
        val offeredEdit = mutableSetOf<String>()
        val shownReview = mutableSetOf<String>()
        timeline.forEachIndexed { index, row ->
            Panel {
                Text("${instantText(row.at, input.policy.zoneId)} · ${row.title}",
                    Modifier.testTag("day_timeline_$index"), style = MaterialTheme.typography.titleMedium)
                Text(row.detail)
                val session = detail.sessions.find { it.id == row.sessionId }
                val reviewLine = session?.let(::timelineReviewLine)
                if (session != null && reviewLine != null && shownReview.add(session.id))
                    Text(reviewLine)
                if (session != null && offeredEdit.add(session.id)) edit?.let { action ->
                    OutlinedButton(onClick = { action(session) }, modifier = Modifier.testTag("timeline_edit_$index")) {
                        Text("Review or correct this session")
                    }
                }
            }
        }
        EvidenceSection("Credited intervals", detail.intervals) { interval ->
            Text("${at(interval.start)} → ${at(interval.end)}")
            Text("${minutesText(interval.minutes)} · from ${count(interval.sessionIds.size, "visit", "visits")}")
            if (interval.reconciledGap) Text("Includes a short gap at the same office, bridged by your ${input.policy.shortGapMinutes}-minute gap rule.")
            Text("Limited to this day in the policy timezone, after the arrival delay, gap bridging and counting overlaps once.")
        }
        EvidenceSection("Visits", detail.sessions) { session ->
            Text(office(session.officeId), style = MaterialTheme.typography.titleMedium)
            val original = detail.originalSessions.find { it.id == session.id }
            Text("As recorded: ${at(original?.start)} → ${at(original?.end)}")
            if (original?.reviewReasons?.contains(ReviewReason.UNCONFIRMED_GAP) == true)
                Text("Confirmed time before the gap may count. The uncertain time needs review; see Credited intervals for exact times.")
            if (session.correctionId != null && !session.correctionReverted) original?.reviewReasons?.forEach {
                Text("Before correction: ${reviewExplanation(it)} Your correction sets the visit times used.")
            }
            Text("Visit times used: ${at(session.start)} → ${at(session.end)}")
            Text("Credit can be less than this visit; Credited intervals show the time that counts.")
            if (original?.manualSessionId == null) {
                if (original?.start != null)
                    Text(if (ReviewReason.UNCONFIRMED_GAP in original.reviewReasons)
                        "Recorded span from arrival to a later location check (not confirmed continuous): ${minutesText(AttendanceEngine.observedMinutes(original, input.now))}"
                        else "Phone-recorded time in office area: ${minutesText(AttendanceEngine.observedMinutes(original, input.now))}")
                else Text("Time in office area unknown: no arrival was recorded.")
            } else Text("Manually entered time: ${minutesText(AttendanceEngine.observedMinutes(original, input.now))}")
            Text(if (ReviewReason.UNCONFIRMED_GAP in session.reviewReasons)
                "Visit span (not confirmed continuous): ${minutesText(AttendanceEngine.observedMinutes(session, input.now))}."
                else "Visit length: ${minutesText(AttendanceEngine.observedMinutes(session, input.now))}${if (session.correctionId != null && !session.correctionReverted) " after correction" else ""}.")
            if (session.isOpen) Text("Still open as of ${at(input.now)}. No leave time is assumed.")
            offices[session.officeId]?.let { value ->
                session.start?.let { start ->
                    val creditStart = start.plusSeconds(value.entryGraceMinutes * 60L)
                    Text("Arrival walking grace: ${value.entryGraceMinutes}m uncredited. Credit starts at ${at(creditStart)}.")
                    if (session.isOpen && input.now < creditStart) {
                        val minutesLeft = (Duration.between(input.now, creditStart).seconds + 59) / 60
                        Text("${minutesLeft}m until credit starts if the observed visit continues.")
                    }
                    if (session.end?.let { it <= creditStart } == true) Text("Visit ended before the arrival grace was up, so 0m is credited for it.")
                }
                Text("Exit grace: ${value.exitGraceMinutes}m. Used only for leave-time estimates; it adds no credit.")
                if (!value.enabled || !value.countsTowardAttendance) Text("This office is disabled or excluded from attendance credit.")
            }
            Text("Confidence: ${confidenceLabel(session.confidence)}. ${if (session.sourceEventIds.isEmpty()) "Entered manually" else "${count(session.sourceEventIds.size, "location record", "location records")} kept"}.")
            session.correctionId?.let { Text(if (session.correctionReverted) "Reverted to the recorded times. The revert is kept in the change history."
                else "Corrected. The original records are kept below.") }
            session.reviewReasons.forEach { Text(reviewExplanation(it)) }
            edit?.let { action -> OutlinedButton(onClick = { action(session) }) { Text("Correct session") } }
        }
        EvidenceSection("Location records", detail.rawEvents) { event ->
            val currentFix = evidenceById[event.id]?.source in setOf("FOREGROUND_LOCATION_RECONCILIATION", "BACKGROUND_LOCATION_RECONCILIATION",
                "ADAPTIVE_LOCATION_CONFIRMATION", "ADAPTIVE_RECOVERY_CONFIRMATION",
                dev.hamstercage.data.EXIT_VERIFY_INSIDE, dev.hamstercage.data.EXIT_VERIFY_OUTSIDE,
                dev.hamstercage.data.PRESENCE_CORROBORATION)
            Text("${when {
                currentFix && event.transition == Transition.ABSENCE -> "Location check: outside"
                currentFix && event.transition == Transition.PRESENCE -> "Location check: inside"
                else -> transitionLabel(event.transition)
            }} · ${office(event.officeId)} · ${at(event.at)}")
            if (currentFix) Text(when {
                event.transition == Transition.ABSENCE ->
                    "A one-time location check found you outside the office area. The exact time you left is unknown."
                detail.sessions.any { it.start == event.at && event.id in it.sourceEventIds } ->
                    "One-time location check. The visit starts at this check; no earlier arrival time is guessed."
                else -> "One-time location check confirming the visit already recorded. It does not restart the arrival grace."
            })
        }
        EvidenceSection("Manual entries", detail.manualSessions) { manual ->
            Text(office(manual.officeId))
            Text("${at(manual.start)} → ${at(manual.end)} · added ${at(manual.createdAt)}")
            if (manual.note.isNotBlank()) Text(evidenceText(manual.note))
        }
        EvidenceSection("Change history", detail.corrections) { correction ->
            Text("Correction to a recorded visit")
            if (correction.revertToOriginal) Text("Revert to recorded times · added ${at(correction.createdAt)}")
            else Text("${at(correction.start)} → ${correction.end?.let(::at) ?: "end from phone records"} · added ${at(correction.createdAt)}")
            Text(if (detail.sessions.any { it.correctionId == correction.id })
                if (correction.revertToOriginal) "In effect: the recorded times are used." else "In effect: these times are used."
                else "Kept for history; not in effect.")
            if (correction.note.isNotBlank()) Text(evidenceText(correction.note))
        }
        EvidenceSection("Items to review", detail.reviews) { review ->
            Text(reviewTitle(review.reason), style = MaterialTheme.typography.titleMedium)
            Text(reviewExplanation(review.reason))
            Text("${if (review.sessionId == null) "Not linked to a visit" else "Linked to a visit"} · ${count(review.sourceEventIds.size, "location record", "location records")}.")
        }
    }
}

/** Display-only labels; stored enum values are unchanged. */
internal fun confidenceLabel(value: Confidence): String = when (value) {
    Confidence.HIGH -> "High"
    Confidence.MEDIUM -> "Medium"
    Confidence.LOW -> "Low"
    Confidence.MANUAL -> "Manual"
}

internal fun transitionLabel(value: Transition): String = when (value) {
    Transition.ENTER -> "Entered office area"
    Transition.EXIT -> "Left office area"
    Transition.PRESENCE -> "Inside office area"
    Transition.ABSENCE -> "Outside office area"
}

internal fun reviewTitle(reason: ReviewReason): String = when (reason) {
    ReviewReason.DUPLICATE_EVENT -> "Duplicate records"
    ReviewReason.REPEATED_ENTER -> "Second arrival without an exit"
    ReviewReason.TRANSIENT_BOUNDARY -> "Very brief visit"
    ReviewReason.MISSING_ENTER -> "Exit without an arrival"
    ReviewReason.OPEN_SESSION -> "Visit still open"
    ReviewReason.STALE_OPEN_SESSION -> "Open past review limit"
    ReviewReason.UNCONFIRMED_GAP -> "Uncertain visit end"
    ReviewReason.UNCONFIRMED_BOUNDARY -> "Exit not confirmed"
    ReviewReason.INVALID_CORRECTION -> "Correction can't be used"
    ReviewReason.ORPHAN_CORRECTION -> "Correction without a visit"
    ReviewReason.UNKNOWN_OFFICE -> "Unknown office"
    ReviewReason.FUTURE_EVENT -> "Record dated after now"
    ReviewReason.CONFLICTING_EVENT_ID -> "Conflicting record"
    ReviewReason.INVALID_EVENT -> "Unreadable record"
    ReviewReason.ZERO_LENGTH_SESSION -> "Zero-length visit"
    ReviewReason.INVALID_MANUAL_SESSION -> "Manual entry can't be used"
    ReviewReason.CONFLICTING_MANUAL_SESSION_ID -> "Conflicting manual entry"
    ReviewReason.REPEATED_EXIT -> "Repeated exit"
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
