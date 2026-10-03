package de.liqstorm.funkwache.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.core.Prefs
import de.liqstorm.funkwache.core.ScanEngine
import de.liqstorm.funkwache.core.ago
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.scan.BleScanner
import de.liqstorm.funkwache.scan.Positioning
import de.liqstorm.funkwache.scan.WifiScanner
import de.liqstorm.funkwache.service.GuardService

@Composable
fun DashboardScreen(act: MainActivity, onTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val alerts by Hub.alerts.collectAsState()
    val ble by Hub.ble.collectAsState()
    val wifi by Hub.wifi.collectAsState()
    val cell by Hub.cell.collectAsState()
    val guard by Hub.guardRunning.collectAsState()
    val tick by Hub.envTick.collectAsState()

    val now = System.currentTimeMillis()
    val recent = alerts.filter { now - it.time < 86_400_000L }
    val worst = recent.maxOfOrNull { it.severity.ordinal } ?: -1
    val (statusText, statusColor) = when {
        worst >= Severity.HIGH.ordinal -> "Gefahr erkannt" to sevColor(Severity.HIGH)
        worst >= Severity.MEDIUM.ordinal -> "Warnung" to sevColor(Severity.MEDIUM)
        worst >= Severity.LOW.ordinal -> "Auffälligkeiten" to sevColor(Severity.LOW)
        else -> "Alles ruhig" to Ok
    }

    // environment problems (recomputed whenever envTick changes)
    val missing = remember(tick) { Perms.missing(ctx) }
    val btOn = remember(tick) { BleScanner.isEnabled(ctx) }
    val locOn = remember(tick) { Positioning.locationEnabled(ctx) }
    val wifiOn = remember(tick) { WifiScanner.isEnabled(ctx) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(border = statusColor) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).background(statusColor.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (worst >= Severity.MEDIUM.ordinal) Icons.Filled.Warning else Icons.Filled.Lock, null, tint = statusColor)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(statusText, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = statusColor)
                        Text(
                            "${recent.size} Ereignis(se) in 24 h · " + if (guard) "Wächter aktiv" else "Wächter aus",
                            color = Muted, fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (missing.isNotEmpty() || !btOn || !locOn || !wifiOn) item {
            Panel(border = sevColor(Severity.LOW)) {
                Text("Eingeschränkte Erkennung", fontWeight = FontWeight.Bold)
                if (missing.isNotEmpty()) {
                    Hint("Fehlende Berechtigungen: ${missing.joinToString { it.substringAfterLast('.') }}")
                    Button(onClick = { act.requestPerms() }) { Text("Berechtigungen erteilen") }
                }
                if (!btOn) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bluetooth ist aus – keine Tracker-Erkennung.", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { act.enableBluetooth() }) { Text("Einschalten") }
                }
                if (!locOn) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Standort ist aus – Android liefert dann keine WLAN-/BLE-Scans.", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { act.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Öffnen") }
                }
                if (!wifiOn) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WLAN ist aus – keine Netz-Analyse (Scannen geht auch ohne Verbindung).", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { act.openSettings(Settings.ACTION_WIFI_SETTINGS) }) { Text("Öffnen") }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Bluetooth", "${ble.size}", Modifier.weight(1f)) { onTab(1) }
                Stat("Tracker", "${ble.count { it.tracker != null }}", Modifier.weight(1f),
                    if (ble.any { it.separated }) sevColor(Severity.MEDIUM) else null) { onTab(1) }
                Stat("WLANs", "${wifi.size}", Modifier.weight(1f)) { onTab(2) }
                Stat("Zellen", "${cell?.cells?.size ?: 0}", Modifier.weight(1f)) { onTab(3) }
            }
        }

        item { GuardPanel(act, guard) }

        item {
            Section("Ereignisse") {
                if (alerts.isNotEmpty()) TextButton(onClick = { Hub.clearAlerts() }) { Text("Alle löschen") }
            }
        }
        if (alerts.isEmpty()) item {
            Hint("Noch keine Auffälligkeiten. Die App prüft laufend auf Tracker, die dir folgen, gefälschte WLANs, " +
                "BLE-Spam, Hacking-Tools, verdächtige Funkzellen (IMSI-Catcher), GPS-Spoofing und Änderungen an " +
                "Sicherheitseinstellungen deines Handys.")
        }
        items(alerts.take(150)) { AlertCard(it) }

        item {
            Section("Grenzen")
            Hint("Android gibt Apps keinen Zugriff auf Rohfunk: WLAN-Deauth-Angriffe, Sub-GHz (433/868 MHz), " +
                "UWB-Signale und versteckte Kameras ohne WLAN/Bluetooth sind mit Handy-Hardware nicht messbar. " +
                "IMSI-Catcher- und Spoofing-Erkennung arbeiten mit Indizien, nicht mit Beweisen.")
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color? = null, onClick: () -> Unit) {
    Panel(modifier.clickable(onClick = onClick)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color ?: Accent)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun GuardPanel(act: MainActivity, running: Boolean) {
    var boot by remember { mutableStateOf(Prefs.startOnBoot) }
    var gnss by remember { mutableStateOf(Prefs.gnssGuard) }
    var low by remember { mutableStateOf(Prefs.notifyLow) }
    var expanded by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hintergrund-Wächter", fontWeight = FontWeight.Bold)
                Text(
                    if (running) ScanEngine.summary() else "Überwacht dauerhaft – auch bei geschlossener App",
                    fontSize = 12.sp, color = Muted
                )
            }
            Switch(checked = running, onCheckedChange = {
                if (it) {
                    if (!Perms.location(act)) act.requestPerms() else GuardService.start(act)
                } else GuardService.stop(act)
            })
        }
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "Optionen ausblenden" else "Optionen", fontSize = 13.sp)
        }
        if (expanded) {
            OptionRow("Nach Neustart automatisch starten", boot) { boot = it; Prefs.startOnBoot = it }
            OptionRow("GPS im Hintergrund (Spoofing-/Jamming-Erkennung, genauere Verfolgungserkennung, mehr Akku)", gnss) {
                gnss = it; Prefs.gnssGuard = it; ScanEngine.restart(act)
            }
            OptionRow("Auch niedrige Warnungen als Benachrichtigung", low) { low = it; Prefs.notifyLow = it }
            Hint("Tipp: Funkwache in den Akku-Einstellungen von der Optimierung ausnehmen, sonst beendet Android den Wächter evtl.")
            OutlinedButton(onClick = { act.openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) {
                Text("Akku-Optimierung öffnen")
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun AlertCard(a: Alert) {
    Panel(border = sevColor(a.severity).takeIf { a.severity.ordinal >= Severity.MEDIUM.ordinal }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SevChip(a.severity)
            Spacer(Modifier.width(6.dp))
            Chip(a.source.label)
            Spacer(Modifier.weight(1f))
            Text(ago(a.time), color = Muted, fontSize = 11.sp)
            IconButton(onClick = { Hub.dismiss(a) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, "Ausblenden", tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(a.title, fontWeight = FontWeight.Bold)
        Text(a.detail, fontSize = 13.sp, color = Color(0xFFC9D4E0), lineHeight = 18.sp)
    }
}
