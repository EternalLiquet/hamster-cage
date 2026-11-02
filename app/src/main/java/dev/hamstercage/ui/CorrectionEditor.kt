package dev.hamstercage.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.hamstercage.data.AttendanceEdit
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class CorrectionActions(val now: () -> Instant, val newId: () -> String, val save: suspend (AttendanceEdit) -> Unit)

@Composable
fun CorrectionEditor(input: AttendanceInput, session: Session?, actions: CorrectionActions,
    clockIs24Hour: Boolean? = null, close: () -> Unit) {
    val zone = input.policy.zoneId
    val use24Hour = clockIs24Hour ?: DateFormat.is24HourFormat(LocalContext.current)
    fun local(value: Instant) = correctionPickerMinute(value, zone).toString()
    fun display(value: Instant) = correctionPreviewText(value, zone, use24Hour)
    val initialStart = session?.start ?: session?.end ?: input.now
    var startLocal by rememberSaveable(session?.id) { mutableStateOf(local(initialStart)) }
    var startOverlap by rememberSaveable(session?.id) { mutableStateOf(overlapChoiceFor(initialStart, zone)) }
    var endLocal by rememberSaveable(session?.id) { mutableStateOf(local(session?.end ?: input.now)) }
    var endOverlap by rememberSaveable(session?.id) { mutableStateOf(overlapChoiceFor(session?.end, zone)) }
    var hasEnd by rememberSaveable(session?.id) { mutableStateOf(session?.end != null) }
    var note by rememberSaveable { mutableStateOf("") }
    var officeId by rememberSaveable { mutableStateOf(session?.officeId ?: input.offices.firstOrNull()?.id.orEmpty()) }
    var preview by remember { mutableStateOf<AttendanceEdit?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun selectedBounds(now: Instant) = localCorrectionBounds(
        LocalBoundary(LocalDateTime.parse(startLocal), startOverlap),
        if (hasEnd) LocalBoundary(LocalDateTime.parse(endLocal), endOverlap) else null, zone, now)
    if (saved) {
        Notice("Attendance change saved", "Your original records and change history are kept. Totals now include this change.")
        Button(onClick = close) { Text("Back to history") }
        return
    }
    fun preview(revert: Boolean) {
        error = null
        try {
            require(note.length <= 2000) { "Keep the note under 2000 characters." }
            val baseline = input.copy(now = Instant.ofEpochMilli(actions.now().toEpochMilli()))
            val id = actions.newId()
            val edit = if (session != null) {
                val bounds = if (revert) CorrectionBounds(session.start ?: session.end ?: baseline.now, session.end)
                    else selectedBounds(baseline.now)
                val sequence = nextCorrectionSequence(baseline.corrections.maxOfOrNull { it.appendSequence } ?: 0)
                AttendanceEdit.Correct(baseline, Correction(id, session.id, bounds.start, bounds.end, baseline.now, note.trim(), revert, sequence))
            } else {
                require(input.offices.any { it.id == officeId }) { "Choose a saved office first." }
                val bounds = selectedBounds(baseline.now)
                AttendanceEdit.AddManual(baseline, ManualSession(id, officeId, bounds.start, bounds.end, baseline.now, note.trim()))
            }
            val proposed = AttendanceEngine.derive(edit.proposedInput())
            if (edit is AttendanceEdit.Correct)
                require(proposed.sessions.any { it.correctionId == edit.value.id }) { "A newer correction exists. Reopen and preview again." }
            preview = edit
        } catch (failure: IllegalArgumentException) { error = failure.message }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Text(if (session == null) "Add manual attendance" else "Correct session", style = MaterialTheme.typography.headlineSmall)
        Text("Your recorded visits stay as they are, and changes are saved separately. Times you add or correct here are labelled Manual; reverting restores the recorded times. Location permission is not needed.")
        Text("Times use ${zone.getDisplayName(TextStyle.FULL, Locale.getDefault())} (${zone.id}). Pick a local date and time; if the clock repeats an hour, you choose which one.")
        Text("Times are in whole minutes. You can check them before saving.")
        FormError(error)
        if (preview == null) {
            if (session == null) input.offices.forEach { office ->
                TextButton(onClick = { officeId = office.id }, enabled = !busy) {
                    Text("${if (officeId == office.id) "✓ " else ""}${evidenceText(office.name)}${if (!office.enabled || !office.countsTowardAttendance) " (no attendance credit)" else ""}")
                }
            }
            LocalBoundaryPicker("Start", LocalDateTime.parse(startLocal), startOverlap, zone, use24Hour, busy,
                onChange = { value -> startLocal = value.toString(); startOverlap = null },
                onOverlap = { startOverlap = it })
            Checkbox(checked = hasEnd, onCheckedChange = { hasEnd = it }, enabled = !busy,
                modifier = Modifier.testTag("correction_has_end").semantics { contentDescription = "Include an end time" })
            Text(when {
                hasEnd -> "End time selected"
                session != null -> "No end time · the visit keeps the end your phone records, and stays open until there is one."
                else -> "No end time · this adds a visit that stays open."
            })
            if (hasEnd) LocalBoundaryPicker("End", LocalDateTime.parse(endLocal), endOverlap, zone, use24Hour, busy,
                onChange = { value -> endLocal = value.toString(); endOverlap = null },
                onOverlap = { endOverlap = it })
            OutlinedTextField(note, { note = it }, label = { Text("Private note (optional)") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("correction_note"))
            Button(onClick = { preview(false) }, enabled = !busy && input.offices.isNotEmpty()) { Text("Preview attendance change") }
            if (session != null && input.corrections.any { it.sessionId in session.correctionTargetIds })
                OutlinedButton(onClick = { preview(true) }, enabled = !busy) { Text("Preview revert to original") }
        } else {
            val edit = preview!!
            val before = remember(edit) { AttendanceEngine.derive(edit.baseline) }
            val after = remember(edit) { AttendanceEngine.derive(edit.proposedInput()) }
            Panel {
                Text("Review before saving", style = MaterialTheme.typography.titleLarge)
                Text("Credited time, all days: ${minutesText(before.intervals.sumOf { it.minutes })} → ${minutesText(after.intervals.sumOf { it.minutes })}", Modifier.testTag("correction_preview_total"))
                val bounds = when (edit) {
                    is AttendanceEdit.Correct -> CorrectionBounds(edit.value.start, edit.value.end)
                    is AttendanceEdit.AddManual -> CorrectionBounds(edit.value.start, edit.value.end)
                }
                Text("Selected time: ${display(bounds.start)} → ${bounds.end?.let(::display) ?: "Open session"}",
                    Modifier.testTag("correction_preview_bounds"))
                Text("Preview as of ${display(edit.baseline.now)}. New visits, rule changes or more elapsed time can change totals later.")
                if (edit is AttendanceEdit.Correct && edit.value.revertToOriginal)
                    Text("Reverting puts this visit back the way it was recorded, including any missing start or end and items to review. The revert is added to the change history.")
                else Text("This time is labelled Manual. The arrival walking delay still applies and overlapping time counts once. Exit grace only changes leave-time estimates.")
                if (after.reviews.isNotEmpty()) Text("${after.reviews.size} ${if (after.reviews.size == 1) "item still needs" else "items still need"} review. Check the day details after saving.")
                Text("Recorded visits, earlier corrections and manual entries are all kept.")
            }
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    try { actions.save(edit); saved = true }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { error = "Change could not be saved, or the record changed. Your existing records were kept. Preview again and retry."; preview = null }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text(if (busy) "Saving…" else "Confirm attendance change") }
            TextButton(onClick = { preview = null }, enabled = !busy) { Text("Back to edit") }
        }
        TextButton(onClick = close, enabled = !busy) { Text("Cancel correction") }
    }
}

internal fun correctionTimeText(value: LocalDateTime, use24Hour: Boolean): String =
    value.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a"))

internal fun correctionPickerMinute(value: Instant, zone: ZoneId): LocalDateTime =
    value.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)

