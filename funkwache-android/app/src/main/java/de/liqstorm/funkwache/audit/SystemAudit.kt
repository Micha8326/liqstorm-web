package de.liqstorm.funkwache.audit

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.util.Log
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.AuditItem
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@SuppressLint("MissingPermission")
object SystemAudit {
    private const val TAG = "SystemAudit"

    private val stores = setOf(
        "com.android.vending", "com.google.android.feedback", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "com.amazon.venezia", "org.fdroid.fdroid", "com.aurora.store",
        "com.xiaomi.market", "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market",
        "com.bbk.appstore", "dev.imranr.obtainium", "com.machiav3lli.fdroid", "com.looker.droidify",
    )

    private val spyPerms = listOf(
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA,
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    fun run(ctx: Context): List<AuditItem> {
        val out = ArrayList<AuditItem>()
        val cr = ctx.contentResolver
        fun add(i: AuditItem) { out += i }
        fun safe(block: () -> Unit) = try { block() } catch (e: Exception) { Log.w(TAG, "check", e) }

        // ---- device basics
        safe {
            val secure = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure
            add(
                if (secure) AuditItem("Bildschirmsperre", "PIN/Passwort/Muster ist eingerichtet.", Severity.INFO, true)
                else AuditItem("Keine Bildschirmsperre", "Jeder mit Zugriff auf das Handy kann Spionage-Apps installieren.", Severity.HIGH, false, Settings.ACTION_SECURITY_SETTINGS)
            )
        }
        safe {
            val patch = LocalDate.parse(Build.VERSION.SECURITY_PATCH)
            val days = ChronoUnit.DAYS.between(patch, LocalDate.now())
            val sev = when {
                days > 180 -> Severity.HIGH
                days > 90 -> Severity.MEDIUM
                else -> Severity.INFO
            }
            add(AuditItem("Sicherheitspatch ${Build.VERSION.SECURITY_PATCH}", "$days Tage alt · Android ${Build.VERSION.RELEASE}" +
                if (sev != Severity.INFO) " – Update suchen (Einstellungen › System › Update)." else "", sev, sev == Severity.INFO))
        }
        safe {
            val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk", "/system/app/Superuser.apk")
                .filter { File(it).exists() }
            val testKeys = Build.TAGS?.contains("test-keys") == true
            if (su.isNotEmpty() || testKeys) {
                add(AuditItem("Root-Spuren gefunden", (su + listOfNotNull(if (testKeys) "test-keys-Build" else null)).joinToString() +
                    ". Mit Root kann Schadsoftware alle Schutzmechanismen umgehen. Wenn du nicht selbst gerootet hast: ernstes Warnsignal.", Severity.HIGH, false))
            } else add(AuditItem("Kein Root erkannt", "Keine typischen su/Magisk-Dateien gefunden.", Severity.INFO, true))
        }

        // ---- debugging
        safe {
            val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
            val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
            val adbWifi = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
            when {
                adbWifi -> add(AuditItem("WLAN-Debugging aktiv", "Über das Netzwerk kann ein gekoppelter Rechner das Handy vollständig steuern.", Severity.HIGH, false, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                adb -> add(AuditItem("USB-Debugging aktiv", "Ein angeschlossener Rechner kann Apps installieren und Daten auslesen. Ausschalten, wenn nicht benötigt.", Severity.MEDIUM, false, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                dev -> add(AuditItem("Entwickleroptionen an", "Debugging ist aus, die Optionen sind aber freigeschaltet.", Severity.LOW, false, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                else -> add(AuditItem("Debugging aus", "USB- und WLAN-Debugging sind deaktiviert.", Severity.INFO, true))
            }
            baselineCheck("adbwifi", if (adbWifi) setOf("on") else emptySet(), Severity.HIGH, "WLAN-Debugging wurde eingeschaltet")
        }

        // ---- radios
        safe {
            val a: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            if (a != null && Perms.btConnect(ctx)) {
                val on = a.isEnabled
                val discoverable = on && Perms.bleScan(ctx) && a.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                add(
                    when {
                        discoverable -> AuditItem("Bluetooth sichtbar", "Das Handy ist für alle sichtbar. Nur zum Koppeln aktivieren.", Severity.MEDIUM, false, Settings.ACTION_BLUETOOTH_SETTINGS)
                        on -> AuditItem("Bluetooth an (nicht sichtbar)", "Ausschalten, wenn du es nicht brauchst, verringert die Angriffsfläche.", Severity.INFO, true, Settings.ACTION_BLUETOOTH_SETTINGS)
                        else -> AuditItem("Bluetooth aus", "Keine Bluetooth-Angriffsfläche – aber auch keine Tracker-Erkennung.", Severity.INFO, true, Settings.ACTION_BLUETOOTH_SETTINGS)
                    }
                )
                val devs = a.bondedDevices ?: emptySet()
                val names = devs.associate { it.address to (it.name ?: "?") }
                val bonded = devs.map { "${it.name ?: "?"} (${it.address})" }.sorted()
                add(AuditItem("Gekoppelte Geräte: ${bonded.size}", bonded.joinToString("\n").ifEmpty { "keine" } +
                    "\nUnbekannte Einträge sofort entfernen.", Severity.INFO, true, Settings.ACTION_BLUETOOTH_SETTINGS))
                if (on) baselineCheck("bonded_addr", names.keys, Severity.MEDIUM, "Neues Bluetooth-Gerät gekoppelt") { "${names[it]} ($it)" }
            }
        }
        safe {
            val nfc = NfcAdapter.getDefaultAdapter(ctx)
            if (nfc != null) add(
                if (nfc.isEnabled) AuditItem("NFC an", "Für kontaktloses Bezahlen nötig. Sonst ausschalten.", Severity.INFO, true, Settings.ACTION_NFC_SETTINGS)
                else AuditItem("NFC aus", "Keine NFC-Angriffsfläche.", Severity.INFO, true, Settings.ACTION_NFC_SETTINGS)
            )
        }

        // ---- network trust
        safe {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val user = ks.aliases().toList().filter { it.startsWith("user:") }
            val names = user.mapNotNull { alias ->
                (ks.getCertificate(alias) as? java.security.cert.X509Certificate)?.subjectX500Principal?.name
            }
            if (user.isNotEmpty()) add(
                AuditItem(
                    "${user.size} Nutzer-Zertifikat(e) installiert",
                    names.joinToString("\n") + "\nEigene CA-Zertifikate erlauben das Mitlesen verschlüsselter Verbindungen. " +
                        "Nur behalten, wenn von dir/deinem Arbeitgeber.", Severity.MEDIUM, false, Settings.ACTION_SECURITY_SETTINGS
                )
            ) else add(AuditItem("Keine Nutzer-CA-Zertifikate", "Nur System-Zertifikate vertraut.", Severity.INFO, true))
            baselineCheck("ca", user.toSet(), Severity.HIGH, "Neues CA-Zertifikat installiert")
        }
        safe {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            val lp = cm.getLinkProperties(cm.activeNetwork)
            val proxy = lp?.httpProxy?.takeIf { !it.host.isNullOrEmpty() }
            if (proxy != null) add(AuditItem("Proxy aktiv: ${proxy.host}:${proxy.port}", "Datenverkehr läuft über einen Proxy – Man-in-the-Middle möglich.", Severity.HIGH, false, Settings.ACTION_WIRELESS_SETTINGS))
            add(
                if (vpn) AuditItem("VPN aktiv", "Gut in fremden Netzen – sofern du die VPN-App selbst eingerichtet hast.", Severity.INFO, true, Settings.ACTION_VPN_SETTINGS)
                else AuditItem("Kein VPN aktiv", "In offenen/fremden WLANs empfiehlt sich ein VPN.", Severity.INFO, true, Settings.ACTION_VPN_SETTINGS)
            )
            if (Build.VERSION.SDK_INT >= 28) {
                val pd = lp?.isPrivateDnsActive == true
                add(
                    if (pd) AuditItem("Privates DNS aktiv", lp?.privateDnsServerName ?: "automatisch", Severity.INFO, true)
                    else AuditItem("Privates DNS aus", "DNS-Anfragen sind unverschlüsselt. Einstellungen › Netzwerk › Privates DNS (z. B. dns.quad9.net).", Severity.LOW, false, Settings.ACTION_WIRELESS_SETTINGS)
                )
            }
        }

        // ---- privileged access (what stalkerware needs)
        val pm = ctx.packageManager
        fun label(pkg: String) = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        fun isSystem(pkg: String) = try {
            pm.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: Exception) {
            false
        }

        safe {
            val acc = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
            val foreign = acc.filterNot { isSystem(it) }
            add(
                if (foreign.isEmpty()) AuditItem("Bedienungshilfen", if (acc.isEmpty()) "Keine Dienste aktiv." else "Nur System-Dienste aktiv.", Severity.INFO, true, Settings.ACTION_ACCESSIBILITY_SETTINGS)
                else AuditItem("Apps mit Bedienungshilfe-Zugriff", foreign.joinToString("\n") { "${label(it)} ($it)" } +
                    "\nDiese Apps können den Bildschirm lesen und Eingaben ausführen – der häufigste Weg für Stalkerware.", Severity.MEDIUM, false, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
            baselineCheck("acc", acc, Severity.HIGH, "Neue App mit Bedienungshilfe-Zugriff", ::label)
        }
        safe {
            val nl = (Settings.Secure.getString(cr, "enabled_notification_listeners") ?: "")
                .split(':').filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
            val foreign = nl.filterNot { isSystem(it) }
            add(
                AuditItem(
                    "Benachrichtigungszugriff: ${nl.size}",
                    nl.joinToString("\n") { "${label(it)} ($it)" }.ifEmpty { "Keine App liest Benachrichtigungen." } +
                        if (foreign.isNotEmpty()) "\nDiese Apps sehen alle Nachrichten inkl. 2FA-Codes." else "",
                    if (foreign.isNotEmpty()) Severity.LOW else Severity.INFO, foreign.isEmpty(),
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                )
            )
            baselineCheck("notif", nl, Severity.MEDIUM, "Neue App mit Benachrichtigungszugriff", ::label)
        }
        safe {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            val admins = dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
            val foreign = admins.filterNot { isSystem(it) }
            add(
                if (foreign.isEmpty()) AuditItem("Geräteadministratoren", if (admins.isEmpty()) "Keine." else "Nur System-Apps.", Severity.INFO, true, Settings.ACTION_SECURITY_SETTINGS)
                else AuditItem("Apps mit Geräteadmin-Rechten", foreign.joinToString("\n") { "${label(it)} ($it)" } +
                    "\nAdmin-Apps können sich vor Deinstallation schützen. Prüfen, ob du das erlaubt hast.", Severity.MEDIUM, false, Settings.ACTION_SECURITY_SETTINGS)
            )
            baselineCheck("admins", admins, Severity.HIGH, "Neue Geräteadministrator-App", ::label)
        }

        // ---- installed apps: sideloaded + hidden + spy permissions
        safe {
            val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33)
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val suspicious = ArrayList<String>()
            val camApps = ArrayList<String>()
            val micApps = ArrayList<String>()
            val sideloaded = ArrayList<String>()
            for (p in pkgs) {
                val ai = p.applicationInfo ?: continue
                if (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue
                if (p.packageName == ctx.packageName) continue
                val installer = try {
                    if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p.packageName).installingPackageName
                    else @Suppress("DEPRECATION") pm.getInstallerPackageName(p.packageName)
                } catch (e: Exception) {
                    null
                }
                val fromStore = installer != null && installer in stores
                if (!fromStore) sideloaded += label(p.packageName)
                val req = p.requestedPermissions ?: continue
                val flags = p.requestedPermissionsFlags ?: continue
                val granted = req.indices.filter { flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.map { req[it] }.toSet()
                if (Manifest.permission.CAMERA in granted) camApps += label(p.packageName)
                if (Manifest.permission.RECORD_AUDIO in granted) micApps += label(p.packageName)
                val spy = spyPerms.count { it in granted }
                val hidden = pm.getLaunchIntentForPackage(p.packageName) == null
                if (!fromStore && ((hidden && spy >= 3) || spy >= 5)) {
                    suspicious += "${label(p.packageName)} (${p.packageName}) – $spy Überwachungsrechte" +
                        (if (hidden) ", kein App-Symbol" else "") + ", Quelle: ${installer ?: "unbekannt"}"
                }
            }
            add(
                if (suspicious.isEmpty()) AuditItem("Keine auffälligen Apps", "${pkgs.size} Pakete geprüft. Keine versteckte App außerhalb eines Stores mit vielen Überwachungsrechten.", Severity.INFO, true)
                else AuditItem("Auffällige Apps (${suspicious.size})", suspicious.joinToString("\n") +
                    "\nMerkmale typischer Stalkerware. Prüfen und ggf. deinstallieren.", Severity.HIGH, false, Settings.ACTION_APPLICATION_SETTINGS)
            )
            add(AuditItem("Kamera erlaubt: ${camApps.size} Apps", camApps.sorted().joinToString(", ").ifEmpty { "keine (außer System)" } +
                "\nJede dieser Apps darf fotografieren/filmen, solange sie geöffnet ist. Unnötige Rechte entziehen.",
                Severity.INFO, true, Settings.ACTION_PRIVACY_SETTINGS))
            add(AuditItem("Mikrofon erlaubt: ${micApps.size} Apps", micApps.sorted().joinToString(", ").ifEmpty { "keine (außer System)" } +
                "\nJede dieser Apps darf aufnehmen, solange sie geöffnet ist. Unnötige Rechte entziehen.",
                Severity.INFO, true, Settings.ACTION_PRIVACY_SETTINGS))
            if (sideloaded.isNotEmpty()) add(
                AuditItem("Apps außerhalb eines Stores: ${sideloaded.size}", sideloaded.sorted().joinToString(", "), Severity.INFO, true, Settings.ACTION_APPLICATION_SETTINGS)
            )
        }

        return out.sortedByDescending { it.severity.ordinal * 2 + if (it.ok) 0 else 1 }
    }

    /** Compares a set with its stored baseline and raises an alert for new entries. */
    private fun baselineCheck(key: String, now: Set<String>, sev: Severity, title: String, label: (String) -> String = { it }) {
        val base = Hub.baseline(key)
        if (base != null) {
            val added = now - base
            if (added.isNotEmpty()) {
                Hub.raise(
                    Alert(
                        "base-$key-${added.sorted().joinToString()}", System.currentTimeMillis(), sev, Source.SYSTEM, title,
                        added.joinToString { label(it) } + " – wenn du das nicht selbst warst, sofort prüfen."
                    )
                )
            }
        }
        Hub.setBaseline(key, now)
    }
}
