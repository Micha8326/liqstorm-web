package de.liqstorm.funkwache.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.core.ago
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.scan.PrivacyMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live camera/microphone usage and the log of past usage by other apps. */
@Composable
fun PrivacyPanel(act: MainActivity) {
    val log by PrivacyMonitor.log.collectAsState()
    val cam by PrivacyMonitor.cameraInUse.collectAsState()
    val mic by PrivacyMonitor.micInUse.collectAsState()
    val df = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)
    val dark = log.count { it.screenOff && !it.inCall }

    Panel(border = if (dark > 0) sevColor(Severity.HIGH) else null) {
        Text("Kamera & Mikrofon", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (cam) "KAMERA AKTIV" else "Kamera aus", if (cam) sevColor(Severity.MEDIUM) else Ok)
            Spacer(Modifier.width(6.dp))
            Chip(if (mic) "MIKROFON AKTIV" else "Mikrofon aus", if (mic) sevColor(Severity.MEDIUM) else Ok)
        }
        Hint(
            "Funkwache benutzt Kamera und Mikrofon selbst nie. Jeder Eintrag unten stammt von einer anderen App. " +
                "Welche App das war, zeigt Android im Datenschutz-Dashboard. Alarm gibt es, wenn eine App bei " +
                "ausgeschaltetem Bildschirm aufnimmt (außer bei Telefonaten)."
        )
        if (dark > 0) Text(
            "$dark Nutzung(en) bei ausgeschaltetem Bildschirm!", color = sevColor(Severity.HIGH),
            fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        if (log.isEmpty()) Hint("Noch keine Nutzung aufgezeichnet.")
        log.take(25).forEach { u ->
            val dur = u.end?.let { e -> "${(e - u.start) / 1000} s" } ?: "läuft gerade"
            val color = when {
                u.screenOff && !u.inCall -> sevColor(Severity.HIGH)
                u.active -> sevColor(Severity.MEDIUM)
                else -> Muted
            }
            Text(
                "${df.format(Date(u.start))}  ${if (u.kind == "Kamera") "📷" else "🎙️"} ${u.detail} · $dur" +
                    (if (u.screenOff) " · Bildschirm aus" else "") + (if (u.inCall) " · Telefonat" else ""),
                fontSize = 12.sp, color = color, modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        if (log.size > 25) Hint("… und ${log.size - 25} ältere (älteste ${ago(log.last().start)})")
        Row {
            OutlinedButton(onClick = { act.openSettings(Settings.ACTION_PRIVACY_SETTINGS) }) { Text("Datenschutz-Dashboard") }
            Spacer(Modifier.width(6.dp))
            if (log.isNotEmpty()) TextButton(onClick = { PrivacyMonitor.clear() }) { Text("Leeren") }
        }
    }
}
