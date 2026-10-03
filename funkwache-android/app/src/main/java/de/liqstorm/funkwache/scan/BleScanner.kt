package de.liqstorm.funkwache.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import de.liqstorm.funkwache.analysis.FollowDetector
import de.liqstorm.funkwache.analysis.SpamDetector
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.core.hex
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.BleDevice
import de.liqstorm.funkwache.model.DeviceKind
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source

@SuppressLint("MissingPermission")
object BleScanner {
    private const val TAG = "BleScanner"
    private val devices = HashMap<String, BleDevice>()
    private var callback: ScanCallback? = null
    private var receiverRegistered = false
    @Volatile private var dirty = false

    val follow = FollowDetector()
    private val spam = SpamDetector()

    private fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun isEnabled(ctx: Context) = try {
        adapter(ctx)?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun bondedAddresses(ctx: Context): Set<String> = try {
        if (Perms.btConnect(ctx)) adapter(ctx)?.bondedDevices?.map { it.address }?.toSet() ?: emptySet() else emptySet()
    } catch (e: SecurityException) {
        emptySet()
    }

    /**
     * @param active true while the app is visible: unfiltered low-latency scan.
     * Background scans use filters, because Android delivers no results for unfiltered
     * scans while the screen is off.
     */
    fun start(ctx: Context, active: Boolean) {
        stop(ctx)
        registerReceiver(ctx)
        if (!Perms.bleScan(ctx)) return
        val scanner = try {
            adapter(ctx)?.takeIf { it.isEnabled }?.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        } ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(if (active) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(ctx, result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(ctx, it) }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed $errorCode")
                Hub.bleScanning.value = false
            }
        }
        try {
            if (active) scanner.startScan(null, settings, cb) else scanner.startScan(backgroundFilters(), settings, cb)
            callback = cb
            Hub.bleScanning.value = true
        } catch (e: Exception) {
            Log.w(TAG, "startScan", e)
        }
    }

    fun stop(ctx: Context) {
        val cb = callback ?: return
        callback = null
        Hub.bleScanning.value = false
        try {
            adapter(ctx)?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
    }

    private fun backgroundFilters(): List<ScanFilter> {
        val l = mutableListOf<ScanFilter>()
        for (id in listOf(0x004C, 0x0006, 0x0075, 0x01AB, 0x058E)) {
            l += ScanFilter.Builder().setManufacturerData(id, ByteArray(0)).build()
        }
        for (s in listOf(0xFD5A, 0xFEED, 0xFEEC, 0xFE33, 0xFEAA, 0xFE2C, 0x3082)) {
            val u = ParcelUuid(BleParser.uuid16(s))
            l += ScanFilter.Builder().setServiceUuid(u).build()
            l += ScanFilter.Builder().setServiceData(u, ByteArray(0)).build()
        }
        l += ScanFilter.Builder().setServiceUuid(ParcelUuid(BleParser.DULT_UUID)).build()
        return l
    }

    private fun handle(ctx: Context, r: ScanResult) {
        val now = System.currentTimeMillis()
        val rec = r.scanRecord
        val manu = HashMap<Int, ByteArray>()
        rec?.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) manu[sa.keyAt(i)] = sa.valueAt(i) }
        val uuids = rec?.serviceUuids?.map { it.uuid } ?: emptyList()
        val sdata = rec?.serviceData?.mapKeys { it.key.uuid } ?: emptyMap()
        val address = r.device.address
        val name = rec?.deviceName ?: try {
            if (Perms.btConnect(ctx)) r.device.name else null
        } catch (e: SecurityException) {
            null
        }
        val info = BleParser.parse(name, address, manu, uuids, sdata)
        val tx = r.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
            ?: rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        val bonded = isBonded(ctx, r.device)

        synchronized(devices) {
            val prev = devices[address]
            devices[address] = BleDevice(
                address = address,
                name = name ?: prev?.name,
                rssi = r.rssi,
                rssiSmooth = prev?.let { it.rssiSmooth * 0.7 + r.rssi * 0.3 } ?: r.rssi.toDouble(),
                txPower = tx ?: prev?.txPower,
                firstSeen = prev?.firstSeen ?: now,
                lastSeen = now,
                seenCount = (prev?.seenCount ?: 0) + 1,
                vendor = info.vendor,
                kind = info.kind,
                label = info.label,
                tracker = info.tracker,
                separated = info.separated,
                connectable = r.isConnectable,
                classic = prev?.classic ?: false,
                bonded = bonded,
                services = info.services,
                notes = info.notes,
                raw = rec?.bytes?.let { trimRaw(it) } ?: "",
            )
            dirty = true
        }

