package de.liqstorm.funkwache.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.ago
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.WifiNet

@Composable
fun WifiScreen(onFind: (FinderTarget) -> Unit) {
    val nets by Hub.wifi.collectAsState()
    val conn by Hub.wifiConn.collectAsState()
    val throttled by Hub.wifiThrottled.collectAsState()
    val lastScan by Hub.wifiLastScan.collectAsState()
    val trusted by Hub.trusted.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val c = conn
            Panel(border = c?.securityRank?.takeIf { it <= 1 }?.let { sevColor(Severity.MEDIUM) }) {
                Text("Aktuelle Verbindung", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (c == null || (c.ssid == null && c.bssid == null)) {
                    Hint("Nicht mit einem WLAN verbunden (oder SSID durch fehlende Standortberechtigung verborgen).")
                } else {
                    Text(c.ssid ?: "?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    KV("BSSID", c.bssid, mono = true)
                    KV("Sicherheit", c.security ?: "unbekannt")
                    KV("Signal", "${c.rssi} dBm · ${c.linkSpeed} Mbit/s")
                    KV("Frequenz", "${c.frequency} MHz")
                    KV("IP", c.ip)
                    KV("DNS", c.dns.joinToString().ifEmpty { null })
                    KV("Privates DNS", c.privateDns ?: "aus")
                    KV("VPN", if (c.vpn) "aktiv" else "nein")
                    KV("Proxy", c.proxy ?: "keiner")
                }
            }
        }
        item {
            Text(
                "${nets.size} Netze · letzter Scan ${if (lastScan > 0) ago(lastScan) else "–"}",
                fontWeight = FontWeight.Bold
            )
            if (throttled) Hint("Android drosselt WLAN-Scans (4 pro 2 Minuten). Für schnellere Suche: Entwickleroptionen › „WLAN-Scan-Drosselung“ ausschalten.")
        }
        item { ChannelChart(nets) }
        items(nets, key = { it.bssid }) { n ->
            WifiCard(n, n.bssid in trusted, expanded == n.bssid,
                onToggle = { expanded = if (expanded == n.bssid) null else n.bssid },
                onFind = { onFind(FinderTarget(true, n.bssid, n.ssid.ifEmpty { n.bssid })) })
        }
        item {
            Hint("Erkannt werden u. a.: Evil Twins (bekanntes Netz plötzlich offen), Karma-/Pineapple-Geräte, " +
                "Pentest-Hardware, offene/WEP-Netze, WPS, typische Kamera-Netznamen und Proxys auf deiner Verbindung.")
        }
    }
}

@Composable
private fun ChannelChart(nets: List<WifiNet>) {
    val ch24 = (1..13).associateWith { c -> nets.count { it.band == "2,4 GHz" && it.channel == c } }
    val max = (ch24.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Panel {
        Text("Kanalbelegung 2,4 GHz", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            ch24.forEach { (_, n) ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .padding(horizontal = 2.dp)
                            .fillMaxWidth()
                            .fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.03f))
                            .background(Accent.copy(alpha = if (n == 0) 0.15f else 0.7f), RoundedCornerShape(3.dp))
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            ch24.keys.forEach { Text("$it", Modifier.weight(1f), fontSize = 10.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        val b5 = nets.count { it.band == "5 GHz" }
        val b6 = nets.count { it.band == "6 GHz" }
        Hint("5 GHz: $b5 Netze · 6 GHz: $b6 Netze")
    }
}

@Composable
private fun WifiCard(n: WifiNet, trusted: Boolean, expanded: Boolean, onToggle: () -> Unit, onFind: () -> Unit) {
    val secColor = when (n.securityRank) {
        0 -> sevColor(Severity.MEDIUM)
        1 -> sevColor(Severity.HIGH)
        2 -> sevColor(Severity.LOW)
        else -> Ok
    }
    val danger = n.flags.any { it == "Pentest-Hardware" || it == "Angriffs-Tool-Name" }
    Panel(Modifier.clickable(onClick = onToggle), border = if (danger && !trusted) sevColor(Severity.HIGH) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.ssid.ifEmpty { "‹versteckt›" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    n.bssid + (n.vendor?.let { " · $it" } ?: ""),
                    fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${n.rssi} dBm", fontWeight = FontWeight.Bold, color = rssiColor(n.rssi.toDouble()), fontSize = 14.sp)
                Text("Kanal ${n.channel} · ${n.band}", fontSize = 11.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        SignalBar(n.rssi.toDouble())
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(n.security, secColor)
            if (trusted) Chip("vertraut", Ok)
            n.flags.filter { !it.startsWith("Unverschlüsselt") && !it.startsWith("WEP") }.forEach {
                Chip(it, if (it == "Pentest-Hardware" || it == "Angriffs-Tool-Name") sevColor(Severity.HIGH) else sevColor(Severity.LOW))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            KV("Standard", n.standard)
            KV("Kanalbreite", n.width?.let { "$it MHz" })
            KV("Frequenz", "${n.frequency} MHz")
            KV("Fähigkeiten", n.capabilities, mono = true)
            KV("Erstmals", ago(n.firstSeen))
            KV("Zuletzt", ago(n.lastSeen))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onFind) { Text("Orten") }
                OutlinedButton(onClick = { Hub.setTrusted(n.bssid, !trusted) }) {
                    Text(if (trusted) "Nicht mehr vertrauen" else "Vertraut")
                }
            }
        }
    }
}
