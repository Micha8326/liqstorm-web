package de.liqstorm.funkwache.model

enum class Severity(val label: String) {
    INFO("Info"), LOW("Niedrig"), MEDIUM("Mittel"), HIGH("Hoch")
}

enum class Source(val label: String) {
    BLE("Bluetooth LE"), BT("Bluetooth"), WIFI("WLAN"), CELL("Mobilfunk"),
    GNSS("GNSS/GPS"), SYSTEM("System"), NFC("NFC")
}

data class Alert(
    /** Dedupe key: the same key is not raised again within the dedupe window. */
    val key: String,
    val time: Long,
    val severity: Severity,
    val source: Source,
    val title: String,
    val detail: String,
)

enum class DeviceKind(val label: String) {
    TRACKER("Tracker"),
    PHONE("Smartphone/Tablet"),
    COMPUTER("Computer"),
    AUDIO("Audio"),
    WEARABLE("Wearable"),
    BEACON("Beacon"),
    TV("TV/Medien"),
    CAMERA("Kamera"),
    PERIPHERAL("Peripherie"),
    VEHICLE("Fahrzeug"),
    IOT("Smart Home/IoT"),
    HACKTOOL("Hacking-Tool"),
    UNKNOWN("Unbekannt"),
}

enum class TrackerType(val label: String) {
    APPLE_FINDMY("Apple Find My (AirTag o. ä.)"),
    SAMSUNG("Samsung SmartTag"),
    TILE("Tile"),
    CHIPOLO("Chipolo"),
    GOOGLE_FMDN("Google Find Hub"),
    DULT("Tracker (DULT-Standard)"),
}

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Exponentially smoothed RSSI, steadier for distance and finder mode. */
    val rssiSmooth: Double,
    val txPower: Int?,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val vendor: String?,
    val kind: DeviceKind,
    val label: String,
    val tracker: TrackerType?,
    /** Tracker reports it is away from its owner — the case that matters for stalking. */
    val separated: Boolean,
    val connectable: Boolean,
    val classic: Boolean,
    val bonded: Boolean,
    val services: List<String>,
    val notes: List<String>,
    val raw: String,
) {
    /** Rough distance estimate in metres (log-distance path loss model). */
    val distance: Double
        get() {
            val ref = txPower?.takeIf { it in -100..20 }?.let { it - 41 } ?: -59
            return Math.pow(10.0, (ref - rssiSmooth) / (10 * 2.4))
        }
}

data class WifiNet(
    val bssid: String,
    val ssid: String,
    val frequency: Int,
    val channel: Int,
    val band: String,
    val rssi: Int,
    val security: String,
    /** 0 open, 1 WEP, 2 WPA/OWE, 3 WPA2, 4 WPA3/Enterprise */
    val securityRank: Int,
    val capabilities: String,
    val vendor: String?,
    val localMac: Boolean,
    val standard: String?,
    val width: Int?,
    val wps: Boolean,
    val firstSeen: Long,
    val lastSeen: Long,
    val flags: List<String>,
) {
    val hidden get() = ssid.isEmpty()
}

data class WifiConnection(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val ip: String?,
    val security: String?,
    val securityRank: Int?,
    val vpn: Boolean,
    val proxy: String?,
    val privateDns: String?,
    val dns: List<String>,
)

data class CellObs(
    val tech: String,
    val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    /** LAC (2G/3G) or TAC (4G/5G) */
    val area: Int?,
    val cid: Long?,
    val pci: Int?,
    val arfcn: Int?,
    val dbm: Int?,
    val level: Int,
) {
    val key get() = "$tech-$mcc-$mnc-$cid"
    val hasId get() = cid != null
}

data class CellStatus(
    val time: Long,
    val operatorName: String?,
    val networkOperator: String?,
    val simOperator: String?,
    val roaming: Boolean,
    val dataNetwork: String?,
    val cells: List<CellObs>,
)

data class SatInfo(
    val constellation: String,
    val svid: Int,
    val cn0: Float,
    val used: Boolean,
    val elevation: Float,
)

data class GnssSnapshot(
    val time: Long,
    val sats: List<SatInfo>,
    val used: Int,
    val meanCn0: Double,
    val stdCn0: Double,
)

data class NfcTagInfo(
    val time: Long,
    val id: String,
    val techs: List<String>,
)

data class AuditItem(
    val title: String,
    val detail: String,
    val severity: Severity,
    val ok: Boolean,
    /** Settings intent action to fix the finding, if any. */
    val action: String? = null,
)
