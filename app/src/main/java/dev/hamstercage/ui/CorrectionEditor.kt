package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.hamstercage.data.AttendanceEdit
import dev.hamstercage.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class CorrectionActions(val now: () -> Instant, val newId: () -> String, val save: suspend (AttendanceEdit) -> Unit)

@Composable
fun CorrectionEditor(input: AttendanceInput, session: Session?, actions: CorrectionActions, close: () -> Unit) {
    fun at(value: Instant?) = value?.atZone(input.policy.zoneId)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).orEmpty()
    var start by rememberSaveable { mutableStateOf(at(session?.start ?: Instant.ofEpochMilli(input.now.toEpochMilli()))) }
    var end by rememberSaveable { mutableStateOf(at(session?.end)) }
    var note by rememberSaveable { mutableStateOf("") }
    var officeId by rememberSaveable { mutableStateOf(session?.officeId ?: input.offices.firstOrNull()?.id.orEmpty()) }
    var preview by remember { mutableStateOf<AttendanceEdit?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (saved) {
        Notice("Attendance change saved", "Your immutable source facts and correction audit were kept. Totals have been recalculated.")
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
                    else correctionBounds(start, end, baseline.now)
                val sequence = nextCorrectionSequence(baseline.corrections.maxOfOrNull { it.appendSequence } ?: 0)
                AttendanceEdit.Correct(baseline, Correction(id, session.id, bounds.start, bounds.end, baseline.now, note.trim(), revert, sequence))
            } else {
                require(input.offices.any { it.id == officeId }) { "Choose a saved office first." }
                val bounds = correctionBounds(start, end, baseline.now)
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
        Text("Original observations stay unchanged. This adds a visible manual audit entry. Location permission is not required.")
        Text("Use a UTC offset for each boundary to distinguish repeated DST hours. Policy timezone: ${input.policy.zoneId.id}.")
        FormError(error)
        if (preview == null) {
            if (session == null) input.offices.forEach { office ->
                TextButton(onClick = { officeId = office.id }, enabled = !busy) {
                    Text("${if (officeId == office.id) "✓ " else ""}${evidenceText(office.name)}${if (!office.enabled || !office.countsTowardAttendance) " (no attendance credit)" else ""}")
                }
            }
            OutlinedTextField(start, { start = it }, label = { Text("Effective start with offset") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("correction_start"))
            OutlinedTextField(end, { end = it }, label = { Text("Effective end with offset (blank = open)") }, singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("correction_end"))
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
                Text("All recorded credited time: ${minutesText(before.intervals.sumOf { it.minutes })} → ${minutesText(after.intervals.sumOf { it.minutes })}", Modifier.testTag("correction_preview_total"))
                Text("Preview evaluated at ${at(edit.baseline.now)}. New observations, policy or advancing time can update later totals.")
                if (edit is AttendanceEdit.Correct && edit.value.revertToOriginal)
                    Text("Revert appends an audit marker and restores original reconstruction, including any missing boundaries and review flags.")
                else Text("The resulting attendance is marked MANUAL. Office grace and overlap rules still apply.")
                if (after.reviews.isNotEmpty()) Text("${after.reviews.size} review notices remain in the local record; inspect day detail after saving.")
                Text("Raw observations, earlier corrections and manual source entries are retained.")
            }
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    try { actions.save(edit); saved = true }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { error = "Change could not be saved, or the record changed. Your facts were kept. Review a fresh preview and try again."; preview = null }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text(if (busy) "Saving…" else "Confirm attendance change") }
            TextButton(onClick = { preview = null }, enabled = !busy) { Text("Back to edit") }
        }
        TextButton(onClick = close, enabled = !busy) { Text("Cancel correction") }
    }
}
