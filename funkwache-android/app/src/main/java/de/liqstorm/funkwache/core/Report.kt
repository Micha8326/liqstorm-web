package de.liqstorm.funkwache.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text snapshot of everything the app currently sees, for sharing/archiving. */
object Report {
    fun build(): String {
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
        val sb = StringBuilder()
        sb.appendLine("FUNKWACHE – Umgebungsbericht ${df.format(Date())}")
        Hub.location.value?.let { sb.appendLine("Standort: %.5f, %.5f (±%.0f m)".format(it.latitude, it.longitude, it.accuracy)) }
        sb.appendLine()

        sb.appendLine("== WARNUNGEN (${Hub.alerts.value.size}) ==")
        Hub.alerts.value.take(50).forEach {
            sb.appendLine("[${it.severity.label}] ${df.format(Date(it.time))} ${it.source.label}: ${it.title}")
            sb.appendLine("    ${it.detail}")
        }
        sb.appendLine()

        sb.appendLine("== SYSTEM-CHECK ==")
        Hub.audit.value.forEach {
            sb.appendLine("[${if (it.ok) "OK" else it.severity.label}] ${it.title}: ${it.detail.replace('\n', ';')}")
        }
        sb.appendLine()

        sb.appendLine("== KAMERA & MIKROFON (letzte Nutzungen) ==")
        de.liqstorm.funkwache.scan.PrivacyMonitor.log.value.take(40).forEach {
            val dur = it.end?.let { e -> "${(e - it.start) / 1000} s" } ?: "läuft"
            sb.appendLine("${df.format(Date(it.start))} ${it.kind} (${it.detail}) $dur" +
                (if (it.screenOff) " BILDSCHIRM AUS" else "") + (if (it.inCall) " Telefonat" else ""))
        }
        sb.appendLine()

        val wifi = Hub.wifi.value
        sb.appendLine("== WLAN (${wifi.size}) ==")
        Hub.wifiConn.value?.let { c ->
            sb.appendLine("Verbunden: ${c.ssid} ${c.bssid} ${c.security ?: ""} ${c.rssi} dBm, IP ${c.ip}, VPN ${if (c.vpn) "ja" else "nein"}, Proxy ${c.proxy ?: "-"}, DNS ${c.dns.joinToString()}")
        }
        wifi.forEach {
            sb.appendLine("${it.bssid}  ${it.rssi} dBm  Kanal ${it.channel} (${it.band})  ${it.security}  „${it.ssid.ifEmpty { "<versteckt>" }}“" +
                (it.vendor?.let { v -> "  [$v]" } ?: "") + if (it.flags.isNotEmpty()) "  ! ${it.flags.joinToString()}" else "")
        }
        sb.appendLine()

        Hub.cell.value?.let { s ->
            sb.appendLine("== MOBILFUNK ==")
            sb.appendLine("Netz: ${s.operatorName} (${s.networkOperator}), SIM ${s.simOperator}, Roaming ${s.roaming}, Daten ${s.dataNetwork ?: "?"}")
            s.cells.forEach {
                sb.appendLine("${if (it.registered) "*" else " "} ${it.tech} ${it.mcc}-${it.mnc} Gebiet ${it.area} Zelle ${it.cid} PCI ${it.pci} Kanal ${it.arfcn} ${it.dbm} dBm")
            }
            sb.appendLine()
        }
        Hub.gnss.value?.let { g ->
            sb.appendLine("== GNSS ==")
            sb.appendLine("${g.sats.size} Satelliten, ${g.used} genutzt, Ø C/N0 %.1f dB-Hz, Streuung %.2f".format(g.meanCn0, g.stdCn0))
            sb.appendLine()
        }
        val ble = Hub.ble.value
        sb.appendLine("== BLUETOOTH (${ble.size}) ==")
        ble.forEach {
            sb.appendLine(
                "${it.address}  ${it.rssi} dBm  ${fmtDist(it.distance)}  ${it.kind.label}  ${it.label}" +
                    (it.vendor?.let { v -> "  [$v]" } ?: "") +
                    (it.tracker?.let { t -> "  TRACKER: ${t.label}${if (it.separated) " (getrennt)" else ""}" } ?: "")
            )
        }
        sb.appendLine()
        return sb.toString()
    }
}
