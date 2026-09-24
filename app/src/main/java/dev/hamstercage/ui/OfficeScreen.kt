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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.hamstercage.data.StorageState
import dev.hamstercage.domain.Office
import dev.hamstercage.offices.OfficeDraft
import dev.hamstercage.offices.OfficeFix
import dev.hamstercage.offices.OfficeMapTile
import dev.hamstercage.offices.OfficePlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class OfficeActions(
    val newId: () -> String,
    val version: suspend (String) -> Long?,
    val save: suspend (Office, Long?) -> Unit,
    val search: suspend (String) -> List<OfficePlace> = { emptyList() },
    val current: suspend () -> OfficeFix = { error("Current location unavailable") },
    val tile: suspend (Double, Double, Float) -> OfficeMapTile? = { _, _, _ -> null },
    val requestForeground: () -> Unit = {},
    val openDeviceSettings: () -> Unit = {},
)

private data class MapRequest(val latitude: Double, val longitude: Double, val radiusMeters: Float, val epoch: Int)
private data class LoadedMap(val request: MapRequest, val tile: OfficeMapTile)

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
    var address by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OfficePlace>>(emptyList()) }
    var searchEpoch by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var selectedLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var manualSelection by rememberSaveable { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var loadedMap by remember { mutableStateOf<LoadedMap?>(null) }
    var tileEpoch by remember { mutableStateOf(0) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var mapForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                currentJob?.cancel(); currentJob = null; locating = false
                searchJob?.cancel(); searchJob = null; searching = false; searchEpoch++
                mapForeground = false; loadedMap = null
            } else if (event == Lifecycle.Event.ON_START) {
                mapForeground = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentJob?.cancel(); searchJob?.cancel()
        }
    }

    fun select(place: OfficePlace) {
        searchEpoch++
        searchJob?.cancel(); searching = false; locating = false
        latitude = place.latitude.toString(); longitude = place.longitude.toString()
        selectedLabel = place.label; manualSelection = false; reviewing = true; confirmed = false
        results = emptyList(); error = null; status = null; loadedMap = null; tileEpoch++
    }

    fun populate(draft: OfficeDraft) {
        searchJob?.cancel(); currentJob?.cancel(); searching = false; locating = false
        name = draft.name; latitude = draft.latitude; longitude = draft.longitude
        radius = draft.radiusMeters; enabled = draft.enabled
        eligible = draft.countsTowardAttendance
        entryGrace = draft.entryGraceMinutes; exitGrace = draft.exitGraceMinutes
        error = null; status = null
        address = ""; results = emptyList(); searchEpoch++
        selectedLabel = if (draft.latitude.isNotBlank()) "Saved office location" else null
        manualSelection = false
        reviewing = false; confirmed = selectedLabel != null; advanced = false; loadedMap = null
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

    fun currentMapRequest(): MapRequest? {
        if (!editing || !reviewing || !mapForeground) return null
        val lat = latitude.toDoubleOrNull(); val lon = longitude.toDoubleOrNull(); val meters = radius.toFloatOrNull()
        if (lat == null || lon == null || meters == null || lat !in -85.0511..85.0511 || lon !in -180.0..180.0 || meters !in 50f..5000f) return null
        return MapRequest(lat, lon, meters, tileEpoch)
    }

    val requestedMap = currentMapRequest()
    LaunchedEffect(requestedMap) {
        loadedMap = null
        if (requestedMap != null) {
            delay(350) // Only a settled radius or pin starts the four viewed-tile requests.
            val tile = try { actions.tile(requestedMap.latitude, requestedMap.longitude, requestedMap.radiusMeters) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { null }
            if (requestedMap == currentMapRequest()) loadedMap = tile?.let { LoadedMap(requestedMap, it) }
        }
    }

    when (state) {
        StorageState.Loading -> Notice("Loading offices", "Reading offices saved on this device.")
        StorageState.Unavailable -> Notice("Offices unavailable", "Local attendance data could not be opened. Saved data was kept for recovery.")
        is StorageState.Ready -> if (!editing) {
            if (state.snapshot.offices.isEmpty()) {
                Notice("No offices yet", "Add a name, then find the address or use your current location. You can also enter coordinates under Advanced.")
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
            Text("Saved offices work offline. Search sends only the address you enter to the device's geocoder; map review loads one OpenStreetMap area.",
                color = CageStyle.Secondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Notice(if (editingId == null) "Add office" else "Edit office",
                "Name the office, choose a search result or your current location, review the pin and boundary, then save.")
            FormError(error)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(name, { name = it }, label = { Text("Office name") },
                modifier = Modifier.fillMaxWidth().testTag("officeName"), singleLine = true)
            OutlinedTextField(address, { address = it; searchEpoch++; searchJob?.cancel(); searching = false; results = emptyList() },
                label = { Text("Search address") }, modifier = Modifier.fillMaxWidth().testTag("officeAddress"), singleLine = true)
            CageButton(if (searching) "Searching…" else "Search address", onClick = {
                val query = address.trim(); val epoch = ++searchEpoch
                searchJob?.cancel(); currentJob?.cancel(); locating = false
                searching = true; confirmed = false; reviewing = false; manualSelection = false; loadedMap = null
                error = null; results = emptyList()
                searchJob = scope.launch {
                    try {
                        val found = actions.search(query)
                        if (epoch == searchEpoch) {
                            results = found
                            if (found.isEmpty()) error = "No addresses found. Try a more specific address, current location or Advanced coordinates."
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { if (epoch == searchEpoch) error = failure.message ?: "Address search unavailable. Retry or use another location method." }
                    finally { if (epoch == searchEpoch) searching = false }
                }
            }, modifier = Modifier.testTag("searchAddressButton"))
            results.forEach { place -> CageButton("Select ${place.label}", onClick = { currentJob?.cancel(); select(place) }) }
            CageButton(if (locating) "Finding current location…" else "Use my current location", onClick = {
                val epoch = ++searchEpoch; locating = true; confirmed = false; reviewing = false; manualSelection = false; loadedMap = null; error = null
                searchJob?.cancel(); searching = false; results = emptyList()
                currentJob?.cancel()
                currentJob = scope.launch {
                    try {
                        val fix = actions.current()
                        if (epoch == searchEpoch) {
                            select(fix.place)
                            status = "Current fix accuracy: ${fix.accuracyMeters.toInt()} m. Review the pin before saving."
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { if (epoch == searchEpoch) error = failure.message ?: "Current location unavailable. Retry or search an address." }
                    finally { if (epoch == searchEpoch) locating = false }
                }
            })
            CageButton("Allow precise foreground location", onClick = actions.requestForeground)
            CageButton("Open device location settings", onClick = actions.openDeviceSettings)
            selectedLabel?.let { Text("Selected: $it", style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(radius, { radius = it; confirmed = false; reviewing = true; loadedMap = null }, label = { Text("Radius (meters)") },
                modifier = Modifier.fillMaxWidth().testTag("officeRadius"), singleLine = true)
            if (reviewing) {
                val lat = latitude.toDoubleOrNull(); val lon = longitude.toDoubleOrNull()
                if (lat != null && lon != null) {
                    OfficeMapReview(loadedMap?.takeIf { it.request == currentMapRequest() }?.tile,
                        lat, lon, radius.toFloatOrNull()?.coerceIn(50f, 5000f) ?: 150f) { newLat, newLon ->
                        latitude = newLat.toString(); longitude = newLon.toString(); confirmed = false; loadedMap = null
                    }
                }
                if (loadedMap?.request != currentMapRequest()) CageButton("Retry map", onClick = { loadedMap = null; tileEpoch++ })
                CageButton("Confirm pin and radius", onClick = {
                    if (radius.toFloatOrNull()?.let { it in 50f..5000f } != true) error = "Radius must be between 50 and 5000 meters."
                    else if (!manualSelection && loadedMap?.request != currentMapRequest())
                        error = "Wait for the current pin and radius map, or use Advanced manual coordinates."
                    else { confirmed = true; reviewing = false; error = null }
                })
            }
            CageButton(if (advanced) "Hide Advanced" else "Advanced: coordinates and walking grace", onClick = { advanced = !advanced })
            if (advanced) {
                OutlinedTextField(latitude, { latitude = it; confirmed = false; reviewing = false; loadedMap = null }, label = { Text("Latitude (degrees)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(longitude, { longitude = it; confirmed = false; reviewing = false; loadedMap = null }, label = { Text("Longitude (degrees)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                CageButton("Review manual coordinates", onClick = {
                    val lat = latitude.toDoubleOrNull(); val lon = longitude.toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) error = "Enter valid latitude and longitude."
                    else { selectedLabel = "Manual coordinates"; manualSelection = true; reviewing = true; confirmed = false; loadedMap = null; tileEpoch++; error = null }
                })
                OutlinedTextField(entryGrace, { entryGrace = it }, label = { Text("Entry grace (minutes)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(exitGrace, { exitGrace = it }, label = { Text("Exit grace (minutes)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = CageStyle.Tight)) {
                Text("Enabled for detection", style = MaterialTheme.typography.bodyMedium)
                Switch(enabled, { enabled = it }, modifier = Modifier.align(Alignment.End).semantics { contentDescription = "Enabled for detection" })
            }
            Column(Modifier.fillMaxWidth().padding(vertical = CageStyle.Tight)) {
                Text("Counts toward attendance", style = MaterialTheme.typography.bodyMedium)
                Switch(eligible, { eligible = it }, modifier = Modifier.align(Alignment.End).semantics { contentDescription = "Counts toward attendance" })
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
                CageButton("Save office", onClick = {
                    if (!confirmed) { error = "Review and confirm the office pin and radius before saving."; return@CageButton }
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
                }, modifier = Modifier.fillMaxWidth())
                CageButton("Cancel", onClick = {
                    searchEpoch++; searchJob?.cancel(); currentJob?.cancel()
                    searching = false; locating = false; editing = false; error = null
                }, modifier = Modifier.fillMaxWidth())
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
