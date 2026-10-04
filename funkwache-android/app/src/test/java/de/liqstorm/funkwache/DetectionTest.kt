package de.liqstorm.funkwache

import de.liqstorm.funkwache.analysis.CellAnalyzer
import de.liqstorm.funkwache.analysis.FollowDetector
import de.liqstorm.funkwache.analysis.SpamDetector
import de.liqstorm.funkwache.analysis.WifiAnalyzer
import de.liqstorm.funkwache.model.CellObs
import de.liqstorm.funkwache.model.CellStatus
import de.liqstorm.funkwache.model.DeviceKind
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.TrackerType
import de.liqstorm.funkwache.model.WifiConnection
import de.liqstorm.funkwache.model.WifiNet
import de.liqstorm.funkwache.scan.BleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun airTagSeparated() {
        val payload = bytes(0x12, 0x19, *IntArray(25) { 0x10 })
        val i = BleParser.parse(null, "C1:22:33:44:55:66", mapOf(0x004C to payload), emptyList(), emptyMap())
        assertEquals(TrackerType.APPLE_FINDMY, i.tracker)
        assertTrue(i.separated)
        assertTrue(i.followRelevantTracker)
        assertEquals(DeviceKind.TRACKER, i.kind)
    }

    @Test
    fun findMyNearOwnerIsNotFollowRelevant() {
        val i = BleParser.parse(null, "C1:22:33:44:55:66", mapOf(0x004C to bytes(0x12, 0x02, 0x00, 0x01)), emptyList(), emptyMap())
        assertEquals(TrackerType.APPLE_FINDMY, i.tracker)
        assertFalse(i.separated)
        assertFalse(i.followRelevantTracker)
    }

    @Test
    fun samsungTileChipolo() {
        val s = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", emptyMap(), emptyList(), mapOf(BleParser.uuid16(0xFD5A) to bytes(1, 2)))
        assertEquals(TrackerType.SAMSUNG, s.tracker)
        val t = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", emptyMap(), listOf(BleParser.uuid16(0xFEED)), emptyMap())
        assertEquals(TrackerType.TILE, t.tracker)
        val c = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", emptyMap(), listOf(BleParser.uuid16(0xFE33)), emptyMap())
        assertEquals(TrackerType.CHIPOLO, c.tracker)
    }

    @Test
    fun googleFindHubUnwantedTrackingMode() {
        val i = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", emptyMap(), emptyList(), mapOf(BleParser.uuid16(0xFEAA) to bytes(0x41, 1, 2, 3)))
        assertEquals(TrackerType.GOOGLE_FMDN, i.tracker)
        assertTrue(i.separated)
        val eddystone = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", emptyMap(), emptyList(), mapOf(BleParser.uuid16(0xFEAA) to bytes(0x10, 0)))
        assertNull(eddystone.tracker)
        assertEquals(DeviceKind.BEACON, eddystone.kind)
    }

    @Test
    fun airPodsArePopupFrames() {
        val i = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", mapOf(0x004C to bytes(0x07, 0x19, 0x01, 0x0E, 0x20, *IntArray(22))), emptyList(), emptyMap())
        assertTrue(i.popup)
        assertEquals("AirPods Pro", i.label)
        assertNull(i.tracker)
    }

    @Test
    fun flipperByOuiAndName() {
        assertTrue(BleParser.parse(null, "0C:FA:22:01:02:03", emptyMap(), emptyList(), emptyMap()).hackTool)
        val n = BleParser.parse("Flipper Kaputt", "D0:00:00:00:00:01", emptyMap(), emptyList(), emptyMap())
        assertTrue(n.hackTool)
        assertEquals(DeviceKind.HACKTOOL, n.kind)
    }

    @Test
    fun iBeacon() {
        val p = bytes(0x02, 0x15, *IntArray(16) { it }, 0x00, 0x01, 0x00, 0x02, 0xC5)
        val i = BleParser.parse(null, "AA:BB:CC:DD:EE:FF", mapOf(0x004C to p), emptyList(), emptyMap())
        assertEquals(DeviceKind.BEACON, i.kind)
        assertTrue(i.notes.any { "Major 1" in it && "Minor 2" in it })
    }

    @Test
    fun truncatedAppleDataDoesNotCrash() {
        BleParser.parse(null, "AA", mapOf(0x004C to bytes(0x12)), emptyList(), emptyMap())
        BleParser.parse(null, "AA", mapOf(0x004C to bytes(0x02, 0x15, 1, 2)), emptyList(), emptyMap())
        BleParser.parse(null, "", mapOf(0x0006 to bytes()), emptyList(), mapOf(BleParser.uuid16(0xFEAA) to bytes()))
    }
}

