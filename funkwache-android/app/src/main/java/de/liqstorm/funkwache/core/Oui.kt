package de.liqstorm.funkwache.core

/**
 * Small offline vendor table (IEEE OUI prefixes). Deliberately limited to vendors that
 * matter for security assessment plus a few very common router makers.
 */
object Oui {
    private val table: Map<String, String> = buildMap {
        // Security / pentest hardware
        put("0C:FA:22", "Flipper Devices (Flipper Zero)")
        put("00:13:37", "Hak5 (WiFi Pineapple)")
        put("00:C0:CA", "ALFA Network (Pentest-WLAN-Adapter)")
        // DIY boards – often used in cheap cameras, deauthers and ESP32 Marauder
        listOf(
            "24:0A:C4", "30:AE:A4", "24:6F:28", "A4:CF:12", "84:CC:A8", "3C:71:BF",
            "EC:FA:BC", "7C:9E:BD", "AC:67:B2", "C8:2B:96", "08:3A:F2", "48:3F:DA", "E8:DB:84",
            "5C:CF:7F", "60:01:94", "18:FE:34", "BC:DD:C2", "CC:50:E3", "94:B9:7E", "A0:20:A6",
        ).forEach { put(it, "Espressif (ESP32/ESP8266)") }
        listOf("B8:27:EB", "DC:A6:32", "E4:5F:01", "D8:3A:DD", "2C:CF:67", "28:CD:C1")
            .forEach { put(it, "Raspberry Pi") }
        // Routers / access points
        listOf("3C:A6:2F", "C8:0E:14", "2C:91:AB", "44:4E:6D", "38:10:D5", "DC:39:6F", "E0:28:6D", "98:9B:CB")
            .forEach { put(it, "AVM (FRITZ!)") }
        listOf("24:A4:3C", "04:18:D6", "78:8A:20", "F0:9F:C2", "80:2A:A8", "FC:EC:DA", "74:83:C2", "E0:63:DA")
            .forEach { put(it, "Ubiquiti") }
        listOf("50:C7:BF", "14:CC:20", "F4:F2:6D", "98:DA:C4", "C0:4A:00", "60:32:B1")
            .forEach { put(it, "TP-Link") }
    }

    /** True for locally administered addresses (randomised MACs, hotspots, virtual APs). */
    fun isLocal(mac: String): Boolean {
        val first = mac.take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    fun vendor(mac: String): String? {
        if (mac.length < 8 || isLocal(mac)) return null
        return table[mac.substring(0, 8).uppercase()]
    }

    fun isPentestHardware(mac: String): Boolean {
        val v = vendor(mac) ?: return false
        return v.startsWith("Flipper") || v.startsWith("Hak5") || v.startsWith("ALFA")
    }
}
