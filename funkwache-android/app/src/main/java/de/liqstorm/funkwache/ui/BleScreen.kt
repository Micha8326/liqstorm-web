package de.liqstorm.funkwache.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.ago
import de.liqstorm.funkwache.core.duration
import de.liqstorm.funkwache.core.fmtDist
import de.liqstorm.funkwache.model.BleDevice
import de.liqstorm.funkwache.model.DeviceKind
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.scan.BleScanner

private enum class BleFilter(val label: String) { ALL("Alle"), TRACKER("Tracker"), NAMED("Mit Name"), RISK("Risiko"), NEAR("Nah") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScreen(act: MainActivity, onFind: (FinderTarget) -> Unit) {
    val ctx = LocalContext.current
    val devices by Hub.ble.collectAsState()
    val scanning by Hub.bleScanning.collectAsState()
    val discovering by Hub.classicDiscovering.collectAsState()
    val trusted by Hub.trusted.collectAsState()
    var filter by rememberSaveable { mutableStateOf(BleFilter.ALL) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val shown = devices.filter {
        when (filter) {
            BleFilter.ALL -> true
            BleFilter.TRACKER -> it.tracker != null || it.kind == DeviceKind.TRACKER
            BleFilter.NAMED -> it.name != null
            BleFilter.RISK -> it.kind == DeviceKind.HACKTOOL || it.separated || it.kind == DeviceKind.CAMERA ||
                it.notes.any { n -> "Kamera" in n }
            BleFilter.NEAR -> it.rssiSmooth > -65
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (scanning) "Scan läuft · ${devices.size} Geräte" else "Scan gestoppt",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${devices.count { it.tracker != null }} Tracker · ${devices.count { it.separated }} vom Besitzer getrennt",
                        fontSize = 12.sp, color = Muted
                    )
                }
                OutlinedButton(onClick = {
                    if (!BleScanner.startClassicDiscovery(ctx)) act.requestPerms()
                }, enabled = !discovering) {
                    if (discovering) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Klassisch", fontSize = 13.sp)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BleFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (shown.isEmpty()) item {
            Hint(
                if (devices.isEmpty()) "Noch keine Geräte. Bluetooth und Standort müssen an sein; die Berechtigungen „Geräte in der Nähe“ und „Standort“ werden benötigt."
                else "Keine Geräte für diesen Filter."
            )
        }
        items(shown, key = { it.address }) { d ->
            DeviceCard(
                d, d.address.uppercase() in trusted, expanded == d.address,
                onToggle = { expanded = if (expanded == d.address) null else d.address },
                onFind = { onFind(FinderTarget(false, d.address, d.label)) },
            )
        }
        item {
            Hint("Tipp: Tracker, die vom Besitzer getrennt sind (AirTag im Offline-Modus, SmartTag, Tile …), werden über Zeit und Ort beobachtet. " +
                "Folgt dir einer über mehrere hundert Meter, schlägt die App Alarm. Mit „Orten“ findest du ihn per Signalstärke.")
        }
    }
}

@Composable
private fun DeviceCard(d: BleDevice, trusted: Boolean, expanded: Boolean, onToggle: () -> Unit, onFind: () -> Unit) {
    val risk = when {
        d.kind == DeviceKind.HACKTOOL -> sevColor(Severity.HIGH)
        d.tracker != null && d.separated -> sevColor(Severity.MEDIUM)
        else -> null
    }
    Panel(Modifier.clickable(onClick = onToggle), border = if (trusted) null else risk) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kindIcon(d.kind), fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    d.address + (d.vendor?.let { " · $it" } ?: ""),
                    fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${d.rssi} dBm", fontWeight = FontWeight.Bold, color = rssiColor(d.rssiSmooth), fontSize = 14.sp)
                Text(fmtDist(d.distance), fontSize = 11.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        SignalBar(d.rssiSmooth)
        val chips = buildList {
            d.tracker?.let { add(it.label to if (d.separated) sevColor(Severity.MEDIUM) else Muted) }
            if (d.separated) add("vom Besitzer getrennt" to sevColor(Severity.MEDIUM))
            if (d.kind == DeviceKind.HACKTOOL) add("Hacking-Tool" to sevColor(Severity.HIGH))
            if (d.bonded) add("gekoppelt" to Ok)
            if (trusted) add("vertraut" to Ok)
            if (d.classic) add("klassisch" to Muted)
            add(d.kind.label to Muted)
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            chips.forEach { (t, c) -> Chip(t, c) }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            KV("Name", d.name)
            KV("Erstmals", ago(d.firstSeen))
            KV("Zuletzt", ago(d.lastSeen))
            KV("Pakete", "${d.seenCount}")
            KV("Sendeleistung", d.txPower?.let { "$it dBm" })
            KV("Verbindbar", if (d.connectable) "ja" else "nein")
            KV("Dienste", d.services.joinToString("\n").ifEmpty { null })
            d.notes.forEach { Hint("• $it") }
            BleScanner.follow.progress(d.address)?.let { p ->
                KV("Begleitet dich", "${duration(p.spanMs)}, ${p.sightings} Sichtungen, ${p.maxDistM.toInt()} m Bewegung")
            }
            if (d.raw.isNotEmpty()) KV("Rohdaten", d.raw, mono = true)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFind) { Text("Orten") }
                OutlinedButton(onClick = { Hub.setTrusted(d.address, !trusted) }) {
                    Text(if (trusted) "Nicht mehr vertrauen" else "Mein Gerät / vertraut")
                }
            }
        }
    }
}
