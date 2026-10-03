package de.liqstorm.funkwache.scan

import de.liqstorm.funkwache.core.Oui
import de.liqstorm.funkwache.core.hex
import de.liqstorm.funkwache.core.u
import de.liqstorm.funkwache.model.DeviceKind
import de.liqstorm.funkwache.model.TrackerType
import java.util.UUID

/**
 * Classifies BLE advertisements. Free of Android types so it can be unit-tested on the JVM.
 *
 * Sources for the formats: Apple Continuity research (furiousMAC, seemoo-lab AirGuard),
 * Google Find Hub / Fast Pair specs, IETF DULT draft, Bluetooth SIG assigned numbers.
 */
object BleParser {

    data class Info(
        val kind: DeviceKind,
        val label: String,
        val vendor: String?,
        val tracker: TrackerType?,
        val separated: Boolean,
        /** Frame type that triggers pairing pop-ups – used by BLE-spam attacks. */
        val popup: Boolean,
        val hackTool: Boolean,
        val cameraWearable: Boolean,
        val notes: List<String>,
        val services: List<String>,
    ) {
        /** Relevant for "is this following me" detection. */
        val followRelevantTracker get() = tracker != null && (tracker != TrackerType.APPLE_FINDMY || separated)
    }

    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    fun uuid16(v: Int): UUID = UUID.fromString("0000%04x".format(v) + BASE_SUFFIX)

