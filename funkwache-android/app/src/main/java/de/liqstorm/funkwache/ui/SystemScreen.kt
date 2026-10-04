package de.liqstorm.funkwache.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.liqstorm.funkwache.audit.SystemAudit
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.model.AuditItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SystemScreen(act: MainActivity) {
    val ctx = LocalContext.current
    val audit by Hub.audit.collectAsState()
    val trusted by Hub.trusted.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val problems = audit.count { !it.ok }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sicherheits-Check", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        if (audit.isEmpty()) "Noch nicht geprüft" else if (problems == 0) "Keine Probleme gefunden" else "$problems Punkt(e) prüfen",
                        color = if (problems == 0) Ok else sevColor(audit.filter { !it.ok }.maxBy { it.severity.ordinal }.severity),
                        fontSize = 13.sp
                    )
                }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        Hub.audit.value = withContext(Dispatchers.Default) { SystemAudit.run(ctx.applicationContext) }
                        busy = false
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Jetzt prüfen")
                }
            }
            Hint("Geprüft wird, was Stalkerware und Angreifer brauchen: Bedienungshilfen, Benachrichtigungszugriff, " +
                "Geräteadmin, versteckte Apps mit Überwachungsrechten, Nutzer-Zertifikate, Proxy, Debugging, Root, " +
                "Patchstand. Änderungen an diesen Punkten lösen automatisch eine Warnung aus.")
        }
        item { PrivacyPanel(act) }
        items(audit) { AuditCard(it, act) }

        item { Section("Vertraute Geräte & Netze (${trusted.size})") }
        if (trusted.isEmpty()) item { Hint("Markiere eigene Kopfhörer, Uhren, Autos oder Tracker als vertraut, damit sie keinen Verfolgungsalarm auslösen.") }
        items(trusted.sorted(), key = { "t-$it" }) { id ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(id, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    TextButton(onClick = { Hub.setTrusted(id, false) }) { Text("Entfernen") }
                }
            }
        }
        item {
            Section("Datenschutz")
            Hint("Funkwache hat keine Internet-Berechtigung. Alle Scans, Warnungen und Berichte bleiben auf dem Gerät.")
        }
    }
}

@Composable
private fun AuditCard(i: AuditItem, act: MainActivity) {
    val color = if (i.ok) Ok else sevColor(i.severity)
    Panel(border = if (i.ok) null else color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (i.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(i.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!i.ok) SevChip(i.severity)
        }
        Text(i.detail, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp), lineHeight = 18.sp)
        i.action?.let { a -> TextButton(onClick = { act.openSettings(a) }) { Text("Einstellungen öffnen") } }
    }
}
