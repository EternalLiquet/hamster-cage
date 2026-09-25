package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.hamstercage.data.StorageState
import dev.hamstercage.privacy.PrivacyResetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class PrivacyActions(
    val deleteHistory: suspend () -> Unit,
    val retryPending: suspend () -> Unit,
    val resetAllAppData: () -> Boolean,
)

private enum class DeleteChoice { HISTORY, ALL }

/** Confirmation scope is intentionally not saved over Activity recreation. */
@Composable
fun PrivacyScreen(state: StorageState, reset: PrivacyResetState, actions: PrivacyActions) {
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<DeleteChoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val available = state is StorageState.Ready && reset is PrivacyResetState.Idle
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
        Panel {
            Text("Privacy and local data", style = MaterialTheme.typography.titleLarge)
            Text("Attendance, offices and settings are stored on this device. Uninstalling Hamster Cage or clearing its app storage deletes them. An ordinary app update or restart does not request deletion of local data.",
                style = MaterialTheme.typography.bodyMedium)
            Text("There is no in-app export or restore in this preview. Attendance works offline. Office setup can send an address you search to the device's geocoder and load the map area you review from OpenStreetMap; attendance history is not sent.",
                style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            when (reset) {
                is PrivacyResetState.Pending -> {
                    Text("History deletion is unfinished. Saved attendance is hidden while cleanup retries; do not treat the old record as current.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        busy = true; message = null
                        scope.launch {
                            try { actions.retryPending(); message = "Attendance and calendar history deleted." }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "Deletion is still pending. Reopen the app to retry." }
                            finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.testTag("privacy_retry")) { Text("Retry deletion") }
                }
                PrivacyResetState.Unavailable -> Text("Privacy reset state could not be read. Attendance is hidden to avoid showing an incomplete deletion. Reopen the app or reset all app data.",
                    style = MaterialTheme.typography.bodyMedium)
                is PrivacyResetState.Idle -> Unit
            }
            if (choice == null) {
                if (available) Button(onClick = { choice = DeleteChoice.HISTORY; message = null }, enabled = !busy,
                    modifier = Modifier.testTag("privacy_delete_history")) { Text("Delete attendance and calendar history") }
                TextButton(onClick = { choice = DeleteChoice.ALL; message = null }, enabled = !busy,
                    modifier = Modifier.testTag("privacy_reset_all")) { Text("Reset all app data") }
            }
        }
        if (choice != null) Panel(warm = true) {
            when (choice) {
                DeleteChoice.HISTORY -> {
                    Text("Delete attendance and calendar history?", style = MaterialTheme.typography.titleMedium)
                    Text("This removes all raw transitions, manual sessions, corrections, excluded dates and notes, WFH labels, coverage and active attendance totals. Offices and base policy settings stay. The app cannot recover deleted history. New observations can be recorded after setup recovers.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                DeleteChoice.ALL -> {
                    Text("Reset all app data?", style = MaterialTheme.typography.titleMedium)
                    Text("Android will clear every app-private database and preference, including attendance, calendar, offices, policy and capture health, and revoke app permissions. The app may close; reopen it to start fresh. The app cannot recover this data.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                null -> Unit
            }
            Button(onClick = {
                when (choice) {
                    DeleteChoice.HISTORY -> {
                        busy = true; message = null
                        scope.launch {
                            try { actions.deleteHistory(); message = "Attendance and calendar history deleted."; choice = null }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "Deletion is unfinished. Saved attendance is hidden; reopen the app or retry deletion."; choice = null }
                            finally { busy = false }
                        }
                    }
                    DeleteChoice.ALL -> {
                        busy = true; message = null
                        try {
                            if (actions.resetAllAppData()) message = "Full reset requested. Reopen the app to check the fresh state."
                            else message = "Android could not reset app data. Your saved data was kept."
                        } catch (_: Exception) { message = "Android could not reset app data. Your saved data was kept." }
                        finally { busy = false; choice = null }
                    }
                    null -> Unit
                }
            }, enabled = !busy && (choice == DeleteChoice.ALL || available),
                modifier = Modifier.testTag("privacy_confirm")) {
                Text(if (choice == DeleteChoice.HISTORY) "Delete history now" else "Reset all data and close")
            }
            TextButton(onClick = { choice = null; message = null }, enabled = !busy,
                modifier = Modifier.testTag("privacy_cancel")) { Text("Cancel; keep all data") }
        }
    }
}
