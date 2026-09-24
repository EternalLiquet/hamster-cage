package dev.hamstercage.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(CageStyle.Small)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
    }
}

@Composable
fun Panel(warm: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (warm) CageStyle.Warm else CageStyle.Surface,
        border = BorderStroke(CageStyle.StrokeWidth, CageStyle.Outline),
    ) {
        Column(Modifier.padding(CageStyle.Space), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap), content = content)
    }
}

/** Status always has a readable label; accent color only reinforces it. */
@Composable
fun Tag(text: String, warm: Boolean = false) {
    Surface(color = if (warm) CageStyle.Warm else CageStyle.Raised, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = CageStyle.Small, vertical = CageStyle.Tight),
            style = MaterialTheme.typography.labelMedium, color = if (warm) CageStyle.Amber else CageStyle.Secondary)
    }
}

@Composable
fun MetricRow(label: String, value: String, emphasis: Boolean = false) {
    val ink = if (emphasis) CageStyle.Amber else CageStyle.Text
    val valueStyle = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    if (LocalDensity.current.fontScale >= CageStyle.LargeFontThreshold) {
        Column(verticalArrangement = Arrangement.spacedBy(CageStyle.Tight)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            Text(value, style = valueStyle, color = ink)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
            Text(value, Modifier.weight(1f), style = valueStyle, color = ink)
        }
    }
}

@Composable
fun Notice(title: String, body: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Panel {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = CageStyle.Amber)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = CageStyle.Secondary)
        if (actionLabel != null) CageButton(actionLabel, onAction)
    }
}

@Composable
fun CageButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick, modifier.defaultMinSize(minWidth = CageStyle.TouchTarget, minHeight = CageStyle.TouchTarget)) { Text(label) }
}

@Composable
fun FormError(error: String?) {
    if (error != null) Text("Error: $error", color = CageStyle.Danger, style = MaterialTheme.typography.bodyMedium)
}

@Preview(name = "Components · regular", widthDp = 360)
@Preview(name = "Components · large text", widthDp = 360, fontScale = 2f)
@Composable
private fun ComponentsPreview() {
    HamsterTheme {
        Column(Modifier.padding(CageStyle.Space), verticalArrangement = Arrangement.spacedBy(CageStyle.Gap)) {
            PageHeading("Preview fixture", "Synthetic design examples")
            Panel(warm = true) {
                Tag("NEEDS REVIEW", warm = true)
                MetricRow("Recorded time", "5h 42m", emphasis = true)
            }
            Notice("No records yet", "Saved attendance will appear here.", "Open setup")
            FormError("Please check the entered time.")
        }
    }
}
