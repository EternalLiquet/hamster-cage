package dev.hamstercage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hamstercage.domain.TimeSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Destination(val label: String, val description: String) {
    DASHBOARD("Dashboard", "Attendance totals will appear here when office capture and the attendance engine are connected."),
    OFFICES("Offices", "Office setup and location permissions are coming in the office capture features."),
    HISTORY("History", "Saved attendance and corrections will appear here. This shell has no attendance history."),
    SETTINGS("Settings", "Attendance policy and privacy controls are coming in the settings features."),
}

/** UI depends on domain contracts; the Activity supplies platform/data implementations. */
@Composable
fun HamsterApp(timeSource: TimeSource, zoneId: ZoneId = ZoneId.systemDefault()) {
    var selectedName by rememberSaveable { mutableStateOf(Destination.DASHBOARD.name) }
    val selected = Destination.valueOf(selectedName)
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selectedName = destination.name },
                        icon = { Text(destination.label.take(1)) },
                        label = { Text(destination.label) },
                    )
                }
            }
        }) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Hamster Cage", style = MaterialTheme.typography.titleMedium)
                Text(selected.label, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                Text(timeSource.localDate(zoneId).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")))
                Text(selected.description, style = MaterialTheme.typography.bodyLarge)
                Text("App shell preview · all four pages work offline. No attendance is collected yet.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