class FollowDetectorTest {
    private val min = 60_000L

    @Test
    fun trackerOnlyTravellingAlongIsLow() {
        // e.g. another passenger's AirTag on the same train
        val d = FollowDetector()
        var lat = 52.5200
        for (i in 0..12) {
            d.sighting("T1", "AirTag", true, i * min, lat, 13.405)
            lat += 0.0005 // ~55 m per minute
        }
        assertEquals(Severity.LOW, d.evaluate(12 * min).single().severity)
        assertTrue(d.evaluate(13 * min).isEmpty())
    }

    @Test
    fun trackerStillThereAfterArrivalIsHigh() {
        val d = FollowDetector()
        var lat = 52.5200
        for (i in 0..12) {
            d.sighting("T1", "AirTag", true, i * min, lat, 13.405)
            lat += 0.0005
        }
        d.evaluate(12 * min)
        for (i in 13..22) d.sighting("T1", "AirTag", true, i * min, lat, 13.405) // stopped, tracker stays
        assertEquals(Severity.HIGH, d.evaluate(22 * min).single().severity)
    }

    @Test
    fun passengerLeavesAtStationNoHigh() {
        val d = FollowDetector()
        var lat = 52.5200
        for (i in 0..12) {
            d.sighting("T1", "AirTag", true, i * min, lat, 13.405)
            lat += 0.0005
        }
        // user arrives and stays, the tracker is no longer seen
        assertTrue(d.evaluate(25 * min).none { it.severity == Severity.HIGH })
    }

    @Test
    fun stationaryTrackerWithLocationIsQuiet() {
        val d = FollowDetector()
        for (i in 0..40) d.sighting("T1", "AirTag", true, i * min, 52.52, 13.405)
        assertTrue(d.evaluate(40 * min).isEmpty())
    }

    @Test
    fun trackerWithoutLocationForLongTimeIsMedium() {
        val d = FollowDetector()
        for (i in 0..35) d.sighting("T1", "Tile", true, i * min, null, null)
        val f = d.evaluate(35 * min)
        assertEquals(Severity.MEDIUM, f.single().severity)
    }

    @Test
    fun ordinaryDeviceNeedsMoreDistance() {
        val d = FollowDetector()
        var lat = 52.52
        for (i in 0..25) {
            d.sighting("P1", "Kopfhörer", false, i * min, lat, 13.405)
            lat += 0.0006
        }
        assertTrue("only travelling along", d.evaluate(25 * min).isEmpty())
        for (i in 26..35) d.sighting("P1", "Kopfhörer", false, i * min, lat, 13.405)
        assertEquals(Severity.LOW, d.evaluate(35 * min).single().severity)
    }

    @Test
    fun haversineRoughlyRight() {
        val m = FollowDetector.haversine(52.52, 13.405, 52.53, 13.405)
        assertTrue(m in 1100.0..1120.0)
    }
}

class SpamDetectorTest {
    @Test
    fun manyRandomAddressesTrigger() {
        val s = SpamDetector()
        var hit: Int? = null
        for (i in 0 until 40) s.record("normal${i % 3}", i * 1000L) // 40 s of normal surroundings
        for (i in 0 until 80) hit = s.record("addr$i", 40_000L + i * 100) ?: hit
        assertTrue(hit != null)
    }

    @Test
    fun crowdedTrainDoesNot() {
        // 60 passengers' AirPods/iPhones, each repeating its address every ~300 ms
        val s = SpamDetector()
        var t = 0L
        repeat(20) { for (i in 0 until 60) assertNull(s.record("dev$i", t++ * 50)) }
        // ten new passengers board at once
        repeat(5) { for (i in 0 until 70) assertNull(s.record("dev$i", t++ * 50)) }
    }

    @Test
    fun fewAirPodsDoNot() {
        val s = SpamDetector()
        for (i in 0 until 200) assertNull(s.record("addr${i % 4}", 1000L + i * 100))
    }
}

class WifiAnalyzerTest {
    private fun net(bssid: String, ssid: String, cap: String): WifiNet {
        val (sec, rank) = WifiAnalyzer.security(cap)
        return WifiNet(bssid, ssid, 2437, 6, "2,4 GHz", -60, sec, rank, cap, null, false, null, null, false, 0, 0, emptyList())
    }

