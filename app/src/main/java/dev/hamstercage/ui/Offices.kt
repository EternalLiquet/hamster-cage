package dev.hamstercage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.hamstercage.domain.Office
import java.util.UUID

@Composable
fun OfficesScreen(offices: List<Office>, onEdit: (Office?) -> Unit, health: @Composable () -> Unit) {
    ScreenColumn {
        PageHeading("Offices","One credit pool. Every eligible office.")
        Button(onClick={onEdit(null)},modifier=Modifier.fillMaxWidth()) { Text("Add office") }
        if(offices.isEmpty()) Notice("Start with a place","Use Westerville, Polaris, or your own label. Coordinates stay on this phone. A 150 m radius is a starting point; buildings and GPS conditions affect accuracy.")
        offices.forEach { office ->
            Panel {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(CageStyle.Gap)) {
                    Text(office.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Tag(if(office.enabled) "ENABLED" else "PAUSED",office.enabled)
                }
                Text(if(office.countsTowardAttendance) "Counts toward attendance" else "Tracking only · no attendance credit",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                MetricRow("Radius","${office.radiusMeters.toInt()} m")
                MetricRow("Walking grace","${office.entryGraceMinutes}m entry · ${office.exitGraceMinutes}m exit")
                OutlinedButton(onClick={onEdit(office)}) { Text("Edit office") }
            }
        }
        if(offices.isNotEmpty()) health()
        Text("Policy changes recompute history. Disabling an office or its eligibility removes its credit from current calculations; original events remain saved.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
    }
}

@Composable
fun OfficeDialog(office: Office?, onDismiss: () -> Unit, saving: Boolean = false, onSave: (Office) -> Unit) {
    var name by remember { mutableStateOf(office?.name ?: "") }
    var latitude by remember { mutableStateOf(office?.latitude?.toString() ?: "") }
    var longitude by remember { mutableStateOf(office?.longitude?.toString() ?: "") }
    var radius by remember { mutableStateOf(office?.radiusMeters?.toInt()?.toString() ?: "150") }
    var entry by remember { mutableStateOf(office?.entryGraceMinutes?.toString() ?: "5") }
    var exit by remember { mutableStateOf(office?.exitGraceMinutes?.toString() ?: "5") }
    var enabled by remember { mutableStateOf(office?.enabled ?: true) }
    var counts by remember { mutableStateOf(office?.countsTowardAttendance ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    FormDialog(if(office==null) "Add office" else "Edit office",onDismiss) {
        Text("Paste decimal coordinates from a trusted map. No map service or location search receives this form.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        Field("Office name",name,{name=it})
        if(office==null && name.isBlank()) Row(horizontalArrangement=Arrangement.spacedBy(CageStyle.Gap)) {
            SuggestionChip(onClick={name="Westerville"},label={Text("Westerville")})
            SuggestionChip(onClick={name="Polaris"},label={Text("Polaris")})
        }
        Field("Latitude (−90 to 90)",latitude,{latitude=it},KeyboardType.Text)
        Field("Longitude (−180 to 180)",longitude,{longitude=it},KeyboardType.Text)
        Field("Radius in meters (50–5000)",radius,{radius=it},KeyboardType.Number)
        Field("Entry grace in minutes (0–120)",entry,{entry=it},KeyboardType.Number)
        Field("Exit grace in minutes (0–120)",exit,{exit=it},KeyboardType.Number)
        LabeledToggle("Office enabled","Disabling excludes this office’s historical credit too.",enabled,{enabled=it})
        LabeledToggle("Counts toward attendance","Changing eligibility recomputes historical totals.",counts,{counts=it})
        FormError(error)
        FormActions(onDismiss,{
            val lat=latitude.trim().toDoubleOrNull();val lon=longitude.trim().toDoubleOrNull()
            val rad=radius.trim().toFloatOrNull();val ent=entry.trim().toIntOrNull();val ext=exit.trim().toIntOrNull()
            error=when {
                name.trim().isEmpty() || name.trim().length>80 -> "Use an office name between 1 and 80 characters."
                lat==null || !lat.isFinite() || lat !in -90.0..90.0 -> "Latitude must be a number from −90 to 90."
                lon==null || !lon.isFinite() || lon !in -180.0..180.0 -> "Longitude must be a number from −180 to 180."
                rad==null || !rad.isFinite() || rad !in 50f..5000f -> "Radius must be from 50 to 5000 meters."
                ent==null || ent !in 0..120 || ext==null || ext !in 0..120 -> "Grace must be a whole number from 0 to 120 minutes."
                else -> null
            }
            if(error==null) onSave(Office(office?.id ?: UUID.randomUUID().toString(),name.trim(),lat!!,lon!!,rad!!,enabled,counts,ent!!,ext!!))
        },saveText=if(saving) "Saving…" else "Save",enabled=!saving)
    }
}

@Composable
fun Field(label: String,value: String,onChange: (String)->Unit,keyboard: KeyboardType=KeyboardType.Text,singleLine: Boolean=true) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=singleLine,keyboardOptions=KeyboardOptions(keyboardType=keyboard),shape=MaterialTheme.shapes.medium)
}
