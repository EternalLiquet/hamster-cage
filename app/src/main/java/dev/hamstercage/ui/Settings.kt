package dev.hamstercage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.hamstercage.domain.*
import java.time.*
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SettingsScreen(policy: Policy,onPolicy: (Policy)->Unit,onExclusion: (ExcludedDate)->Unit,onRemoveExclusion: (LocalDate)->Unit,onWfh: (LocalDate,Boolean)->Unit,onDelete: (String)->Unit,health: @Composable ()->Unit) {
    var policyDialog by remember { mutableStateOf(false) }
    var exclusionDialog by remember { mutableStateOf(false) }
    var wfhDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val today=LocalDate.now(policy.zoneId)
    ScreenColumn {
        PageHeading("Settings","Your policy. Your private record.")
        Panel {
            Text("Attendance policy",style=MaterialTheme.typography.titleLarge)
            MetricRow("Daily target",minutesText(policy.targetMinutesPerDay.toDouble()))
            MetricRow("Expected weekdays",policy.expectedWeekdays.sortedBy { it.value }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT,Locale.getDefault()) }.ifEmpty { "None" })
            MetricRow("Timezone",policy.zoneId.id)
            MetricRow("Merge short gaps","${policy.shortGapMinutes} minutes")
            OutlinedButton(onClick={policyDialog=true}) { Text("Edit policy") }
            Text("Policy changes recalculate saved history from its original events.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        }
        health()
        Panel {
            Text("Holidays & exclusions",style=MaterialTheme.typography.titleLarge)
            Text("Excluded dates do not add to required workdays. Holidays are not automatically imported.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            if(policy.excludedDates.isEmpty()) Text("No excluded dates.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            policy.excludedDates.sortedByDescending { it.date }.forEach { day ->
                HorizontalDivider(color=CageStyle.Outline)
                Text(day.date.toString(),style=MaterialTheme.typography.titleMedium)
                Text(readable(day.reason.name),style=MaterialTheme.typography.bodyMedium)
                if(day.note.isNotBlank()) Text(day.note,style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                TextButton(onClick={onRemoveExclusion(day.date)}) { Text("Remove exclusion") }
            }
            OutlinedButton(onClick={exclusionDialog=true}) { Text("Add excluded date") }
        }
        Panel {
            Text("Work from home",style=MaterialTheme.typography.titleLarge)
            MetricRow("${today.year} WFH days",policy.wfhDates.count { it.year == today.year }.toString(),true)
            Text("WFH labels do not reduce your attendance requirement. A remote expected day still belongs in the denominator.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            policy.wfhDates.sortedDescending().forEach { date ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(date.toString(),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                    TextButton(onClick={onWfh(date,false)}) { Text("Remove") }
                }
            }
            OutlinedButton(onClick={wfhDialog=true}) { Text("Label WFH date") }
        }
        Panel {
            Text("Privacy & storage",style=MaterialTheme.typography.titleLarge)
            Tag("LOCAL FIRST",true)
            Text("Attendance and office coordinates are stored in this app’s private storage on your phone. This build has no analytics, account, or app sync. Android’s location provider handles geofence detection.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            Text("No automatic cloud backup is enabled. Uninstalling or clearing app storage deletes these records. Export and private homelab sync are planned for a later release.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            OutlinedButton(onClick={deleteDialog=true},colors=ButtonDefaults.outlinedButtonColors(contentColor=CageStyle.Danger)) { Text("Delete attendance history") }
        }
        Text("Hamster Cage · personal attendance estimates\nNot an official employer record.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
    }
    if(policyDialog) PolicyDialog(policy,{policyDialog=false}) { onPolicy(it);policyDialog=false }
    if(exclusionDialog) ExclusionDialog(today,{exclusionDialog=false}) { onExclusion(it);exclusionDialog=false }
    if(wfhDialog) DateLabelDialog(today,{wfhDialog=false}) { onWfh(it,true);wfhDialog=false }
    if(deleteDialog) DeleteDialog({deleteDialog=false}) { onDelete(it);deleteDialog=false }
}

@Composable
private fun PolicyDialog(policy: Policy,onDismiss: ()->Unit,onSave: (Policy)->Unit) {
    var target by remember { mutableStateOf(policy.targetMinutesPerDay.toString()) }
    var zone by remember { mutableStateOf(policy.zoneId.id) }
    var gap by remember { mutableStateOf(policy.shortGapMinutes.toString()) }
    var maxOpen by remember { mutableStateOf(policy.maxOpenSessionHours.toString()) }
    var weekdays by remember { mutableStateOf(policy.expectedWeekdays) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog("Attendance policy",onDismiss) {
        Field("Target minutes per workday",target,{target=it},KeyboardType.Number)
        Field("Policy timezone",zone,{zone=it})
        Text("Use a timezone such as America/New_York. The phone’s travel timezone does not change this policy.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        Text("Expected weekdays",style=MaterialTheme.typography.titleMedium)
        DayOfWeek.values().forEach { day ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=day in weekdays,onCheckedChange={checked -> weekdays=if(checked) weekdays+day else weekdays-day})
                Text(day.getDisplayName(TextStyle.FULL,Locale.getDefault()),style=MaterialTheme.typography.bodyLarge)
            }
        }
        Field("Short-gap merge minutes (0–120)",gap,{gap=it},KeyboardType.Number)
        Field("Review open sessions after hours (1–24)",maxOpen,{maxOpen=it},KeyboardType.Number)
        Text("Changing these rules also updates historical calculations. Original events are preserved.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        FormError(error)
        FormActions(onDismiss,{
            try {
                val targetValue=target.toIntOrNull();val gapValue=gap.toIntOrNull();val maxValue=maxOpen.toIntOrNull()
                require(targetValue!=null && targetValue in 1..1440) { "Target must be from 1 to 1440 whole minutes." }
                require(gapValue!=null && gapValue in 0..120) { "Gap must be from 0 to 120 whole minutes." }
                require(maxValue!=null && maxValue in 1..24) { "Open-session review must be from 1 to 24 hours." }
                require(weekdays.isNotEmpty()) { "Choose at least one expected weekday." }
                val zoneValue=ZoneId.of(zone.trim())
                onSave(policy.copy(zoneId=zoneValue,targetMinutesPerDay=targetValue,expectedWeekdays=weekdays,shortGapMinutes=gapValue,maxOpenSessionHours=maxValue))
            } catch(e: IllegalArgumentException) { error=e.message ?: "Check the policy values." }
              catch(e: DateTimeException) { error="Enter a valid timezone, such as America/New_York." }
        })
    }
}

@Composable
private fun ExclusionDialog(today: LocalDate,onDismiss: ()->Unit,onSave: (ExcludedDate)->Unit) {
    var date by remember { mutableStateOf(today.toString()) }
    var reason by remember { mutableStateOf(ExclusionReason.BANK_HOLIDAY) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog("Exclude a date",onDismiss) {
        Field("Date · YYYY-MM-DD",date,{date=it})
        Text("Reason",style=MaterialTheme.typography.titleMedium)
        ExclusionReason.entries.forEach { option ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=reason==option,onClick={reason=option})
                Text(readable(option.name),style=MaterialTheme.typography.bodyLarge)
            }
        }
        Field("Optional note",note,{note=it},singleLine=false)
        Text("Saving an existing date updates its reason and note.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        FormError(error)
        FormActions(onDismiss,{
            try {
                require(note.length<=2000) { "Keep the note under 2000 characters." }
                onSave(ExcludedDate(LocalDate.parse(date.trim()),reason,note.trim()))
            } catch(e: DateTimeException) { error="Enter a valid date using YYYY-MM-DD." }
              catch(e: IllegalArgumentException) { error=e.message }
        })
    }
}

@Composable
private fun DateLabelDialog(today: LocalDate,onDismiss: ()->Unit,onSave: (LocalDate)->Unit) {
    var date by remember { mutableStateOf(today.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog("Label WFH date",onDismiss) {
        Field("Date · YYYY-MM-DD",date,{date=it})
        Text("This labels the date without excluding it from the attendance requirement.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        FormError(error)
        FormActions(onDismiss,{
            try { onSave(LocalDate.parse(date.trim())) }
            catch(e: DateTimeException) { error="Enter a valid date using YYYY-MM-DD." }
        })
    }
}

@Composable
private fun DeleteDialog(onDismiss: ()->Unit,onDelete: (String)->Unit) {
    var confirmation by remember { mutableStateOf("") }
    FormDialog("Delete attendance history?",onDismiss) {
        Text("Permanently deletes raw attendance events, manual sessions, corrections, exclusions and WFH labels. Office configuration and attendance policy stay. This cannot be undone.",style=MaterialTheme.typography.bodyLarge)
        Field("Type DELETE to confirm",confirmation,{confirmation=it})
        FormActions(onDismiss,{onDelete(confirmation)},saveText="Delete history",enabled=confirmation=="DELETE")
    }
}
