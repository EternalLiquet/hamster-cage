package dev.hamstercage.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

fun minutesText(value: Double): String {
    val minutes = abs(value).roundToLong()
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
fun balanceText(value: Double): String = (if(value < -0.5) "−" else if(value > 0.5) "+" else "") + minutesText(value)
fun instantText(value: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("h:mm a").withZone(zone).format(value)
fun readable(value: String): String = value.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=CageStyle.Space).padding(top=12.dp,bottom=30.dp), verticalArrangement=Arrangement.spacedBy(CageStyle.Gap), content=content)
}

@Composable
fun PageHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=12.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(title,style=MaterialTheme.typography.headlineLarge)
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        }
        action?.invoke()
    }
}

@Composable
fun Panel(warm: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(CageStyle.Radius), color=if(warm) CageStyle.Warm else CageStyle.Surface, border=BorderStroke(1.dp,CageStyle.Outline)) {
        Column(Modifier.padding(CageStyle.Space),verticalArrangement=Arrangement.spacedBy(CageStyle.Gap),content=content)
    }
}

@Composable
fun Tag(text: String, warm: Boolean = false) {
    Surface(color=if(warm) CageStyle.Amber.copy(alpha=0.12f) else CageStyle.Raised,shape=RoundedCornerShape(6.dp)) {
        Text(text,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium,color=if(warm) CageStyle.Amber else CageStyle.Secondary)
    }
}

@Composable
fun MetricRow(label: String, value: String, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        Text(value,Modifier.weight(1f),style=if(emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,color=if(emphasis) CageStyle.Amber else CageStyle.Text)
    }
}

@Composable
fun Notice(title: String, body: String, actionLabel: String?=null, onAction: () -> Unit = {}) {
    Panel {
        Text(title,style=MaterialTheme.typography.titleMedium,color=CageStyle.Amber)
        Text(body,style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        if(actionLabel != null) OutlinedButton(onClick=onAction) { Text(actionLabel) }
    }
}

@Composable
fun LabeledToggle(title: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.bodyLarge)
            if(description!=null) Text(description,style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        }
        Switch(checked=checked,onCheckedChange=onChange)
    }
}

@Composable
fun FormDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).heightIn(max=740.dp),shape=RoundedCornerShape(24.dp),color=CageStyle.Surface) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(CageStyle.Space),verticalArrangement=Arrangement.spacedBy(CageStyle.Gap)) {
                Text(title,style=MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }
}

@Composable
fun FormError(error: String?) {
    if(error != null) Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
}

@Composable
fun FormActions(onCancel: () -> Unit, onSave: () -> Unit, saveText: String="Save", enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        TextButton(onClick=onCancel) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick=onSave,enabled=enabled) { Text(saveText) }
    }
}
