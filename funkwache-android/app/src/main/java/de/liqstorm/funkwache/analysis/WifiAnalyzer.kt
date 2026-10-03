package de.liqstorm.funkwache.analysis

import de.liqstorm.funkwache.core.Oui
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source
import de.liqstorm.funkwache.model.WifiConnection
import de.liqstorm.funkwache.model.WifiNet

object WifiAnalyzer {

    /** Parses Android's capability string, e.g. "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]". */
    fun security(cap: String): Pair<String, Int> {
        val c = cap.uppercase()
        return when {
            "EAP-SUITE-B" in c -> "WPA3-Enterprise" to 4
            "SAE" in c && "PSK" in c -> "WPA2/WPA3-Mischbetrieb" to 3
            "SAE" in c -> "WPA3" to 4
            "EAP" in c -> "WPA2-Enterprise" to 4
            "OWE" in c -> "OWE (Enhanced Open)" to 2
            "RSN" in c || "WPA2" in c -> (if ("TKIP" in c && "CCMP" !in c) "WPA2 (TKIP)" else "WPA2") to 3
            "WPA" in c -> "WPA (veraltet)" to 2
            "WEP" in c -> "WEP (unsicher)" to 1
            else -> "Offen" to 0
        }
    }

    fun channel(freq: Int): Int = when (freq) {
        2484 -> 14
        in 2412..2472 -> (freq - 2407) / 5
        in 5160..5885 -> (freq - 5000) / 5
        in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    fun band(freq: Int): String = when {
        freq < 3000 -> "2,4 GHz"
        freq < 5925 -> "5 GHz"
        else -> "6 GHz"
    }

    // Short tokens must stand alone ("IPC_3F2A", "CAM-01"), so "Camping" does not match.
    private val cameraPattern = Regex(
        "(?<![a-z])(cam|ipc|ipcam|camera|dvr|nvr|spycam)(?![a-z])|hdwifi|wificam|wifi_cam|v380|yoosee|xmeye|icsee|" +
            "lookcam|hidvcam|ezviz|tapo_c|imou|reolink|eufycam|gw_ap|mv[0-9]{3,}",
        RegexOption.IGNORE_CASE
    )
    private val attackPattern = Regex(
        "pineapple|marauder|deauth|wifiphisher|fluxion|airgeddon|evil ?twin|pwn|hak5|esp32-?attack",
        RegexOption.IGNORE_CASE
    )

    /** Per-network flags shown in the list. */
    fun flags(n: WifiNet): List<String> {
        val f = mutableListOf<String>()
        when (n.securityRank) {
            0 -> f += "Unverschlüsselt"
            1 -> f += "WEP – in Minuten knackbar"
            2 -> if (n.security.startsWith("WPA (")) f += "WPA1/TKIP veraltet"
        }
        if (n.wps) f += "WPS aktiv (PIN-Angriffe möglich)"
        if (n.hidden) f += "Versteckte SSID"
        if (cameraPattern.containsMatchIn(n.ssid)) f += "Mögliche Kamera"
        if (attackPattern.containsMatchIn(n.ssid)) f += "Angriffs-Tool-Name"
        if (Oui.isPentestHardware(n.bssid)) f += "Pentest-Hardware"
        if (n.vendor?.startsWith("Espressif") == true) f += "ESP32/ESP8266-Modul"
        if (n.localMac) f += "Zufalls-/Hotspot-MAC"
        return f
    }

    fun analyze(nets: List<WifiNet>, conn: WifiConnection?, known: Map<String, Int>, now: Long): List<Alert> {
        val out = ArrayList<Alert>()
        val visible = nets.filter { it.ssid.isNotEmpty() }

        // 1) Evil twin: a network we know as encrypted appears with weaker security.
        for (n in visible) {
            val knownRank = known[n.ssid] ?: continue
            if (knownRank >= 2 && n.securityRank < knownRank && n.securityRank <= 1) {
                out += Alert(
                    "eviltwin-${n.ssid}-${n.bssid}", now, Severity.HIGH, Source.WIFI,
                    "Evil-Twin-Verdacht: „${n.ssid}“",
                    "Dein bekanntes Netz „${n.ssid}“ ist normalerweise verschlüsselt, wird hier aber " +
                        "${n.security} von ${n.bssid} ausgestrahlt. Nicht verbinden – Auto-Verbinden für dieses Netz deaktivieren."
                )
            }
        }

        // 2) Same SSID offered open and encrypted at the same time.
        visible.groupBy { it.ssid }.forEach { (ssid, group) ->
            val ranks = group.map { it.securityRank }.toSet()
            if (ranks.size > 1 && 0 in ranks && ranks.any { it >= 2 }) {
                val open = group.filter { it.securityRank == 0 }.joinToString { it.bssid }
                out += Alert(
                    "mixedsec-$ssid", now, Severity.MEDIUM, Source.WIFI,
                    "„$ssid“ offen UND verschlüsselt sichtbar",
                    "Dasselbe Netz wird zusätzlich unverschlüsselt angeboten ($open). Typisch für einen gefälschten Zugangspunkt."
                )
            }
        }

        // 3) Karma / WiFi Pineapple: one radio announcing many different open SSIDs.
        visible.filter { it.securityRank == 0 }
            .groupBy { it.bssid.uppercase().take(14) } // first 5 octets
            .forEach { (prefix, group) ->
                val ssids = group.map { it.ssid }.toSet()
                if (ssids.size >= 3) {
                    out += Alert(
                        "karma-$prefix", now, Severity.MEDIUM, Source.WIFI,
                        "Ein Gerät funkt ${ssids.size} offene Netze",
                        "Ein Sender ($prefix:xx) bietet gleichzeitig die offenen Netze ${ssids.take(6).joinToString()} an. " +
                            "So arbeiten Karma-Angriffe/WiFi Pineapple, um Geräte anzulocken."
                    )
                }
            }

        // 4) Hardware and names associated with attacks.
        for (n in nets) {
            if (Oui.isPentestHardware(n.bssid)) {
                out += Alert(
                    "pentest-${n.bssid}", now, Severity.HIGH, Source.WIFI,
                    "Pentest-Hardware: ${n.vendor}",
                    "Zugangspunkt „${n.ssid.ifEmpty { "versteckt" }}“ (${n.bssid}) stammt von ${n.vendor}. " +
                        "Solche Geräte werden für Man-in-the-Middle-Angriffe eingesetzt."
                )
            } else if (n.ssid.isNotEmpty() && attackPattern.containsMatchIn(n.ssid)) {
                out += Alert(
                    "attackname-${n.bssid}", now, Severity.MEDIUM, Source.WIFI,
                    "Verdächtiger Netzname „${n.ssid}“",
                    "Der Name deutet auf ein WLAN-Angriffswerkzeug hin (${n.bssid}, ${n.rssi} dBm)."
                )
            }
            if (n.ssid.isNotEmpty() && cameraPattern.containsMatchIn(n.ssid)) {
                out += Alert(
                    "camera-${n.bssid}", now, Severity.LOW, Source.WIFI,
                    "Mögliche WLAN-Kamera: „${n.ssid}“",
                    "Netzname passt zu typischen IP-/Mini-Kameras (${n.bssid}, ${n.rssi} dBm). " +
                        "In Ferienwohnung/Hotel: mit WLAN › Orten die Richtung suchen."
                )
            }
        }

        // 5) Current connection.
        if (conn?.ssid != null) {
            when (conn.securityRank) {
                0 -> out += Alert(
                    "conn-open-${conn.ssid}", now, Severity.MEDIUM, Source.WIFI,
                    "Verbunden mit offenem WLAN",
                    "„${conn.ssid}“ ist unverschlüsselt – Datenverkehr kann mitgelesen und manipuliert werden. VPN nutzen."
                )
                1 -> out += Alert(
                    "conn-wep-${conn.ssid}", now, Severity.HIGH, Source.WIFI,
                    "Verbunden mit WEP-Netz",
                    "WEP ist seit Jahren gebrochen. „${conn.ssid}“ bietet keinen echten Schutz."
                )
            }
            if (conn.proxy != null) {
                out += Alert(
                    "proxy-${conn.proxy}", now, Severity.HIGH, Source.WIFI,
                    "HTTP-Proxy aktiv: ${conn.proxy}",
                    "Dein Datenverkehr wird über einen Proxy geleitet. Wenn du das nicht selbst eingerichtet hast, " +
                        "ist das ein Man-in-the-Middle-Anzeichen (WLAN-Einstellungen › Proxy)."
                )
            }
            val dup = visible.filter { it.ssid == conn.ssid && it.bssid != conn.bssid && it.securityRank < (conn.securityRank ?: 0) }
            for (d in dup) {
                out += Alert(
                    "conn-twin-${d.bssid}", now, Severity.HIGH, Source.WIFI,
                    "Schwächere Kopie deines WLANs",
                    "Neben deinem Zugangspunkt sendet ${d.bssid} ebenfalls „${conn.ssid}“, aber mit ${d.security}."
                )
            }
        }
        return out
    }
}
