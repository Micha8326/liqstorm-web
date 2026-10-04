package de.liqstorm.funkwache.analysis

import de.liqstorm.funkwache.model.Severity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detects radio devices that keep showing up around you while you move – the core signal for a
 * hidden tracker (AirTag, SmartTag, Tile ...) or a device carried by someone following you.
 *
 * Pure logic, no Android dependencies.
 */
class FollowDetector {

    data class Pt(val t: Long, val lat: Double?, val lon: Double?)

    class Track(val id: String, var label: String, var tracker: Boolean) {
        val pts = ArrayList<Pt>()
        var alerted: Severity? = null
    }

    data class Finding(val id: String, val label: String, val severity: Severity, val title: String, val detail: String)

    data class Progress(val spanMs: Long, val sightings: Int, val maxDistM: Double)

    private val tracks = HashMap<String, Track>()

    /** Minimum time between two recorded sightings of the same device. */
    var minGapTrackerMs = 30_000L
    var minGapOtherMs = 60_000L

    @Synchronized
    fun sighting(id: String, label: String, tracker: Boolean, now: Long, lat: Double?, lon: Double?) {
        val tr = tracks.getOrPut(id) { Track(id, label, tracker) }
        tr.label = label
        tr.tracker = tr.tracker || tracker
        val last = tr.pts.lastOrNull()
        val gap = if (tr.tracker) minGapTrackerMs else minGapOtherMs
        if (last != null && now - last.t < gap) return
        tr.pts += Pt(now, lat, lon)
        if (tr.pts.size > 400) tr.pts.removeAt(0)
    }

    @Synchronized
    fun progress(id: String): Progress? {
        val tr = tracks[id] ?: return null
        if (tr.pts.isEmpty()) return null
        return Progress(tr.pts.last().t - tr.pts.first().t, tr.pts.size, maxDistance(tr.pts))
    }

    @Synchronized
    fun evaluate(now: Long): List<Finding> {
        val out = ArrayList<Finding>()
        val it = tracks.values.iterator()
        while (it.hasNext()) {
            val tr = it.next()
            val last = tr.pts.last()
            // prune: gone for 3 h, or a one-off sighting older than 15 min
            if (now - last.t > 3 * 3600_000L || (tr.pts.size == 1 && now - last.t > 15 * 60_000L)) {
                it.remove()
                continue
            }
            val span = last.t - tr.pts.first().t
            val located = tr.pts.count { p -> p.lat != null }
            val dist = maxDistance(tr.pts)
            // In bus/train/car-sharing other people's devices travel along, so movement alone is not enough:
            // the strong signal is a device that is still with you after you have arrived somewhere.
            val arrived = arrivedTogether(tr.pts)
            val f: Finding? = if (tr.tracker) {
                when {
                    arrived && tr.pts.size >= 4 -> Finding(
                        tr.id, tr.label, Severity.HIGH, "Tracker folgt dir",
                        "${tr.label} (${tr.id}) hat sich ${dist.toInt()} m mit dir bewegt und ist auch nach der Ankunft " +
                            "noch bei dir (seit ${span / 60000} min). Suche ihn mit Bluetooth › Gerät › Orten – " +
                            "Taschen, Jacke, Rucksack, Auto (Radkasten, Stoßstange, unter Sitzen)."
                    )
                    span >= 10 * 60_000L && tr.pts.size >= 3 && dist >= 300 -> Finding(
                        tr.id, tr.label, Severity.LOW, "Tracker reist mit dir",
                        "${tr.label} (${tr.id}) ist seit ${span / 60000} min über ${dist.toInt()} m mit dir unterwegs. " +
                            "In Bus und Bahn normal (Mitreisende). Ist er nach der Ankunft immer noch da, kommt eine Warnung."
                    )
                    located < 2 && span >= 30 * 60_000L && buckets(tr.pts) >= 6 -> Finding(
                        tr.id, tr.label, Severity.MEDIUM, "Tracker dauerhaft in deiner Nähe",
                        "${tr.label} (${tr.id}) ist seit ${span / 60000} min ununterbrochen in Funkreichweite. " +
                            "Ohne Standortdaten lässt sich nicht sagen, ob er dir folgt."
                    )
                    else -> null
                }
            } else {
                if (arrived && span >= 20 * 60_000L && tr.pts.size >= 5 && dist >= 1000) Finding(
                    tr.id, tr.label, Severity.LOW, "Gerät begleitet dich",
                    "${tr.label} (${tr.id}) war über ${dist.toInt()} m mit dir unterwegs und ist auch am Ziel noch da. " +
                        "Wenn es dein eigenes Gerät ist, markiere es als vertraut."
                ) else null
            }
            if (f != null && (tr.alerted == null || f.severity.ordinal > tr.alerted!!.ordinal)) {
                tr.alerted = f.severity
                out += f
            }
        }
        return out
    }

