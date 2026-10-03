package de.liqstorm.funkwache.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import de.liqstorm.funkwache.analysis.WifiAnalyzer
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Oui
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.model.WifiConnection
import de.liqstorm.funkwache.model.WifiNet

@SuppressLint("MissingPermission")
object WifiScanner {
    private const val TAG = "WifiScanner"
    private val nets = HashMap<String, WifiNet>()
    private var registered = false

    private fun wm(ctx: Context) = ctx.applicationContext.getSystemService(WifiManager::class.java)

    fun isEnabled(ctx: Context) = wm(ctx)?.isWifiEnabled == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> collect(c)
                WifiManager.WIFI_STATE_CHANGED_ACTION -> Hub.envTick.value++
            }
        }
    }

    fun register(ctx: Context) {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx.applicationContext, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    /** Requests a scan. Android throttles apps to 4 scans per 2 minutes. */
    fun scan(ctx: Context) {
        register(ctx)
        if (!Perms.location(ctx)) return
        val ok = try {
            @Suppress("DEPRECATION")
            wm(ctx)?.startScan() == true
        } catch (e: Exception) {
            false
        }
        Hub.wifiThrottled.value = !ok
        collect(ctx)
    }

    fun collect(ctx: Context) {
        if (!Perms.location(ctx)) return
        val results: List<ScanResult> = try {
            wm(ctx)?.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val now = System.currentTimeMillis()
        synchronized(nets) {
            for (r in results) {
                val n = toNet(r, now, nets[r.BSSID])
                nets[n.bssid] = n
            }
            nets.values.removeAll { now - it.lastSeen > 5 * 60_000L }
        }
        if (results.isNotEmpty()) Hub.wifiLastScan.value = now
        val list = synchronized(nets) { nets.values.sortedByDescending { it.rssi } }
        Hub.wifi.value = list

        val conn = connection(ctx, list)
        Hub.wifiConn.value = conn
        if (conn?.ssid != null && conn.securityRank != null) Hub.rememberNetwork(conn.ssid, conn.securityRank)
        WifiAnalyzer.analyze(list, conn, Hub.knownNetworks(), now).forEach { Hub.raise(it) }
    }

    @Suppress("DEPRECATION")
    private fun toNet(r: ScanResult, now: Long, prev: WifiNet?): WifiNet {
        val ssid = (if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.removeSurrounding("\"") else r.SSID) ?: ""
        val (sec, rank) = WifiAnalyzer.security(r.capabilities ?: "")
        // ScanResult timestamp is µs since boot; convert to wall clock for "last seen".
        val ageMs = (android.os.SystemClock.elapsedRealtime() - r.timestamp / 1000).coerceAtLeast(0)
        val lastSeen = now - ageMs
        val std = if (Build.VERSION.SDK_INT >= 30) when (r.wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (be)"
            else -> null
        } else null
        val width = when (r.channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            5 -> 320
            else -> null
        }
        val base = WifiNet(
            bssid = r.BSSID.uppercase(),
            ssid = ssid.takeUnless { it == "<unknown ssid>" } ?: "",
            frequency = r.frequency,
            channel = WifiAnalyzer.channel(r.frequency),
            band = WifiAnalyzer.band(r.frequency),
            rssi = r.level,
            security = sec,
            securityRank = rank,
            capabilities = r.capabilities ?: "",
            vendor = Oui.vendor(r.BSSID),
            localMac = Oui.isLocal(r.BSSID),
            standard = std,
            width = width,
            wps = r.capabilities?.contains("WPS") == true,
            firstSeen = prev?.firstSeen ?: lastSeen,
            lastSeen = maxOf(lastSeen, prev?.lastSeen ?: 0),
            flags = emptyList(),
        )
        return base.copy(flags = WifiAnalyzer.flags(base))
    }

    @Suppress("DEPRECATION")
    private fun connection(ctx: Context, list: List<WifiNet>): WifiConnection? {
        val wm = wm(ctx) ?: return null
        val info = try {
            wm.connectionInfo
        } catch (e: Exception) {
            null
        } ?: return null
        val bssid = info.bssid?.uppercase()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
        if (bssid == null && info.networkId == -1) return null
        val ssid = info.ssid?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" || it.isEmpty() }
        val match = list.firstOrNull { it.bssid == bssid }
        val ipInt = info.ipAddress
        val ip = if (ipInt == 0) null else
            "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"

        var vpn = false
        var proxy: String? = null
        var privateDns: String? = null
        var dns = emptyList<String>()
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) vpn = true
            }
            val lp = cm.getLinkProperties(cm.activeNetwork)
            proxy = lp?.httpProxy?.takeIf { !it.host.isNullOrEmpty() }?.let { "${it.host}:${it.port}" }
                ?: cm.defaultProxy?.takeIf { !it.host.isNullOrEmpty() }?.let { "${it.host}:${it.port}" }
            if (Build.VERSION.SDK_INT >= 28 && lp?.isPrivateDnsActive == true) {
                privateDns = lp.privateDnsServerName ?: "automatisch"
            }
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "link properties", e)
        }
        return WifiConnection(
            ssid = ssid, bssid = bssid, rssi = info.rssi, linkSpeed = info.linkSpeed,
            frequency = info.frequency, ip = ip, security = match?.security, securityRank = match?.securityRank,
            vpn = vpn, proxy = proxy, privateDns = privateDns, dns = dns,
        )
    }
}
