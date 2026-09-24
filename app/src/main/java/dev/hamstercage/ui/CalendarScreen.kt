package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.ExcludedDate
import dev.hamstercage.domain.ExclusionReason
import dev.hamstercage.policy.calendarDate
import dev.hamstercage.policy.excludedDate
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class CalendarActions(
    val saveExclusion: suspend (ExcludedDate) -> Unit,
    val removeExclusion: suspend (LocalDate) -> Unit,
    val setWfh: suspend (LocalDate, Boolean) -> Unit,
)

private fun exclusionLabel(reason: ExclusionReason) = when (reason) {
    ExclusionReason.BANK_HOLIDAY -> "Bank holiday"
    ExclusionReason.COMPANY_CLOSURE -> "Company closure"
    ExclusionReason.PTO -> "PTO"
    ExclusionReason.OTHER_EXCUSED -> "Other excused"
}

@Composable
fun CalendarScreen(state: StorageState, today: LocalDate, actions: CalendarActions) {
    val policy = (state as? StorageState.Ready)?.snapshot?.policy ?: return
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingExisting by rememberSaveable { mutableStateOf(false) }
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var reasonName by rememberSaveable { mutableStateOf(ExclusionReason.BANK_HOLIDAY.name) }
    var note by rememberSaveable { mutableStateOf("") }
    var wfhDate by rememberSaveable { mutableStateOf(today.toString()) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    fun write(action: suspend () -> Unit, done: () -> Unit = {}) {
        busy = true; error = null; message = null
        scope.launch {
            try { action(); done(); message = "Calendar saved. Attendance has been recalculated." }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { error = "Calendar change could not be saved. Your existing records were kept. Try again." }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Panel {
            Text("Calendar and WFH", style = MaterialTheme.typography.titleLarge)
            Text("Excluded dates remove an expected day's requirement. WFH is a label and does not remove that requirement. Existing office credit is retained either way.", style = MaterialTheme.typography.bodyMedium)
            Text("No employer holiday feed is preloaded. Add only the dates that apply to your policy.", style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            Text("${today.year} WFH dates: ${policy.wfhDates.count { it.year == today.year }}", style = MaterialTheme.typography.bodyMedium)
            FormError(error)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!editing) Button(onClick = {
                date = today.toString(); reasonName = ExclusionReason.BANK_HOLIDAY.name; note = ""
                editingExisting = false; editing = true; error = null; message = null
            }, enabled = !busy) { Text("Add excluded date") }
            else {
                Text(if (editingExisting) "Edit exclusion" else "Add exclusion", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(date, { date = it }, label = { Text("Excluded date · YYYY-MM-DD") }, enabled = !busy && !editingExisting,
                    modifier = Modifier.fillMaxWidth().testTag("calendar_date"), singleLine = true)
                ExclusionReason.entries.forEach { reason ->
                    Row(Modifier.fillMaxWidth().selectable(reasonName == reason.name, enabled = !busy, role = Role.RadioButton, onClick = { reasonName = reason.name }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reasonName == reason.name, onClick = null, enabled = !busy)
                        Text(exclusionLabel(reason), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Private note (optional)") }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("calendar_note"))
                Text("Saving an existing date replaces its exclusion reason and note. Its WFH label stays unchanged.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    val value = try { excludedDate(date, ExclusionReason.valueOf(reasonName), note) }
                    catch (failure: IllegalArgumentException) { error = failure.message; return@Button }
                    write({ actions.saveExclusion(value) }, { editing = false })
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save exclusion") }
                TextButton(onClick = { editing = false; error = null }, enabled = !busy) { Text("Cancel exclusion edit") }
            }
            HorizontalDivider()
            OutlinedTextField(wfhDate, { wfhDate = it }, label = { Text("WFH date · YYYY-MM-DD") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("wfh_date"), singleLine = true)
            Button(onClick = {
                val value = try { calendarDate(wfhDate) }
                catch (failure: IllegalArgumentException) { error = failure.message; return@Button }
                write({ actions.setWfh(value, true) })
            }, enabled = !busy) { Text("Label WFH date") }
        }
        val dates = (policy.excludedDates.map { it.date } + policy.wfhDates).distinct().sortedDescending()
        val page = offset.coerceAtMost(((dates.size - 1).coerceAtLeast(0) / 10) * 10)
        if (dates.isEmpty()) Notice("No calendar dates yet", "No exclusions or WFH labels have been saved.")
        if (page > 0) TextButton(onClick = { offset = page - 10 }, enabled = !busy) { Text("Newer calendar dates") }
        dates.drop(page).take(10).forEach { day ->
            val exclusion = policy.excludedDates.find { it.date == day }
            Panel {
                Text(day.toString(), Modifier.testTag("calendar_saved_$day"), style = MaterialTheme.typography.titleMedium)
                exclusion?.let { saved ->
                    Tag(exclusionLabel(saved.reason).uppercase())
                    if (saved.note.isNotBlank()) Text(evidenceText(saved.note), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        date = day.toString(); note = saved.note; reasonName = saved.reason.name
                        editingExisting = true; editing = true; error = null; message = null
                    }, enabled = !busy) { Text("Edit exclusion for $day") }
                    TextButton(onClick = { write({ actions.removeExclusion(day) }) }, enabled = !busy) { Text("Remove exclusion for $day") }
                }
                if (day in policy.wfhDates) {
                    Tag("WFH")
                    TextButton(onClick = { write({ actions.setWfh(day, false) }) }, enabled = !busy) { Text("Remove WFH for $day") }
                }
            }
        }
        if (page + 10 < dates.size) TextButton(onClick = { offset = page + 10 }, enabled = !busy) { Text("Earlier calendar dates") }
    }
}
