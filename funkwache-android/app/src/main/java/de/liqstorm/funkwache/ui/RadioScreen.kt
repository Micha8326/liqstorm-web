package de.liqstorm.funkwache.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.ScanEngine
import de.liqstorm.funkwache.core.ago
import de.liqstorm.funkwache.model.CellObs
import de.liqstorm.funkwache.model.Severity
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
fun RadioScreen(act: MainActivity) {
    val ctx = LocalContext.current
    val cell by Hub.cell.collectAsState()
    val gnss by Hub.gnss.collectAsState()
    val loc by Hub.location.collectAsState()
    val tags by Hub.nfcTags.collectAsState()
    var nfcOn by remember { mutableStateOf(false) }

    // live satellite data only while this screen is visible
    DisposableEffect(Unit) {
        ScanEngine.setGps(ctx, true)
        onDispose {
            ScanEngine.setGps(ctx, false)
            act.setNfcReader(false)
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---------------------------------------------------------- cellular
        item { Section("Mobilfunk") }
        item {
            val s = cell
            Panel {
                if (s == null) {
                    Hint("Keine Zelldaten. Benötigt Standort-Berechtigung und eine SIM-Karte.")
                } else {
                    Text(s.operatorName?.ifEmpty { null } ?: "Unbekannter Betreiber", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    KV("Netz (MCC-MNC)", s.networkOperator)
                    KV("SIM", s.simOperator)
                    KV("Datenverbindung", s.dataNetwork)
                    KV("Roaming", if (s.roaming) "ja" else "nein")
                    KV("Aktualisiert", ago(s.time))
                }
            }
        }
        items(cell?.cells ?: emptyList()) { CellCard(it) }
        item {
            Hint("IMSI-Catcher-Indizien: erzwungener Wechsel auf 2G, bekannte Zell-ID mit anderer Gebietskennung, " +
                "häufige Gebietswechsel im Stillstand, extrem starke Zelle. Android 12+: In den Netzwerkeinstellungen „2G zulassen“ ausschalten.")
            OutlinedButton(onClick = { act.openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS) }) { Text("Mobilfunk-Einstellungen") }
        }

        // -------------------------------------------------------------- GNSS
        item { Section("Satelliten (GNSS)") }
        item {
            val g = gnss
            Panel {
                if (g == null) {
                    Hint("Warte auf Satellitendaten … (im Freien/am Fenster schneller)")
                } else {
                    Text("${g.sats.size} Satelliten · ${g.used} genutzt", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    KV("Ø Signal (C/N0)", "%.1f dB-Hz".format(g.meanCn0))
                    KV("Streuung", "%.2f dB".format(g.stdCn0) + if (g.sats.count { it.cn0 > 0 } >= 8 && g.stdCn0 < 1.5) "  ⚠ auffällig gleichmäßig" else "")
                    g.sats.groupBy { it.constellation }.forEach { (c, list) ->
                        KV(c, "${list.size} sichtbar, ${list.count { it.used }} genutzt, max ${"%.0f".format(list.maxOf { it.cn0 })} dB-Hz")
                    }
                    Spacer(Modifier.height(6.dp))
                    g.sats.filter { it.cn0 > 0 }.sortedByDescending { it.cn0 }.take(16).forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.constellation.take(3)} ${s.svid}", Modifier.width(76.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = if (s.used) Accent else Muted)
                            SignalBar(-100.0 + s.cn0 * 1.4, Modifier.weight(1f))
                            Text("%.0f".format(s.cn0), Modifier.width(30.dp).padding(start = 6.dp), fontSize = 11.sp)
                        }
                    }
                }
                loc?.let {
                    Spacer(Modifier.height(6.dp))
                    KV("Position", "%.5f, %.5f ±%.0f m (%s)".format(it.latitude, it.longitude, it.accuracy, it.provider))
                }
            }
            Hint("Spoofing zeigt sich oft durch gleich starke Signale aller Satelliten oder Positionssprünge, Jamming durch plötzlichen Totalausfall.")
        }

        // --------------------------------------------------------------- NFC
        item { Section("NFC") }
        item {
            Panel {
                if (!act.nfcAvailable()) {
                    Hint("Dieses Gerät hat kein NFC.")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("NFC-Tags auslesen", fontWeight = FontWeight.Bold)
                            Text(if (act.nfcEnabled()) "NFC ist an" else "NFC ist aus", fontSize = 12.sp, color = Muted)
                        }
                        Switch(checked = nfcOn, onCheckedChange = { nfcOn = it; act.setNfcReader(it) })
                    }
                    if (!act.nfcEnabled()) TextButton(onClick = { act.openSettings(Settings.ACTION_NFC_SETTINGS) }) { Text("NFC-Einstellungen") }
                    Hint("Halte Karten, Sticker oder verdächtige Aufkleber an die Rückseite. Zeigt UID und Technik – " +
                        "z. B. um fremde NFC-Tags an Fahrrad, Auto oder Tasche zu identifizieren.")
                    tags.forEach { t ->
                        KV(ago(t.time), "${t.id}\n${t.techs.joinToString()}", mono = true)
                    }
                }
            }
        }

        // ------------------------------------------------------- magnetometer
        item { Section("Magnet-Sonde") }
        item { MagnetProbe() }
    }
}