    @Synchronized
    fun clear() = tracks.clear()

    /**
     * True when the device was seen at a place at least 300 m away from where it was first seen, and has
     * stayed with you there (sightings within 150 m) for at least 8 minutes.
     */
    private fun arrivedTogether(pts: List<Pt>): Boolean {
        val loc = pts.filter { it.lat != null && it.lon != null }
        if (loc.size < 4) return false
        val end = loc.last()
        var i = loc.size - 1
        while (i > 0 && haversine(end.lat!!, end.lon!!, loc[i - 1].lat!!, loc[i - 1].lon!!) <= 150) i--
        val stay = loc.subList(i, loc.size)
        val first = loc.first()
        return stay.size >= 3 && end.t - stay.first().t >= 8 * 60_000L &&
            haversine(first.lat!!, first.lon!!, end.lat!!, end.lon!!) >= 300
    }

    private fun buckets(pts: List<Pt>) = pts.map { it.t / (5 * 60_000L) }.toSet().size

    companion object {
        fun maxDistance(pts: List<Pt>): Double {
            val loc = pts.filter { it.lat != null && it.lon != null }
            if (loc.size < 2) return 0.0
            // Distance from first and last position covers out-and-back movement well enough.
            val a = loc.first()
            val b = loc.last()
            return loc.maxOf { maxOf(haversine(a.lat!!, a.lon!!, it.lat!!, it.lon!!), haversine(b.lat!!, b.lon!!, it.lat!!, it.lon!!)) }
        }

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}

/**
 * BLE spam (Flipper Zero "BLE Spam", ESP32 apps) floods the air with pairing pop-up frames
 * from constantly changing random addresses. Legit environments rarely show more than a
 * handful of distinct pop-up advertisers in a few seconds.
 */
class SpamDetector(private val windowMs: Long = 10_000, private val threshold: Int = 40) {
    private val events = ArrayDeque<Pair<Long, String>>()
    private val firstSeen = HashMap<String, Long>()
    private var startedAt = -1L

    /**
     * Returns the number of throw-away pop-up advertisers in the window if it crosses the threshold.
     * Spam tools use a new random address for (almost) every packet, while real AirPods/phones in a
     * crowded train keep their address and repeat it many times. Right after scanning starts every
     * device is "new", so the first 30 s are only used to learn the surroundings.
     */
    @Synchronized
    fun record(address: String, now: Long): Int? {
        if (startedAt < 0) startedAt = now
        events.addLast(now to address)
        firstSeen.putIfAbsent(address, now)
        while (events.isNotEmpty() && events.first().first < now - windowMs) events.removeFirst()
        if (firstSeen.size > 5000) firstSeen.values.removeAll { it < now - 5 * 60_000L }
        if (now - startedAt < 30_000) return null
        val counts = HashMap<String, Int>()
        for ((_, a) in events) counts[a] = (counts[a] ?: 0) + 1
        val oneShot = counts.count { (a, c) -> c <= 2 && (firstSeen[a] ?: 0) >= now - windowMs }
        return if (oneShot >= threshold) oneShot else null
    }
}
