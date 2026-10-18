package com.meni.whitelistfilter

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Whitelist.init(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) {
                        AppScreen(onStart = ::requestStart, onStop = ::stopVpn)
                    }
                }
            }
        }
    }

    private fun requestStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_START))
    }

    private fun stopVpn() {
        startService(Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_STOP))
    }
}

@Composable
fun AppScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val running by FilterState.running.collectAsState()
    val domains by Whitelist.domains.collectAsState()
    val blocked by FilterState.blocked.collectAsState()
    var input by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("מסנן רשימה לבנה", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (running) "✅ הסינון פעיל" else "⛔ הסינון כבוי",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = if (running) onStop else onStart) {
                        Text(if (running) "עצור" else "הפעל")
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("דומיין להוספה, למשל wikipedia.org") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    Whitelist.add(input)
                    input = ""
                }) { Text("הוסף") }
            }
        }
        item {
            Text("אתרים מאושרים (${domains.size})", style = MaterialTheme.typography.titleMedium)
        }
        items(domains.sorted(), key = { "a_$it" }) { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d, Modifier.weight(1f))
                TextButton(onClick = { Whitelist.remove(d) }) { Text("הסר") }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("נחסמו לאחרונה", style = MaterialTheme.typography.titleMedium)
            if (blocked.isEmpty()) Text("אין עדיין", style = MaterialTheme.typography.bodySmall)
        }
        items(blocked, key = { "b_$it" }) { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Whitelist.add(d) }) { Text("אשר") }
            }
        }
    }
}