@Composable
private fun CellCard(c: CellObs) {
    Panel(border = if (c.tech == "GSM" && c.registered) sevColor(Severity.LOW) else if (c.registered) Accent else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${c.tech}${if (c.registered) " · verbunden" else " · Nachbar"}",
                    fontWeight = FontWeight.SemiBold, color = if (c.registered) Accent else Muted
                )
                Text(
                    listOfNotNull(
                        c.mcc?.let { "$it-${c.mnc}" },
                        c.area?.let { (if (c.tech in setOf("LTE", "NR")) "TAC " else "LAC ") + it },
                        c.cid?.let { "CID $it" },
                        c.pci?.let { "PCI $it" },
                        c.arfcn?.let { "Kanal $it" },
                    ).joinToString(" · "),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Muted
                )
            }
            Text(c.dbm?.let { "$it dBm" } ?: "–", fontWeight = FontWeight.Bold, color = rssiColor((c.dbm ?: -120).toDouble() + 15))
        }
    }
}

/**
 * Magnetometer as a near-field probe: speakers, motors, transformers and many hidden electronics
 * (cameras, GPS trackers with magnet mounts) distort the local field.
 */
@Composable
private fun MagnetProbe() {
    val ctx = LocalContext.current
    var value by remember { mutableFloatStateOf(0f) }
    var base by remember { mutableFloatStateOf(Float.NaN) }
    var peak by remember { mutableFloatStateOf(0f) }
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }

    DisposableEffect(sensor) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                value = value * 0.6f + m * 0.4f
                if (base.isNaN()) base = m
                peak = maxOf(peak, abs(value - base))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }

    Panel {
        if (sensor == null) {
            Hint("Kein Magnetfeldsensor vorhanden.")
            return@Panel
        }
        val delta = if (base.isNaN()) 0f else abs(value - base)
        val sev = when {
            delta > 60 -> Severity.HIGH
            delta > 25 -> Severity.MEDIUM
            delta > 10 -> Severity.LOW
            else -> null
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.0f µT".format(value), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("Abweichung %.0f µT · Spitze %.0f".format(delta, peak), color = sev?.let { sevColor(it) } ?: Muted, fontSize = 13.sp)
        }
        SignalBar(-100.0 + (delta.coerceAtMost(100f) * 0.65), Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { base = value; peak = 0f }) { Text("Nullen") }
        }
        Hint("Erdmagnetfeld ≈ 25–65 µT. Erst in freier Umgebung „Nullen“, dann langsam an Rauchmelder, Steckdosen, " +
            "Uhren, Lüftungsgitter, Autoradkästen oder Unterboden führen. Starke Ausschläge = Magnete/Elektronik " +
            "(z. B. Magnethalterung eines GPS-Trackers).")
    }
}
