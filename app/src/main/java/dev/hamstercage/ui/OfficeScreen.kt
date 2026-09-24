package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.offices.OfficeDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class OfficeActions(
    val newId: () -> String,
    val version: suspend (String) -> Long?,
    val save: suspend (Office, Long?) -> Unit,
)

@Composable
fun OfficeScreen(state: StorageState, actions: OfficeActions) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var expectedVersion by remember { mutableStateOf<Long?>(null) }
    var loadingVersion by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf("150") }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var eligible by rememberSaveable { mutableStateOf(true) }
    var entryGrace by rememberSaveable { mutableStateOf("5") }
    var exitGrace by rememberSaveable { mutableStateOf("5") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

    fun populate(draft: OfficeDraft) {
        name = draft.name; latitude = draft.latitude; longitude = draft.longitude
        radius = draft.radiusMeters; enabled = draft.enabled
        eligible = draft.countsTowardAttendance
        entryGrace = draft.entryGraceMinutes; exitGrace = draft.exitGraceMinutes
        error = null; status = null
    }

    fun startEditing(office: Office) {
        editing = true
        editingId = office.id
        expectedVersion = null
        populate(OfficeDraft.from(office))
    }

    LaunchedEffect(editing, editingId) {
        val officeId = editingId
        if (editing && officeId != null) {
            loadingVersion = true
            expectedVersion = null
            try {
                val version = actions.version(officeId)
                if (editing && editingId == officeId) {
                    expectedVersion = version
                    if (version == null) error = "Office changed. Reopen the list and try again."
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) {
                if (editing && editingId == officeId) error = "Office could not be loaded. Try again."
            } finally {
                if (editing && editingId == officeId) loadingVersion = false
            }
        }
    }

    when (state) {
        StorageState.Loading -> Notice("Loading offices", "Reading offices saved on this device.")
        StorageState.Unavailable -> Notice("Offices unavailable", "Local attendance data could not be opened. Saved data was kept for recovery.")
        is StorageState.Ready -> if (!editing) {
            if (state.snapshot.offices.isEmpty()) {
                Notice("No offices yet", "Add an office with coordinates entered on this device. No location is preloaded.")
            } else {
                state.snapshot.offices.forEach { office ->
                    key(office.id) { OfficeRow(office, ::startEditing) }
                }
            }
            status?.let { Notice("Saved", it) }
            CageButton("Add office", onClick = {
                editing = true
                editingId = null
                expectedVersion = null
                loadingVersion = false
                populate(OfficeDraft())
            })
            Text("Coordinates remain on this device. Office boundary detection is connected in the capture feature.",
                color = CageStyle.Secondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Notice(if (editingId == null) "Add office" else "Edit office",
                "Enter exact coordinates on this device. No location search or network request is used.")
            FormError(error)
            OutlinedTextField(name, { name = it }, label = { Text("Office name") },
                modifier = Modifier.fillMaxWidth().testTag("officeName"), singleLine = true)
            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude (degrees)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude (degrees)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(radius, { radius = it }, label = { Text("Radius (meters)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(entryGrace, { entryGrace = it }, label = { Text("Entry grace (minutes)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(exitGrace, { exitGrace = it }, label = { Text("Exit grace (minutes)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth().padding(vertical = CageStyle.Tight), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Enabled for detection", style = MaterialTheme.typography.bodyMedium)
                Switch(enabled, { enabled = it }, modifier = Modifier.semantics { contentDescription = "Enabled for detection" })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = CageStyle.Tight), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Counts toward attendance", style = MaterialTheme.typography.bodyMedium)
                Switch(eligible, { eligible = it }, modifier = Modifier.semantics { contentDescription = "Counts toward attendance" })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
                CageButton("Save office", onClick = {
                    val draft = OfficeDraft(name, latitude, longitude, radius, enabled, eligible, entryGrace, exitGrace)
                    val office = try { draft.toOffice(editingId ?: actions.newId()) }
                    catch (invalid: IllegalArgumentException) { error = invalid.message; return@CageButton }
                    if (loadingVersion || saving || (editingId != null && expectedVersion == null)) {
                        error = "Office is still loading. Try again."; return@CageButton
                    }
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            actions.save(office, expectedVersion)
                            status = "Office saved locally. Boundary registration will follow capture setup."
                            editing = false
                        } catch (failure: CancellationException) { throw failure }
                        catch (_: Exception) { error = "Office could not be saved. Review the form and try again." }
                        finally { saving = false }
                    }
                })
                CageButton("Cancel", onClick = { editing = false; error = null })
            }
        }
    }
}

@Composable
private fun OfficeRow(office: Office, onEdit: (Office) -> Unit) {
    Panel {
        Text(office.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(CageStyle.Small)) {
            Tag(if (office.enabled) "ENABLED" else "DISABLED")
            Tag(if (office.countsTowardAttendance) "COUNTS" else "NOT CREDITED")
        }
        MetricRow("Radius", "${office.radiusMeters} m")
        MetricRow("Entry grace", "${office.entryGraceMinutes} min")
        MetricRow("Exit grace", "${office.exitGraceMinutes} min")
        CageButton("Edit ${office.name}", onClick = { onEdit(office) },
            modifier = Modifier.testTag("edit-${office.id}"))
    }
}