    @Test
    fun securityParsing() {
        assertEquals(0, WifiAnalyzer.security("[ESS]").second)
        assertEquals(1, WifiAnalyzer.security("[WEP][ESS]").second)
        assertEquals(3, WifiAnalyzer.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]").second)
        assertEquals(4, WifiAnalyzer.security("[RSN-SAE-CCMP][ESS]").second)
        assertEquals(3, WifiAnalyzer.security("[RSN-PSK+SAE-CCMP][ESS]").second)
        assertEquals(2, WifiAnalyzer.security("[RSN-OWE-CCMP][ESS]").second)
    }

    @Test
    fun channels() {
        assertEquals(1, WifiAnalyzer.channel(2412))
        assertEquals(13, WifiAnalyzer.channel(2472))
        assertEquals(36, WifiAnalyzer.channel(5180))
        assertEquals(1, WifiAnalyzer.channel(5955))
    }

    @Test
    fun evilTwinOfKnownNetwork() {
        val a = WifiAnalyzer.analyze(listOf(net("11:22:33:44:55:66", "Zuhause", "[ESS]")), null, mapOf("Zuhause" to 3), 0)
        assertTrue(a.any { it.severity == Severity.HIGH && it.key.startsWith("eviltwin") })
    }

    @Test
    fun karmaPattern() {
        val nets = listOf("Telekom", "Starbucks", "Airport Free", "Hotel").mapIndexed { i, s ->
            net("00:11:22:33:44:0$i", s, "[ESS]")
        }
        assertTrue(WifiAnalyzer.analyze(nets, null, emptyMap(), 0).any { it.key.startsWith("karma") })
    }

    @Test
    fun pineappleOui() {
        val a = WifiAnalyzer.analyze(listOf(net("00:13:37:AA:BB:CC", "Free", "[ESS]")), null, emptyMap(), 0)
        assertTrue(a.any { it.key.startsWith("pentest") && it.severity == Severity.HIGH })
    }

    @Test
    fun cameraNames() {
        val a = WifiAnalyzer.analyze(listOf(net("12:22:33:44:55:66", "IPC_3f2a", "[WPA2-PSK-CCMP]")), null, emptyMap(), 0)
        assertTrue(a.any { it.key.startsWith("camera") })
        val b = WifiAnalyzer.analyze(listOf(net("12:22:33:44:55:66", "Campingplatz", "[WPA2-PSK-CCMP]")), null, emptyMap(), 0)
        assertTrue(b.none { it.key.startsWith("camera") })
        assertFalse(WifiAnalyzer.isCameraName("SONOS-AxQBBgABKZSfPgS6CAM="))
        assertFalse(WifiAnalyzer.isCameraName("xyz6CAM="))
        assertTrue(WifiAnalyzer.isCameraName("CAM-01"))
    }

    @Test
    fun proxyOnConnection() {
        val c = WifiConnection("Büro", "AA:AA:AA:AA:AA:AA", -50, 300, 5180, "10.0.0.2", "WPA2", 3, false, "10.0.0.66:8080", null, emptyList())
        assertTrue(WifiAnalyzer.analyze(emptyList(), c, emptyMap(), 0).any { it.key.startsWith("proxy") })
    }
}

class CellAnalyzerTest {
    private fun cell(tech: String, area: Int, cid: Long, dbm: Int = -90, reg: Boolean = true) =
        CellObs(tech, reg, "262", "01", area, cid, 1, 100, dbm, 3)

    private fun status(t: Long, vararg c: CellObs) = CellStatus(t, "Telekom", "26201", "26201", false, null, c.toList())

    @Test
    fun downgradeTo2gIsHigh() {
        val a = CellAnalyzer()
        a.analyze(status(0, cell("LTE", 100, 1)), null, null)
        val out = a.analyze(status(60_000, cell("GSM", 200, 2)), null, null)
        assertTrue(out.any { it.severity == Severity.HIGH && it.key.startsWith("downgrade") })
    }

    @Test
    fun areaChangeOfKnownCell() {
        val a = CellAnalyzer()
        a.analyze(status(0, cell("LTE", 100, 7)), null, null)
        val out = a.analyze(status(10_000, cell("LTE", 999, 7)), null, null)
        assertTrue(out.any { it.key.startsWith("areachange") })
    }

    @Test
    fun normalLteIsQuiet() {
        val a = CellAnalyzer()
        for (i in 0..10) assertTrue(a.analyze(status(i * 10_000L, cell("LTE", 100, 7)), 52.5, 13.4).isEmpty())
    }
}