internal fun correctionPreviewText(value: Instant, zone: ZoneId, use24Hour: Boolean): String =
    DateTimeFormatter.ofPattern(if (use24Hour) "EEE, MMM d, yyyy · HH:mm z" else "EEE, MMM d, yyyy · h:mm a z")
        .withZone(zone).format(value)

@Composable
private fun LocalBoundaryPicker(label: String, value: LocalDateTime, overlap: Int?, zone: ZoneId,
    use24Hour: Boolean, busy: Boolean, onChange: (LocalDateTime) -> Unit, onOverlap: (Int) -> Unit) {
    val context = LocalContext.current
    Text(label, style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = {
        DatePickerDialog(context, { _, year, month, day ->
            onChange(LocalDate.of(year, month + 1, day).atTime(value.toLocalTime().truncatedTo(ChronoUnit.MINUTES)))
        }, value.year, value.monthValue - 1, value.dayOfMonth).show()
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("correction_${label.lowercase()}_date")) {
        Text("Choose $label date · ${value.toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}")
    }
    OutlinedButton(onClick = {
        TimePickerDialog(context, { _, hour, minute ->
            onChange(value.toLocalDate().atTime(hour, minute))
        }, value.hour, value.minute, use24Hour).show()
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("correction_${label.lowercase()}_time")) {
        Text("Choose $label time · ${correctionTimeText(value, use24Hour)}")
    }
    val offsets = overlapOffsets(value, zone)
    if (offsets.size == 2) {
        Text("Clocks repeat this time. Choose which occurrence you mean; the first happens before the second.")
        offsets.forEachIndexed { index, _ ->
            OutlinedButton(onClick = { onOverlap(index) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("correction_${label.lowercase()}_overlap_$index")) {
                Text("${if (overlap == index) "✓ " else ""}${if (index == 0) "First" else "Second"} ${correctionTimeText(value, use24Hour)}")
            }
        }
    } else if (zone.rules.getValidOffsets(value).isEmpty()) {
        val next = zone.rules.getTransition(value)?.dateTimeAfter
        Text("This time does not exist because clocks move forward. Choose another time.")
        if (next != null) OutlinedButton(onClick = { onChange(next) }, enabled = !busy,
            modifier = Modifier.testTag("correction_${label.lowercase()}_next_valid")) {
            Text("Use next valid time · ${correctionTimeText(next, use24Hour)}")
        }
    }
}
