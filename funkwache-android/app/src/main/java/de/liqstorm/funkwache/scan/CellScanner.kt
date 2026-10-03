package de.liqstorm.funkwache.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import de.liqstorm.funkwache.analysis.CellAnalyzer
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.model.CellObs
import de.liqstorm.funkwache.model.CellStatus

@SuppressLint("MissingPermission")
object CellScanner {
    private const val TAG = "CellScanner"
    private val analyzer = CellAnalyzer()

    private fun tm(ctx: Context) = ctx.applicationContext.getSystemService(TelephonyManager::class.java)

    fun poll(ctx: Context) {
        if (!Perms.location(ctx)) return
        val tm = tm(ctx) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(ctx),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) = onCells(ctx, cellInfo)
                        override fun onError(errorCode: Int, detail: Throwable?) {
                            onCells(ctx, safeAll(tm))
                        }
                    }
                )
            } else {
                onCells(ctx, safeAll(tm))
            }
        } catch (e: Exception) {
            Log.w(TAG, "poll", e)
        }
    }

    private fun safeAll(tm: TelephonyManager): List<CellInfo> = try {
        tm.allCellInfo ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun onCells(ctx: Context, infos: List<CellInfo>) {
        val tm = tm(ctx) ?: return
        val cells = infos.mapNotNull { toObs(it) }
            .sortedWith(compareByDescending<CellObs> { it.registered }.thenByDescending { it.dbm ?: -200 })
        val dataType = if (Perms.phone(ctx)) try {
            netTypeName(tm.dataNetworkType)
        } catch (e: SecurityException) {
            null
        } else null
        val s = CellStatus(
            time = System.currentTimeMillis(),
            operatorName = tm.networkOperatorName,
            networkOperator = tm.networkOperator,
            simOperator = tm.simOperator,
            roaming = tm.isNetworkRoaming,
            dataNetwork = dataType,
            cells = cells,
        )
        Hub.cell.value = s
        val loc = Hub.location.value
        analyzer.analyze(s, loc?.latitude, loc?.longitude).forEach { Hub.raise(it) }
    }

    private fun Int.valid(): Int? = takeIf { it != Int.MAX_VALUE && it != CellInfo.UNAVAILABLE && it >= 0 }
    private fun Long.valid(): Long? = takeIf { it != Long.MAX_VALUE && it >= 0 }
    private fun Int.dbm(): Int? = takeIf { it != Int.MAX_VALUE && it != CellInfo.UNAVAILABLE && it in -160..0 }

    @Suppress("DEPRECATION")
    private fun toObs(ci: CellInfo): CellObs? = when {
        ci is CellInfoLte -> {
            val id = ci.cellIdentity
            CellObs(
                "LTE", ci.isRegistered, mcc({ id.mccString }, id.mcc), mnc({ id.mncString }, id.mnc),
                id.tac.valid(), id.ci.valid()?.toLong(), id.pci.valid(), id.earfcn.valid(),
                ci.cellSignalStrength.dbm.dbm(), ci.cellSignalStrength.level
            )
        }
        ci is CellInfoGsm -> {
            val id = ci.cellIdentity
            CellObs(
                "GSM", ci.isRegistered, mcc({ id.mccString }, id.mcc), mnc({ id.mncString }, id.mnc),
                id.lac.valid(), id.cid.valid()?.toLong(), null, id.arfcn.valid(),
                ci.cellSignalStrength.dbm.dbm(), ci.cellSignalStrength.level
            )
        }
        ci is CellInfoWcdma -> {
            val id = ci.cellIdentity
            CellObs(
                "UMTS", ci.isRegistered, mcc({ id.mccString }, id.mcc), mnc({ id.mncString }, id.mnc),
                id.lac.valid(), id.cid.valid()?.toLong(), id.psc.valid(), id.uarfcn.valid(),
                ci.cellSignalStrength.dbm.dbm(), ci.cellSignalStrength.level
            )
        }
        Build.VERSION.SDK_INT >= 29 && ci is CellInfoNr -> {
            val id = ci.cellIdentity as CellIdentityNr
            val ss = ci.cellSignalStrength as CellSignalStrengthNr
            CellObs(
                "NR", ci.isRegistered, id.mccString, id.mncString,
                id.tac.valid(), id.nci.valid(), id.pci.valid(), id.nrarfcn.valid(),
                ss.dbm.dbm(), ss.level
            )
        }
        Build.VERSION.SDK_INT >= 29 && ci is CellInfoTdscdma -> {
            val id = ci.cellIdentity
            CellObs(
                "TD-SCDMA", ci.isRegistered, id.mccString, id.mncString,
                id.lac.valid(), id.cid.valid()?.toLong(), null, id.uarfcn.valid(),
                ci.cellSignalStrength.dbm.dbm(), ci.cellSignalStrength.level
            )
        }
        ci is CellInfoCdma -> CellObs(
            "CDMA", ci.isRegistered, null, null, ci.cellIdentity.networkId.valid(),
            ci.cellIdentity.basestationId.valid()?.toLong(), null, null,
            ci.cellSignalStrength.dbm.dbm(), ci.cellSignalStrength.level
        )
        else -> null
    }

    @Suppress("DEPRECATION")
    // String getters exist from API 28; the lambdas keep them from being called on older devices.
    private fun mcc(s: () -> String?, i: Int): String? =
        if (Build.VERSION.SDK_INT >= 28) s() else i.valid()?.toString()

    private fun mnc(s: () -> String?, i: Int): String? =
        if (Build.VERSION.SDK_INT >= 28) s() else i.valid()?.let { "%02d".format(it) }

    fun netTypeName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2G)"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2G)"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA (2G)"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA (3G)"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3G)"
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD -> "EV-DO (3G)"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "WLAN-Calling"
        else -> "unbekannt"
    }
}
