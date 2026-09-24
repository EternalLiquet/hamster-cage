package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import dev.hamstercage.data.PolicySettings
import dev.hamstercage.data.StorageState
import dev.hamstercage.policy.PolicyDraft
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SettingsSaver = listSaver<PolicySettings, String>(
    save = { listOf(it.zoneId.id, it.targetMinutesPerDay.toString(), it.expectedWeekdays.sorted().joinToString(",") { day -> day.value.toString() }, it.shortGapMinutes.toString(), it.maxOpenSessionHours.toString()) },
    restore = { PolicySettings(ZoneId.of(it[0]), it[1].toInt(), it[2].split(',').filter(String::isNotBlank).map { value -> DayOfWeek.of(value.toInt()) }.toSet(), it[3].toInt(), it[4].toInt()) },
)

@Composable
fun PolicyScreen(state: StorageState, save: suspend (PolicySettings, PolicySettings) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf("") }
    var gap by rememberSaveable { mutableStateOf("") }
    var maxOpen by rememberSaveable { mutableStateOf("") }
    var weekdayValues by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var expected by rememberSaveable(stateSaver = SettingsSaver) { mutableStateOf(PolicySettings()) }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val policy = (state as? StorageState.Ready)?.snapshot?.policy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        when {
            state == StorageState.Loading -> Notice("Loading policy", "Reading settings saved on this device.")
            policy == null -> Notice("Policy unavailable", "Local settings could not be opened. Saved data was kept for recovery.")
            !editing -> Panel {
                Text("Attendance policy", style = MaterialTheme.typography.titleLarge)
                MetricRow("Daily target", "${policy.targetMinutesPerDay} minutes")
                MetricRow("Expected weekdays", policy.expectedWeekdays.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.ifEmpty { "None" })
                MetricRow("Policy timezone", policy.zoneId.id)
                MetricRow("Short-gap merge", "${policy.shortGapMinutes} minutes")
                MetricRow("Review open sessions after", "${policy.maxOpenSessionHours} hours")
                Text("Changes recalculate saved history from original events. They never change the recorded observations.", style = MaterialTheme.typography.bodyMedium)
                if (saved) Text("Policy saved. Attendance has been recalculated.", style = MaterialTheme.typography.bodyMedium)
                CageButton("Edit policy", {
                    val draft = PolicyDraft.from(policy)
                    target = draft.targetMinutes; zone = draft.zone; gap = draft.gapMinutes; maxOpen = draft.maxOpenHours
                    weekdayValues = draft.weekdays.map { it.value }; expected = PolicySettings(policy.zoneId, policy.targetMinutesPerDay, policy.expectedWeekdays, policy.shortGapMinutes, policy.maxOpenSessionHours)
                    error = null; saved = false; editing = true
                })
            }
            else -> Panel {
                Text("Edit attendance policy", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(target, { target = it }, label = { Text("Target minutes per workday") }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("policy_target"), singleLine = true)
                OutlinedTextField(zone, { zone = it }, label = { Text("Policy timezone") }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("policy_zone"), singleLine = true)
                Text("Use a named timezone such as America/New_York. Device timezone changes while travelling do not change this policy.", style = MaterialTheme.typography.bodyMedium)
                Text("Expected weekdays", style = MaterialTheme.typography.titleMedium)
                DayOfWeek.entries.forEach { day ->
                    val selected = day.value in weekdayValues
                    Row(Modifier.fillMaxWidth().testTag("policy_day_${day.name}").toggleable(selected, enabled = !saving, role = Role.Checkbox,
                        onValueChange = { checked -> weekdayValues = if (checked) (weekdayValues + day.value).distinct() else weekdayValues - day.value }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, onCheckedChange = null, enabled = !saving)
                        Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(gap, { gap = it }, label = { Text("Short-gap merge minutes (0–120)") }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("policy_gap"), singleLine = true)
                OutlinedTextField(maxOpen, { maxOpen = it }, label = { Text("Open-session review hours (1–24)") }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("policy_max_open"), singleLine = true)
                Text("Saving changes recalculates past and current totals, required days and departure estimates. Original events, corrections, holidays and WFH labels remain intact.", style = MaterialTheme.typography.bodyMedium)
                FormError(error)
                Button(onClick = {
                    val settings = try { PolicyDraft(target, zone, gap, maxOpen, weekdayValues.map(DayOfWeek::of).toSet()).validated() }
                    catch (failure: IllegalArgumentException) { error = failure.message; return@Button }
                    saving = true; error = null
                    scope.launch {
                        try { save(settings, expected); editing = false; saved = true }
                        catch (failure: CancellationException) { throw failure }
                        catch (_: IllegalStateException) { error = "Policy changed. Cancel and reopen the editor before saving." }
                        catch (_: Exception) { error = "Policy could not be saved. Your recorded attendance was kept. Try again." }
                        finally { saving = false }
                    }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save and recalculate") }
                TextButton(onClick = { editing = false; error = null }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Cancel policy edit") }
            }
        }
    }
}
