package de.liqstorm.funkwache.core

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.AuditItem
import de.liqstorm.funkwache.model.BleDevice
import de.liqstorm.funkwache.model.CellStatus
import de.liqstorm.funkwache.model.GnssSnapshot
import de.liqstorm.funkwache.model.NfcTagInfo
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source
import de.liqstorm.funkwache.model.WifiConnection
import de.liqstorm.funkwache.model.WifiNet
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Central app state shared by the scanners, the guard service and the UI. */
object Hub {
    lateinit var app: Context
        private set
    private lateinit var prefs: SharedPreferences

    val ble = MutableStateFlow<List<BleDevice>>(emptyList())
    val bleScanning = MutableStateFlow(false)
    val classicDiscovering = MutableStateFlow(false)
    val wifi = MutableStateFlow<List<WifiNet>>(emptyList())
    val wifiConn = MutableStateFlow<WifiConnection?>(null)
    val wifiLastScan = MutableStateFlow(0L)
    val wifiThrottled = MutableStateFlow(false)
    val cell = MutableStateFlow<CellStatus?>(null)
    val gnss = MutableStateFlow<GnssSnapshot?>(null)
    val location = MutableStateFlow<Location?>(null)
    val alerts = MutableStateFlow<List<Alert>>(emptyList())
    val audit = MutableStateFlow<List<AuditItem>>(emptyList())
    val nfcTags = MutableStateFlow<List<NfcTagInfo>>(emptyList())
    val guardRunning = MutableStateFlow(false)
    val trusted = MutableStateFlow<Set<String>>(emptySet())
    /** Bumped when permissions or radio states may have changed. */
    val envTick = MutableStateFlow(0)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("funkwache", Context.MODE_PRIVATE)
        trusted.value = prefs.getStringSet("trusted", emptySet())!!.toSet()
        alerts.value = loadAlerts()
    }

    // ---------------------------------------------------------------- alerts

    @Synchronized
    fun raise(alert: Alert, dedupeMs: Long = 6 * 3600_000L) {
        val now = alert.time
        val existing = alerts.value.firstOrNull { it.key == alert.key && now - it.time < dedupeMs }
        if (existing != null && existing.severity.ordinal >= alert.severity.ordinal) return
        val list = (listOf(alert) + alerts.value.filter { it !== existing }).take(400)
        alerts.value = list
        saveAlerts(list)
        if (alert.severity.ordinal >= Severity.MEDIUM.ordinal ||
            (alert.severity == Severity.LOW && Prefs.notifyLow)
        ) {
            Notifier.alert(app, alert)
        }
    }

    fun clearAlerts() {
        alerts.value = emptyList()
        saveAlerts(emptyList())
    }

    fun dismiss(alert: Alert) {
        val list = alerts.value.filter { it !== alert }
        alerts.value = list
        saveAlerts(list)
    }

    private fun saveAlerts(list: List<Alert>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("k", it.key).put("t", it.time).put("s", it.severity.name)
                    .put("src", it.source.name).put("ti", it.title).put("d", it.detail)
            )
        }
        prefs.edit().putString("alerts", arr.toString()).apply()
    }

    private fun loadAlerts(): List<Alert> = try {
        val arr = JSONArray(prefs.getString("alerts", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Alert(
                o.getString("k"), o.getLong("t"), Severity.valueOf(o.getString("s")),
                Source.valueOf(o.getString("src")), o.getString("ti"), o.getString("d")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ------------------------------------------------------- trusted devices

    fun isTrusted(id: String) = id.uppercase() in trusted.value

    fun setTrusted(id: String, trust: Boolean) {
        val s = trusted.value.toMutableSet()
        if (trust) s += id.uppercase() else s -= id.uppercase()
        trusted.value = s
        prefs.edit().putStringSet("trusted", s).apply()
    }

    // ------------------------------------------- known networks (evil twin)

    /** SSID -> best security rank we have seen while connected to it. */
    fun knownNetworks(): Map<String, Int> = try {
        val o = JSONObject(prefs.getString("known_wifi", "{}")!!)
        o.keys().asSequence().associateWith { o.getInt(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    fun rememberNetwork(ssid: String, rank: Int) {
        val o = JSONObject(prefs.getString("known_wifi", "{}")!!)
        if (o.optInt(ssid, -1) < rank) {
            o.put(ssid, rank)
            prefs.edit().putString("known_wifi", o.toString()).apply()
        }
    }

    // ------------------------------------------------- audit baselines

    fun baseline(key: String): Set<String>? =
        if (prefs.contains("base_$key")) prefs.getStringSet("base_$key", emptySet())!!.toSet() else null

    fun setBaseline(key: String, value: Set<String>) {
        prefs.edit().putStringSet("base_$key", value).apply()
    }

    internal fun prefs() = prefs
}

/** User settings. */
object Prefs {
    private val p get() = Hub.prefs()

    var startOnBoot: Boolean
        get() = p.getBoolean("start_on_boot", true)
        set(v) = p.edit().putBoolean("start_on_boot", v).apply()

    /** Use GPS in the background guard (spoofing detection, better follow detection; costs battery). */
    var gnssGuard: Boolean
        get() = p.getBoolean("gnss_guard", false)
        set(v) = p.edit().putBoolean("gnss_guard", v).apply()

    var notifyLow: Boolean
        get() = p.getBoolean("notify_low", false)
        set(v) = p.edit().putBoolean("notify_low", v).apply()

    var guardWanted: Boolean
        get() = p.getBoolean("guard_wanted", false)
        set(v) = p.edit().putBoolean("guard_wanted", v).apply()
}