        if (info.popup) {
            spam.record(address, now)?.let { n ->
                Hub.raise(
                    Alert(
                        "blespam", now, Severity.HIGH, Source.BLE, "BLE-Spam-Angriff erkannt",
                        "$n verschiedene Sender haben in 10 s Pairing-Pop-ups ausgelöst. Typisch für Flipper Zero/ESP32 " +
                            "„BLE Spam“. Kann Pop-up-Flut, Abstürze oder ungewolltes Koppeln verursachen – Bluetooth kurz ausschalten."
                    ), dedupeMs = 10 * 60_000L
                )
            }
        }
        if (info.hackTool && !Hub.isTrusted(address)) {
            Hub.raise(
                Alert(
                    "hack-$address", now, Severity.MEDIUM, Source.BLE, "Hacking-Tool in der Nähe",
                    "${info.label} ($address, ${r.rssi} dBm). Flipper Zero & Co. können Funkangriffe ausführen."
                )
            )
        }
        if (info.cameraWearable && !Hub.isTrusted(address)) {
            Hub.raise(
                Alert(
                    "camwear-$address", now, Severity.LOW, Source.BLE, "Mögliche Kamerabrille in der Nähe",
                    "${info.label} ($address, ${r.rssi} dBm). Smart-Brillen können unauffällig filmen."
                )
            )
        }
        if (!bonded && !Hub.isTrusted(address)) {
            val loc = Hub.location.value
            follow.sighting(address, info.label, info.followRelevantTracker, now, loc?.latitude, loc?.longitude)
        }
    }

    private fun isBonded(ctx: Context, d: BluetoothDevice) = try {
        Perms.btConnect(ctx) && d.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: SecurityException) {
        false
    }

    private fun trimRaw(b: ByteArray): String {
        // strip zero padding at the end of legacy 31-byte advertisements
        var end = b.size
        while (end > 0 && b[end - 1].toInt() == 0) end--
        return b.copyOf(end).hex(" ")
    }

    // ------------------------------------------------------------- classic

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice = (if (android.os.Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val cls: BluetoothClass? = if (android.os.Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
                    onClassic(c, dev, if (rssi == Short.MIN_VALUE.toInt()) -100 else rssi, cls)
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> Hub.classicDiscovering.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> Hub.classicDiscovering.value = false
                BluetoothAdapter.ACTION_STATE_CHANGED -> Hub.envTick.value++
            }
        }
    }

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx.applicationContext, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    fun startClassicDiscovery(ctx: Context): Boolean {
        registerReceiver(ctx)
        if (!Perms.bleScan(ctx)) return false
        return try {
            val a = adapter(ctx) ?: return false
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
    }

    private fun onClassic(ctx: Context, dev: BluetoothDevice, rssi: Int, cls: BluetoothClass?) {
        val now = System.currentTimeMillis()
        val name = try {
            if (Perms.btConnect(ctx)) dev.name else null
        } catch (e: SecurityException) {
            null
        }
        val info = BleParser.parse(name, dev.address, emptyMap(), emptyList(), emptyMap())
        val bonded = isBonded(ctx, dev)
        var kind = info.kind
        var label = info.label
        when (cls?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> {
                val dc = cls.deviceClass
                if (dc == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || dc == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) {
                    kind = DeviceKind.VEHICLE; if (name == null) label = "Freisprecheinrichtung/Auto"
                } else if (dc == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA || dc == BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER) {
                    kind = DeviceKind.CAMERA; if (name == null) label = "Videokamera"
                } else if (kind == DeviceKind.UNKNOWN) {
                    kind = DeviceKind.AUDIO
                }
            }
            BluetoothClass.Device.Major.COMPUTER -> if (kind == DeviceKind.UNKNOWN) kind = DeviceKind.COMPUTER
            BluetoothClass.Device.Major.PHONE -> if (kind == DeviceKind.UNKNOWN) kind = DeviceKind.PHONE
            BluetoothClass.Device.Major.WEARABLE, BluetoothClass.Device.Major.HEALTH ->
                if (kind == DeviceKind.UNKNOWN) kind = DeviceKind.WEARABLE
            BluetoothClass.Device.Major.PERIPHERAL -> if (kind == DeviceKind.UNKNOWN) kind = DeviceKind.PERIPHERAL
            BluetoothClass.Device.Major.IMAGING -> if (kind == DeviceKind.UNKNOWN) kind = DeviceKind.CAMERA
            BluetoothClass.Device.Major.NETWORKING -> if (kind == DeviceKind.UNKNOWN) kind = DeviceKind.IOT
        }
        if (label == DeviceKind.UNKNOWN.label || label == info.kind.label) label = name ?: kind.label
        synchronized(devices) {
            val prev = devices[dev.address]
            devices[dev.address] = (prev ?: BleDevice(
                address = dev.address, name = name, rssi = rssi, rssiSmooth = rssi.toDouble(), txPower = null,
                firstSeen = now, lastSeen = now, seenCount = 0, vendor = info.vendor, kind = kind, label = label,
                tracker = null, separated = false, connectable = true, classic = true,
                bonded = bonded, services = emptyList(),
                notes = emptyList(), raw = "",
            )).copy(
                name = name ?: prev?.name, rssi = rssi, lastSeen = now, seenCount = (prev?.seenCount ?: 0) + 1,
                classic = true, kind = if (prev != null && prev.kind != DeviceKind.UNKNOWN) prev.kind else kind,
                label = if (prev != null && prev.kind != DeviceKind.UNKNOWN) prev.label else label,
                notes = (prev?.notes ?: emptyList()) + listOfNotNull(cls?.let { "Bluetooth-Klasse 0x%06X".format(it.hashCode()) })
                    .filter { prev?.notes?.contains(it) != true },
            )
            dirty = true
        }
        if (!Hub.isTrusted(dev.address) && !bonded) {
            val loc = Hub.location.value
            follow.sighting(dev.address, label, false, now, loc?.latitude, loc?.longitude)
        }
    }

    // ------------------------------------------------------------- publish

    /** Copies the device table into the UI state; drops devices not seen for [maxAgeMs]. */
    fun publish(maxAgeMs: Long = 5 * 60_000L) {
        val now = System.currentTimeMillis()
        val list = synchronized(devices) {
            devices.values.removeAll { now - it.lastSeen > maxAgeMs }
            if (!dirty && Hub.ble.value.size == devices.size) return
            dirty = false
            devices.values.toList()
        }
        Hub.ble.value = list.sortedByDescending { it.rssiSmooth }
    }

    fun clear() {
        synchronized(devices) { devices.clear() }
        Hub.ble.value = emptyList()
    }
}
