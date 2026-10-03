package de.liqstorm.funkwache.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.liqstorm.funkwache.R
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.service.GuardService
import de.liqstorm.funkwache.ui.MainActivity

object Notifier {
    const val CH_GUARD = "guard"
    const val CH_ALERT = "alerts"
    const val GUARD_ID = 1

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, "Wächter-Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Dauerhafte Anzeige, solange der Hintergrund-Wächter läuft"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Warnungen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tracker, Evil-Twin-WLANs, IMSI-Catcher-Verdacht, Systemänderungen"
                enableVibration(true)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun guardNotification(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, GuardService::class.java).setAction(GuardService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_GUARD)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Funkwache aktiv")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Beenden", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateGuard(ctx: Context, text: String) {
        if (!canNotify(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(GUARD_ID, guardNotification(ctx, text))
        } catch (_: SecurityException) {
        }
    }

    fun alert(ctx: Context, a: Alert) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("${if (a.severity == Severity.HIGH) "⚠ " else ""}${a.title}")
            .setContentText(a.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.detail))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(
                if (a.severity == Severity.HIGH) NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_HIGH
            )
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(a.key.hashCode() and 0x7fffffff or 0x100, n)
        } catch (_: SecurityException) {
        }
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
