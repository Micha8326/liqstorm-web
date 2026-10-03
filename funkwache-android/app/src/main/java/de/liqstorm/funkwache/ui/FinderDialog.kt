package de.liqstorm.funkwache.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.ago
import de.liqstorm.funkwache.core.fmtDist
import kotlinx.coroutines.delay

/** "Geiger counter" for a single device: live signal strength, trend and beeps. */
@Composable
fun FinderDialog(t: FinderTarget, onClose: () -> Unit) {
    val ble by Hub.ble.collectAsState()
    val wifi by Hub.wifi.collectAsState()
    val bleDev = if (!t.wifi) ble.firstOrNull { it.address == t.id } else null
    val wifiNet = if (t.wifi) wifi.firstOrNull { it.bssid == t.id } else null
    val rssi: Double? = bleDev?.rssiSmooth ?: wifiNet?.rssi?.toDouble()
    val lastSeen = bleDev?.lastSeen ?: wifiNet?.lastSeen
    val stale = lastSeen == null || System.currentTimeMillis() - lastSeen > 10_000
    var beep by remember { mutableStateOf(true) }
    val history = remember { mutableStateListOf<Double>() }
    val current by rememberUpdatedState(if (stale) null else rssi)

    LaunchedEffect(rssi) {
        if (rssi != null) {
            history += rssi
            while (history.size > 20) history.removeAt(0)
        }
    }
    LaunchedEffect(beep) {
        if (!beep) return@LaunchedEffect
        val tg = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            null
        }
        try {
            while (true) {
                val r = current
                if (r != null) tg?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                // -35 dBm -> 100 ms, -100 dBm -> 1500 ms
                val ms = if (r == null) 1500L else (100 + (1400 * (1 - rssiFrac(r)))).toLong()
                delay(ms)
            }
        } finally {
            tg?.release()
        }
    }

    val trend = if (history.size >= 8) {
        val half = history.size / 2
        history.takeLast(half).average() - history.take(half).average()
    } else 0.0

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        title = { Text("Orten: ${t.label}", maxLines = 2) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(t.id, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (rssi == null) "–" else "%.0f dBm".format(rssi),
                    fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    color = if (rssi == null || stale) Muted else rssiColor(rssi)
                )
                if (rssi != null) {
                    SignalBar(rssi, Modifier.padding(vertical = 8.dp))
                    bleDev?.let { Text("Entfernung ${fmtDist(it.distance)} (grob)", color = Muted, fontSize = 13.sp) }
                    Text(
                        when {
                            stale -> "Kein aktuelles Signal (${lastSeen?.let { ago(it) } ?: "nie"}) – Gerät außer Reichweite oder sendet selten"
                            trend > 3 -> "▲ wärmer – du kommst näher"
                            trend < -3 -> "▼ kälter – du entfernst dich"
                            else -> "● gleichbleibend"
                        },
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Text("Piepton", Modifier.weight(1f))
                    Switch(checked = beep, onCheckedChange = { beep = it })
                }
                Text(
                    if (t.wifi) "WLAN-Werte aktualisieren sich nur alle 30 s (Android-Drosselung). Langsam bewegen."
                    else "Langsam gehen und das Handy schwenken – dein Körper dämpft das Signal. Bei ~-40 dBm bist du direkt dran. " +
                        "Typische Verstecke: Taschenfutter, Jackentaschen, Radkasten, Stoßstange, unter dem Sitz.",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    )
}
