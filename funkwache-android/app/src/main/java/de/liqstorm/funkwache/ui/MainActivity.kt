package de.liqstorm.funkwache.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.core.Perms
import de.liqstorm.funkwache.core.Prefs
import de.liqstorm.funkwache.core.Report
import de.liqstorm.funkwache.core.ScanEngine
import de.liqstorm.funkwache.core.hex
import de.liqstorm.funkwache.model.NfcTagInfo
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.service.GuardService

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Hub.envTick.value++
        ScanEngine.restart(this)
        maybeStartGuard()
    }

    private var nfcReader = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FunkTheme { AppRoot(this) } }
        if (savedInstanceState == null && Perms.missing(this).isNotEmpty()) requestPerms()
    }

    override fun onStart() {
        super.onStart()
        ScanEngine.acquire(this, "ui")
        maybeStartGuard()
    }

    override fun onStop() {
        ScanEngine.release(this, "ui")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Hub.envTick.value++
        if (nfcReader) enableNfc()
    }

    override fun onPause() {
        disableNfc()
        super.onPause()
    }

    private fun maybeStartGuard() {
        if (Prefs.guardWanted && !Hub.guardRunning.value && Perms.location(this)) {
            try {
                GuardService.start(this)
            } catch (_: Exception) {
            }
        }
    }

    fun requestPerms() = permLauncher.launch(Perms.wanted())

    fun enableBluetooth() {
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
    }

    fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "Einstellung nicht verfügbar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun share() {
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Funkwache – Umgebungsbericht")
            .putExtra(Intent.EXTRA_TEXT, Report.build())
        startActivity(Intent.createChooser(i, "Bericht teilen"))
    }

    // ----------------------------------------------------------------- NFC

    fun nfcAvailable() = NfcAdapter.getDefaultAdapter(this) != null
    fun nfcEnabled() = NfcAdapter.getDefaultAdapter(this)?.isEnabled == true

    fun setNfcReader(on: Boolean) {
        nfcReader = on
        if (on) enableNfc() else disableNfc()
    }

    private fun enableNfc() {
        val a = NfcAdapter.getDefaultAdapter(this) ?: return
        try {
            a.enableReaderMode(
                this,
                { tag ->
                    val info = NfcTagInfo(
                        System.currentTimeMillis(), tag.id.hex(":"),
                        tag.techList.map { it.substringAfterLast('.') }
                    )
                    Hub.nfcTags.value = (listOf(info) + Hub.nfcTags.value).take(20)
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE,
                null
            )
        } catch (_: Exception) {
        }
    }

    private fun disableNfc() {
        try {
            NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        } catch (_: Exception) {
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Schutz", Icons.Filled.Lock),
    Tab("Bluetooth", Icons.Filled.Search),
    Tab("WLAN", Icons.Filled.Home),
    Tab("Funk", Icons.Filled.Phone),
    Tab("System", Icons.Filled.Build),
)

data class FinderTarget(val wifi: Boolean, val id: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(act: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var finder by remember { mutableStateOf<FinderTarget?>(null) }
    val alerts by Hub.alerts.collectAsState()
    val serious = alerts.count { it.severity.ordinal >= Severity.MEDIUM.ordinal && System.currentTimeMillis() - it.time < 86_400_000L }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Funkwache", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { act.share() }) { Icon(Icons.Filled.Share, contentDescription = "Bericht teilen") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            if (i == 0 && serious > 0) {
                                BadgedBox(badge = { Badge { Text("$serious") } }) { Icon(t.icon, contentDescription = null) }
                            } else Icon(t.icon, contentDescription = null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> DashboardScreen(act) { tab = it }
                1 -> BleScreen(act) { finder = it }
                2 -> WifiScreen { finder = it }
                3 -> RadioScreen(act)
                else -> SystemScreen(act)
            }
        }
    }
    finder?.let { FinderDialog(it) { finder = null } }
}
