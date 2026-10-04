package de.liqstorm.funkwache.core

import android.content.Context
import de.liqstorm.funkwache.audit.SystemAudit
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.Source
import de.liqstorm.funkwache.scan.BleScanner
import de.liqstorm.funkwache.scan.CellScanner
import de.liqstorm.funkwache.scan.Positioning
import de.liqstorm.funkwache.scan.PrivacyMonitor
import de.liqstorm.funkwache.scan.WifiScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs all scanners. Users ("ui", "guard") acquire it; while the UI is visible it scans
 * aggressively, with only the guard left it switches to a battery-friendly duty cycle.
 */
object ScanEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val users = LinkedHashSet<String>()
    private var job: Job? = null
    private var activeMode = false
    private var guardMode = false
    private var gpsWanted = false

    fun acquire(ctx: Context, user: String) {
        users += user
        apply(ctx.applicationContext, force = false)
    }

    fun release(ctx: Context, user: String) {
        users -= user
        apply(ctx.applicationContext, force = false)
    }

    /** Restart after permission or radio changes. */
    fun restart(ctx: Context) = apply(ctx.applicationContext, force = true)

    /** The GNSS view wants live satellite data. */
    fun setGps(ctx: Context, on: Boolean) {
        gpsWanted = on
        if (users.isNotEmpty()) Positioning.start(ctx.applicationContext, gps())
    }

    private fun gps() = gpsWanted || ("guard" in users && Prefs.gnssGuard)

    private fun apply(app: Context, force: Boolean) {
        if (users.isEmpty()) {
            job?.cancel()
            job = null
            BleScanner.stop(app)
            Positioning.stop(app)
            return
        }
        val active = "ui" in users
        val guard = "guard" in users
        if (!force && job != null && active == activeMode && guard == guardMode) {
            Positioning.start(app, gps())
            return
        }
        job?.cancel()
        activeMode = active
        guardMode = guard
        job = scope.launch { loop(app, active, guard) }
    }

    private suspend fun loop(app: Context, active: Boolean, guard: Boolean): Unit = coroutineScope {
        Positioning.stop(app)
        Positioning.start(app, gps())
        WifiScanner.register(app)
        PrivacyMonitor.start(app)

        // BLE: (re)start regularly – Android silently degrades long-running scans after ~30 min.
        launch {
            var started = 0L
            val period = if (active) 10 * 60_000L else 25 * 60_000L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (!Hub.bleScanning.value || now - started > period) {
                    BleScanner.start(app, active)
                    started = now
                }
                delay(15_000)
            }
        }
        launch {
            while (isActive) {
                BleScanner.publish()
                delay(if (active) 700 else 5_000)
            }
        }
        launch {
            while (isActive) {
                WifiScanner.scan(app)
                delay(if (active) 30_000 else 3 * 60_000L)
            }
        }
        launch {
            while (isActive) {
                CellScanner.poll(app)
                delay(if (active) 8_000 else 30_000)
            }
        }
        launch {
            while (isActive) {
                delay(20_000)
                val now = System.currentTimeMillis()
                BleScanner.follow.evaluate(now).forEach {
                    Hub.raise(Alert("follow-${it.id}", now, it.severity, Source.BLE, it.title, it.detail))
                }
            }
        }
        launch {
            while (isActive) {
                Hub.audit.value = withContext(Dispatchers.Default) { SystemAudit.run(app) }
                delay(if (active) 2 * 60_000L else 15 * 60_000L)
            }
        }
        if (guard) {
            launch {
                while (isActive) {
                    delay(60_000)
                    Notifier.updateGuard(app, summary())
                }
            }
        }
    }

    fun summary(): String {
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        val alerts = Hub.alerts.value.count { it.time > dayAgo && it.severity.ordinal >= 2 }
        val trackers = Hub.ble.value.count { it.tracker != null }
        return "${Hub.ble.value.size} BT-Geräte ($trackers Tracker) · ${Hub.wifi.value.size} WLANs · " +
            if (alerts == 0) "keine Warnungen" else "$alerts Warnung(en) in 24 h"
    }
}
