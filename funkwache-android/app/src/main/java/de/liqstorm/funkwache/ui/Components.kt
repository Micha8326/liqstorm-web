package de.liqstorm.funkwache.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.model.DeviceKind
import de.liqstorm.funkwache.model.Severity

val Accent = Color(0xFF22D3EE)
val Ok = Color(0xFF34D399)
val Muted = Color(0xFF8B98A9)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00151A),
    secondary = Color(0xFF818CF8),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF151C26),
    onSurfaceVariant = Color(0xFFB4C0CE),
    surfaceContainer = Color(0xFF121821),
    error = Color(0xFFF87171),
)

@Composable
fun FunkTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

fun sevColor(s: Severity): Color = when (s) {
    Severity.INFO -> Color(0xFF60A5FA)
    Severity.LOW -> Color(0xFFFACC15)
    Severity.MEDIUM -> Color(0xFFFB923C)
    Severity.HIGH -> Color(0xFFF87171)
}

fun kindIcon(k: DeviceKind) = when (k) {
    DeviceKind.TRACKER -> "📍"
    DeviceKind.PHONE -> "📱"
    DeviceKind.COMPUTER -> "💻"
    DeviceKind.AUDIO -> "🎧"
    DeviceKind.WEARABLE -> "⌚"
    DeviceKind.BEACON -> "📡"
    DeviceKind.TV -> "📺"
    DeviceKind.CAMERA -> "📷"
    DeviceKind.PERIPHERAL -> "⌨️"
    DeviceKind.VEHICLE -> "🚗"
    DeviceKind.IOT -> "💡"
    DeviceKind.HACKTOOL -> "☠️"
    DeviceKind.UNKNOWN -> "❔"
}

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = border?.let { androidx.compose.foundation.BorderStroke(1.dp, it.copy(alpha = 0.6f)) },
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun Chip(text: String, color: Color = Muted) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SevChip(s: Severity) = Chip(s.label.uppercase(), sevColor(s))

@Composable
fun Section(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        trailing?.invoke()
    }
}

@Composable
fun KV(k: String, v: String?, mono: Boolean = false) {
    if (v.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.width(118.dp))
        Text(v, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

/** Maps RSSI (dBm) to 0..1 for bars. */
fun rssiFrac(rssi: Double) = ((rssi + 100) / 65).toFloat().coerceIn(0f, 1f)

fun rssiColor(rssi: Double): Color = when {
    rssi > -55 -> Color(0xFFF87171)
    rssi > -70 -> Color(0xFFFB923C)
    rssi > -85 -> Color(0xFFFACC15)
    else -> Muted
}

@Composable
fun SignalBar(rssi: Double, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { rssiFrac(rssi) },
        modifier = modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = rssiColor(rssi),
        trackColor = Color(0x22FFFFFF),
        drawStopIndicator = {},
    )
}

@Composable
fun Hint(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
}
