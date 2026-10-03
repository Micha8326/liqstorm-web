package de.liqstorm.funkwache.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

val Byte.u: Int get() = toInt() and 0xFF

fun ByteArray.hex(sep: String = ""): String = joinToString(sep) { "%02X".format(it.u) }

fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - time) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "vor $s s"
        s < 3600 -> "vor ${s / 60} min"
        s < 86400 -> "vor ${s / 3600} h"
        else -> "vor ${s / 86400} d"
    }
}

fun duration(ms: Long): String {
    val m = ms / 60000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

fun fmtDist(m: Double): String = when {
    m < 1 -> "< 1 m"
    m < 10 -> "~%.1f m".format(m)
    m < 1000 -> "~%.0f m".format(m)
    else -> "~%.1f km".format(m / 1000)
}

object Perms {
    fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun location(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun bleScan(ctx: Context) = if (Build.VERSION.SDK_INT >= 31)
        has(ctx, Manifest.permission.BLUETOOTH_SCAN) && location(ctx)
    else location(ctx)

    fun btConnect(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
        has(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    fun phone(ctx: Context) = has(ctx, Manifest.permission.READ_PHONE_STATE)

    /** All runtime permissions the app asks for, depending on the Android version. */
    fun wanted(): Array<String> {
        val l = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l += Manifest.permission.POST_NOTIFICATIONS
            l += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return l.toTypedArray()
    }

    fun missing(ctx: Context) = wanted().filterNot { has(ctx, it) }
}
