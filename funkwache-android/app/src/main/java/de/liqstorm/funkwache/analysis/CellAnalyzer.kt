package de.liqstorm.funkwache.analysis

import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.CellStatus
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source

/**
 * IMSI-catcher / fake base station heuristics. None of these alone is proof – real networks
 * also fall back to 2G or reshuffle areas – so severities stay moderate unless indicators stack.
 */
class CellAnalyzer {
    private data class Serving(val t: Long, val tech: String, val area: Int?, val lat: Double?, val lon: Double?)

    private val history = ArrayDeque<Serving>()
    /** cell key -> area code first seen with that cell. */
    private val areas = HashMap<String, Int>()

    fun analyze(s: CellStatus, lat: Double?, lon: Double?): List<Alert> {
        val out = ArrayList<Alert>()
        val now = s.time
        val serving = s.cells.firstOrNull { it.registered } ?: return out

        history.addLast(Serving(now, serving.tech, serving.area, lat, lon))
        while (history.isNotEmpty() && history.first().t < now - 30 * 60_000L) history.removeFirst()

        // 1) Downgrade to 2G shortly after having 4G/5G.
        if (serving.tech == "GSM") {
            val hadModern = history.any { it.tech in setOf("LTE", "NR") && now - it.t < 10 * 60_000L }
            if (hadModern) {
                out += Alert(
                    "downgrade-${serving.key}", now, Severity.HIGH, Source.CELL,
                    "Plötzlicher Wechsel auf 2G",
                    "Dein Telefon war eben noch im 4G/5G-Netz und hängt jetzt in einer GSM-Zelle " +
                        "(LAC ${serving.area}, CID ${serving.cid}, ${serving.dbm} dBm). Erzwungenes 2G ist das klassische " +
                        "Muster von IMSI-Catchern, weil 2G sich ohne Netzauthentifizierung fälschen lässt. " +
                        "Tipp: In den Netzeinstellungen 2G deaktivieren (Android 12+)."
                )
            } else {
                out += Alert(
                    "on2g-${s.networkOperator}", now, Severity.LOW, Source.CELL,
                    "Verbindung über 2G (GSM)",
                    "2G bietet schwache Verschlüsselung und keine Netzauthentifizierung. " +
                        "In Deutschland ist 2G kaum noch nötig – wenn möglich deaktivieren."
                )
            }
        }

        // 2) Known cell id suddenly announced with a different area code.
        for (c in s.cells) {
            if (!c.hasId || c.area == null) continue
            val prev = areas[c.key]
            if (prev == null) {
                areas[c.key] = c.area
            } else if (prev != c.area) {
                out += Alert(
                    "areachange-${c.key}-${c.area}", now, Severity.MEDIUM, Source.CELL,
                    "Zelle mit geänderter Gebietskennung",
                    "Zelle ${c.tech} CID ${c.cid} meldete erst Gebiet $prev, jetzt ${c.area}. " +
                        "Fake-Basisstationen kopieren echte Zell-IDs mit abweichender LAC/TAC, um Telefone zur Neuanmeldung zu zwingen."
                )
                areas[c.key] = c.area
            }
        }
        if (areas.size > 5000) areas.clear()

        // 3) Many area changes while standing still.
        val recent = history.filter { now - it.t < 5 * 60_000L }
        val areaSwitches = recent.zipWithNext().count { (a, b) -> a.area != b.area }
        val located = recent.filter { it.lat != null }
        val moved = if (located.size >= 2) FollowDetector.haversine(
            located.first().lat!!, located.first().lon!!, located.last().lat!!, located.last().lon!!
        ) else null
        if (areaSwitches >= 3 && (moved == null || moved < 300)) {
            out += Alert(
                "areaflap-${now / (15 * 60_000L)}", now, Severity.MEDIUM, Source.CELL,
                "Häufige Gebietswechsel ohne Bewegung",
                "$areaSwitches Wechsel der Location/Tracking Area in 5 min" +
                    (moved?.let { " bei nur ${it.toInt()} m Bewegung" } ?: "") +
                    ". Kann auf eine Fake-Basisstation hindeuten, die Telefone abfängt."
            )
        }

        // 4) Serving network differs from SIM operator without roaming.
        val net = s.networkOperator
        val sim = s.simOperator
        if (!net.isNullOrEmpty() && !sim.isNullOrEmpty() && net != sim && !s.roaming &&
            net.take(3) == sim.take(3) && serving.mcc != null
        ) {
            out += Alert(
                "opmismatch-$net-$sim", now, Severity.LOW, Source.CELL,
                "Fremdes Netz ohne Roaming",
                "Eingebucht in $net (${s.operatorName}), SIM gehört zu $sim. Bei MVNOs/National Roaming normal, sonst auffällig."
            )
        }

        // 5) Suspiciously strong serving signal.
        val dbm = serving.dbm
        if (dbm != null && dbm > -50 && serving.tech in setOf("GSM", "LTE")) {
            out += Alert(
                "strong-${serving.key}", now, Severity.LOW, Source.CELL,
                "Ungewöhnlich starke Funkzelle ($dbm dBm)",
                "Die versorgende Zelle ist extrem stark. Direkt neben einem Mast normal – " +
                    "in Innenräumen kann es auf einen mobilen Sender in der Nähe hinweisen."
            )
        }
        return out
    }
}
