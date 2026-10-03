package de.liqstorm.funkwache.scan

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.GnssSnapshot
import de.liqstorm.funkwache.model.SatInfo
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source
import kotlin.math.sqrt

/** Location for follow detection plus GNSS satellite monitoring for spoofing/jamming hints. */
@SuppressLint("MissingPermission")
object Positioning {
    private const val TAG = "Positioning"
    private var running = false
    private var gpsOn = false
    private var lastGpsFix: Location? = null
    private var goodSince = 0L
    private var lastGood: GnssSnapshot? = null

    private fun lm(ctx: Context) = ctx.applicationContext.getSystemService(LocationManager::class.java)

    fun locationEnabled(ctx: Context) = try {
        val lm = lm(ctx)
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        false
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onLocation(l)
        override fun onProviderEnabled(provider: String) { Hub.envTick.value++ }
        override fun onProviderDisabled(provider: String) { Hub.envTick.value++ }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) = onGnss(status)
        override fun onStopped() {
            Hub.gnss.value = null
        }
    }

    /** @param gps also run the GPS receiver (needed for satellite data; costs battery). */
    fun start(ctx: Context, gps: Boolean) {
        if (!Perms.location(ctx)) return
        if (running && gps == gpsOn) return
        stop(ctx)
        val lm = lm(ctx)
        try {
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                if (lm.allProviders.contains(p)) lm.requestLocationUpdates(p, 20_000L, 20f, listener, Looper.getMainLooper())
            }
            if (gps && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
                lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            }
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null && Hub.location.value == null) Hub.location.value = last
            running = true
            gpsOn = gps
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
        }
    }

    fun stop(ctx: Context) {
        if (!running) return
        try {
            val lm = lm(ctx)
            lm.removeUpdates(listener)
            lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stop", e)
        }
        running = false
        gpsOn = false
        Hub.gnss.value = null
    }

    private fun onLocation(l: Location) {
        val cur = Hub.location.value
        // keep the more accurate fix when two arrive close together
        if (cur == null || l.time - cur.time > 30_000 || l.accuracy <= cur.accuracy) Hub.location.value = l

        if (l.provider == LocationManager.GPS_PROVIDER && l.accuracy < 50) {
            val prev = lastGpsFix
            if (prev != null) {
                val dt = (l.time - prev.time) / 1000.0
                val d = prev.distanceTo(l)
                if (dt in 0.5..120.0 && d / dt > 350 && d > 2000) {
                    Hub.raise(
                        Alert(
                            "gpsjump-${l.time / 600_000}", l.time, Severity.MEDIUM, Source.GNSS,
                            "Unplausibler GPS-Sprung",
                            "Position sprang ${d.toInt()} m in ${"%.0f".format(dt)} s (${(d / dt * 3.6).toInt()} km/h). " +
                                "Mögliches GPS-Spoofing – Standortangaben gerade nicht vertrauen."
                        )
                    )
                }
            }
            lastGpsFix = l
        }
    }

    private fun constellation(t: Int) = when (t) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        7 -> "NavIC"
        else -> "?"
    }

    private fun onGnss(s: GnssStatus) {
        val now = System.currentTimeMillis()
        val sats = (0 until s.satelliteCount).map {
            SatInfo(constellation(s.getConstellationType(it)), s.getSvid(it), s.getCn0DbHz(it), s.usedInFix(it), s.getElevationDegrees(it))
        }
        val heard = sats.filter { it.cn0 > 0 }
        val mean = if (heard.isEmpty()) 0.0 else heard.map { it.cn0.toDouble() }.average()
        val std = if (heard.size < 2) 0.0 else sqrt(heard.map { (it.cn0 - mean) * (it.cn0 - mean) }.average())
        val snap = GnssSnapshot(now, sats, sats.count { it.used }, mean, std)
        Hub.gnss.value = snap

        // Spoofing: a single transmitter produces near-identical, strong signal levels on all satellites.
        if (heard.size >= 8 && mean > 38 && std < 1.5) {
            Hub.raise(
                Alert(
                    "gnssspoof-${now / 1800_000}", now, Severity.MEDIUM, Source.GNSS,
                    "Verdacht auf GPS-Spoofing",
                    "${heard.size} Satelliten mit fast identischer Signalstärke (Ø ${"%.1f".format(mean)} dB-Hz, " +
                        "Streuung ${"%.2f".format(std)}). Echte Satelliten unterscheiden sich deutlich."
                )
            )
        }
        // Jamming: good reception collapses within seconds.
        if (snap.used >= 6 && mean > 25) {
            if (goodSince == 0L) goodSince = now
            lastGood = snap
        } else {
            val good = lastGood
            if (good != null && goodSince != 0L && now - good.time < 15_000 && good.time - goodSince > 30_000 &&
                (heard.size <= 2 || mean < 15)
            ) {
                Hub.raise(
                    Alert(
                        "gnssjam-${now / 1800_000}", now, Severity.LOW, Source.GNSS,
                        "GNSS-Empfang schlagartig eingebrochen",
                        "Eben noch ${good.used} Satelliten genutzt, jetzt ${heard.size} hörbar (Ø ${"%.1f".format(mean)} dB-Hz). " +
                            "Kann ein Störsender (Jammer) sein – oder einfach ein Gebäude/Tunnel."
                    )
                )
            }
            goodSince = 0L
            lastGood = null
        }
    }
}
