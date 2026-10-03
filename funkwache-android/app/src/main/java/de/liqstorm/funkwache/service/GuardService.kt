package de.liqstorm.funkwache.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Notifier
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.core.Prefs
import de.liqstorm.funkwache.core.ScanEngine

/** Foreground service that keeps the scanners running while the app is closed. */
class GuardService : Service() {
    private var acquired = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.guardWanted = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Perms.location(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(
                this, Notifier.GUARD_ID,
                Notifier.guardNotification(this, "Überwache Bluetooth, WLAN, Mobilfunk und System …"), type
            )
        } catch (e: Exception) {
            Log.w("GuardService", "startForeground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!acquired) {
            ScanEngine.acquire(this, "guard")
            acquired = true
        }
        Hub.guardRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        if (acquired) ScanEngine.release(this, "guard")
        acquired = false
        Hub.guardRunning.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "de.liqstorm.funkwache.STOP_GUARD"

        fun start(ctx: Context) {
            Prefs.guardWanted = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs.guardWanted = false
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.startOnBoot || !Prefs.guardWanted) return
        try {
            GuardService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "could not start guard", e)
        }
    }
}