    fun short(u: UUID): Int? {
        val s = u.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX)) s.substring(4, 8).toInt(16) else null
    }

    /** Detecting Unwanted Location Trackers (IETF DULT) non-owner service. */
    val DULT_UUID: UUID = UUID.fromString("15190001-12f4-c226-88ed-2ac5579f2a85")

    val serviceNames: Map<Int, String> = mapOf(
        0x1800 to "Generic Access", 0x1801 to "Generic Attribute", 0x180A to "Geräteinfo",
        0x180F to "Akku", 0x180D to "Herzfrequenz", 0x1812 to "HID (Tastatur/Maus)",
        0x1810 to "Blutdruck", 0x1816 to "Rad-Sensor", 0x1826 to "Fitnessgerät",
        0x181C to "Benutzerdaten", 0x1802 to "Sofortalarm", 0x1803 to "Link Loss",
        0x1804 to "Sendeleistung", 0x184E to "LE Audio", 0x1850 to "LE Audio (PACS)",
        0xFD6F to "Exposure Notification (Corona-Warn)", 0xFE2C to "Google Fast Pair",
        0xFEAA to "Eddystone / Google Find Hub", 0xFD5A to "Samsung SmartTag",
        0xFEED to "Tile", 0xFEEC to "Tile", 0xFE33 to "Chipolo", 0xFE9F to "Google",
        0xFE0F to "Philips Hue (Signify)", 0xFE03 to "Amazon", 0x3082 to "Flipper Zero",
    )

    val companies: Map<Int, String> = mapOf(
        0x0000 to "Ericsson", 0x0001 to "Nokia", 0x0002 to "Intel", 0x0003 to "IBM",
        0x0006 to "Microsoft", 0x000A to "Qualcomm (CSR)", 0x000D to "Texas Instruments",
        0x000F to "Broadcom", 0x001D to "Qualcomm", 0x0030 to "STMicroelectronics",
        0x0046 to "MediaTek", 0x004C to "Apple", 0x0059 to "Nordic Semiconductor",
        0x005D to "Realtek", 0x0067 to "GN (Jabra)", 0x0075 to "Samsung", 0x0087 to "Garmin",
        0x009E to "Bose", 0x00C4 to "LG Electronics", 0x00E0 to "Google", 0x012D to "Sony",
        0x0131 to "Cypress", 0x0157 to "Huami (Amazfit/Mi Band)", 0x0171 to "Amazon",
        0x01AB to "Meta (Facebook)", 0x027D to "Huawei", 0x02E5 to "Espressif",
        0x038F to "Xiaomi", 0x0499 to "Ruuvi", 0x058E to "Meta Platforms Technologies",
    )

    private val airpodsModels = mapOf(
        0x0220 to "AirPods", 0x0F20 to "AirPods (2. Gen)", 0x1320 to "AirPods (3. Gen)",
        0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2", 0x0A20 to "AirPods Max",
        0x0320 to "Powerbeats3", 0x0B20 to "Powerbeats Pro", 0x0520 to "BeatsX",
        0x0620 to "Beats Solo3", 0x0920 to "Beats Studio3", 0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds",
    )

    fun parse(
        name: String?,
        address: String,
        manufacturer: Map<Int, ByteArray>,
        serviceUuids: Collection<UUID>,
        serviceData: Map<UUID, ByteArray>,
    ): Info {
        var kind = DeviceKind.UNKNOWN
        var label: String? = null
        var tracker: TrackerType? = null
        var separated = false
        var popup = false
        var hack = false
        var camWear = false
        val notes = mutableListOf<String>()

        val allUuids = (serviceUuids + serviceData.keys).toSet()
        val shorts = allUuids.mapNotNull { short(it) }.toSet()
        val services = allUuids.map { u ->
            short(u)?.let { s -> serviceNames[s]?.let { "$it (0x%04X)".format(s) } ?: "0x%04X".format(s) }
                ?: if (u == DULT_UUID) "DULT-Tracker-Warnung" else u.toString()
        }

        fun setKind(k: DeviceKind, l: String, force: Boolean = false) {
            if (force || kind == DeviceKind.UNKNOWN) {
                kind = k
                label = l
            }
        }

        fun setTracker(t: TrackerType, l: String) {
            tracker = t
            kind = DeviceKind.TRACKER
            label = l
        }

        // ---- trackers announced via service UUIDs
        if (0xFD5A in shorts) setTracker(TrackerType.SAMSUNG, "Samsung SmartTag")
        if (0xFEED in shorts || 0xFEEC in shorts) setTracker(TrackerType.TILE, "Tile-Tracker")
        if (0xFE33 in shorts) setTracker(TrackerType.CHIPOLO, "Chipolo-Tracker")
        serviceData[uuid16(0xFEAA)]?.takeIf { it.isNotEmpty() }?.let { d ->
            when (d[0].u) {
                0x40, 0x41 -> {
                    setTracker(TrackerType.GOOGLE_FMDN, "Find-Hub-Tracker (Google)")
                    if (d[0].u == 0x41) {
                        separated = true
                        notes += "Schutzmodus gegen unerwünschtes Tracking aktiv (vom Besitzer getrennt)"
                    }
                }
                0x00 -> setKind(DeviceKind.BEACON, "Eddystone-UID-Beacon")
                0x10 -> setKind(DeviceKind.BEACON, "Eddystone-URL-Beacon")
                0x20 -> setKind(DeviceKind.BEACON, "Eddystone-TLM-Beacon")
                else -> setKind(DeviceKind.BEACON, "Eddystone-Beacon")
            }
        }
        if (DULT_UUID in allUuids) {
            if (tracker == null) setTracker(TrackerType.DULT, "Tracker (DULT)")
            separated = true
            notes += "Sendet das standardisierte Warnsignal für getrennte Tracker"
        }
        if (0xFE2C in shorts) {
            popup = true
            setKind(DeviceKind.AUDIO, "Google-Fast-Pair-Gerät")
        }
        if (0xFD6F in shorts) setKind(DeviceKind.PHONE, "Smartphone (Exposure Notification)")
        if (0x3082 in shorts) hack = true
        if (0x1812 in shorts) setKind(DeviceKind.PERIPHERAL, "Eingabegerät (HID)")
        if (0x180D in shorts) setKind(DeviceKind.WEARABLE, "Herzfrequenz-Sensor")
        if (0xFE0F in shorts) setKind(DeviceKind.IOT, "Philips Hue")

        // ---- manufacturer specific data
        for ((id, d) in manufacturer) {
            when (id) {
                0x004C -> {
                    var i = 0
                    while (i + 1 < d.size) {
                        val t = d[i].u
                        val len = d[i + 1].u
                        val p = d.copyOfRange(i + 2, minOf(d.size, i + 2 + len))
                        when (t) {
                            0x02 -> if (p.size >= 20) {
                                val uuid = p.copyOfRange(0, 16).hex()
                                val major = (p[16].u shl 8) or p[17].u
                                val minor = (p[18].u shl 8) or p[19].u
                                setKind(DeviceKind.BEACON, "iBeacon", force = kind != DeviceKind.TRACKER)
                                notes += "iBeacon UUID $uuid · Major $major · Minor $minor"
                            }
                            0x05 -> setKind(DeviceKind.PHONE, "Apple-Gerät (AirDrop aktiv)")
                            0x07 -> {
                                popup = true
                                val model = if (p.size >= 3) (p[1].u shl 8) or p[2].u else -1
                                setKind(DeviceKind.AUDIO, airpodsModels[model] ?: "AirPods/Beats", force = kind == DeviceKind.UNKNOWN || kind == DeviceKind.PHONE)
                            }
                            0x09 -> setKind(DeviceKind.TV, "AirPlay-Empfänger (Apple TV/Lautsprecher)")
                            0x0A -> setKind(DeviceKind.PHONE, "Apple-Gerät (AirPlay-Quelle)")
                            0x0B -> setKind(DeviceKind.WEARABLE, "Apple Watch")
                            0x0C -> setKind(DeviceKind.PHONE, "Apple-Gerät (Handoff)")
                            0x0D, 0x0E -> setKind(DeviceKind.PHONE, "Apple-Gerät (Hotspot)")
                            0x0F -> {
                                popup = true
                                setKind(DeviceKind.PHONE, "Apple-Gerät (Nearby Action)")
                            }
                            0x10 -> setKind(DeviceKind.PHONE, "Apple-Gerät (iPhone/iPad/Mac)")
                            0x12 -> {
                                if (len >= 0x19) {
                                    setTracker(TrackerType.APPLE_FINDMY, "Find-My-Gerät, vom Besitzer getrennt (z. B. AirTag)")
                                    separated = true
                                    notes += "Find-My-Offline-Modus: Besitzer ist nicht in der Nähe"
                                } else if (tracker == null) {
                                    setTracker(TrackerType.APPLE_FINDMY, "Find-My-Gerät (Besitzer in der Nähe)")
                                }
                            }
                        }
                        i += 2 + len
                    }
                    if (kind == DeviceKind.UNKNOWN) label = "Apple-Gerät"
                }
                0x0006 -> {
                    if (d.size >= 3 && d[0].u == 0x03 && d[1].u == 0x00 && d[2].u == 0x80) {
                        popup = true
                        setKind(DeviceKind.PERIPHERAL, "Microsoft Swift Pair")
                    } else if (d.isNotEmpty() && d[0].u == 0x01) {
                        setKind(DeviceKind.COMPUTER, "Windows-Gerät")
                    }
                }
                0x0075 -> {
                    if (d.size >= 3 && d[0].u == 0x42 && d[1].u == 0x09 && d[2].u == 0x81) {
                        popup = true
                        setKind(DeviceKind.AUDIO, "Galaxy Buds (Pairing)")
                    } else if (d.size >= 7 && d[0].u == 0x01 && d[1].u == 0x00 && d[2].u == 0x02 &&
                        d[3].u == 0x00 && d[4].u == 0x01 && d[5].u == 0x01 && d[6].u == 0xFF
                    ) {
                        popup = true
                        setKind(DeviceKind.WEARABLE, "Galaxy Watch (Pairing)")
                    } else {
                        setKind(DeviceKind.PHONE, "Samsung-Gerät")
                    }
                }
                0x01AB, 0x058E -> {
                    camWear = true
                    setKind(DeviceKind.WEARABLE, "Meta-Gerät (Smart-Brille/Headset)")
                    notes += "Meta-Geräte umfassen Kamerabrillen (Ray-Ban/Oakley Meta)"
                }
                0x0157 -> setKind(DeviceKind.WEARABLE, "Fitness-Tracker (Amazfit/Mi Band)")
                0x0087 -> setKind(DeviceKind.WEARABLE, "Garmin-Gerät")
                0x009E, 0x0067 -> setKind(DeviceKind.AUDIO, "Kopfhörer/Headset")
                0x0499 -> setKind(DeviceKind.IOT, "Ruuvi-Sensor")
            }
        }

        // ---- name heuristics
        val n = name?.lowercase().orEmpty()
        if (n.isNotEmpty()) {
            when {
                "flipper" in n || "marauder" in n || "pwnagotchi" in n || "blespam" in n -> hack = true
                Regex("airtag|smarttag|tile|chipolo|tracker|itag").containsMatchIn(n) && tracker == null ->
                    setKind(DeviceKind.TRACKER, "Möglicher Tracker (Name)")
                Regex("cam|camera|gopro|insta360|spy").containsMatchIn(n) ->
                    setKind(DeviceKind.CAMERA, "Kamera", force = kind == DeviceKind.UNKNOWN)
                Regex("ray-ban|rayban|oakley meta|spectacles").containsMatchIn(n) -> {
                    camWear = true
                    setKind(DeviceKind.WEARABLE, "Smart-Brille mit Kamera", force = true)
                }
                Regex("buds|airpods|headphone|headset|jbl|bose|wh-|wf-|speaker|soundcore|sony|beats").containsMatchIn(n) ->
                    setKind(DeviceKind.AUDIO, "Audiogerät")
                Regex("watch|band|fit|garmin|amazfit|polar|whoop").containsMatchIn(n) ->
                    setKind(DeviceKind.WEARABLE, "Wearable")
                Regex("tv|bravia|roku|chromecast|fire ?tv|\\[lg\\]|samsung q|webos").containsMatchIn(n) ->
                    setKind(DeviceKind.TV, "TV/Streaming")
                Regex("tesla|bmw|vw |volkswagen|audi|mercedes|my car|skoda|seat|ford|opel|toyota|hyundai|kia|volvo|renault|peugeot").containsMatchIn(n) ->
                    setKind(DeviceKind.VEHICLE, "Fahrzeug")
                Regex("macbook|laptop|desktop-|laptop-|thinkpad|surface|imac|\\bpc\\b").containsMatchIn(n) ->
                    setKind(DeviceKind.COMPUTER, "Computer")
                Regex("iphone|ipad|galaxy|pixel|phone|redmi|xiaomi|huawei|oneplus|oppo|motorola").containsMatchIn(n) ->
                    setKind(DeviceKind.PHONE, "Smartphone")
                Regex("keyboard|mouse|tastatur|maus|mx |controller|gamepad").containsMatchIn(n) ->
                    setKind(DeviceKind.PERIPHERAL, "Peripherie")
                Regex("hue|bulb|lamp|plug|switch|sensor|thermo|tado|eve |nuki|lock").containsMatchIn(n) ->
                    setKind(DeviceKind.IOT, "Smart-Home-Gerät")
            }
        }

        val ouiVendor = Oui.vendor(address)
        if (ouiVendor?.startsWith("Flipper") == true) hack = true
        if (hack) {
            kind = DeviceKind.HACKTOOL
            label = "Flipper Zero / Hacking-Tool"
            notes += "Kann Funk-Angriffe ausführen (BLE-Spam, Sub-GHz, NFC/RFID-Kopien)"
        }

        val vendor = manufacturer.keys.firstNotNullOfOrNull { companies[it] } ?: ouiVendor
        return Info(
            kind = kind,
            label = label ?: name ?: vendor?.let { "$it-Gerät" } ?: kind.label,
            vendor = vendor,
            tracker = tracker,
            separated = separated,
            popup = popup,
            hackTool = hack,
            cameraWearable = camWear,
            notes = notes,
            services = services,
        )
    }
}
