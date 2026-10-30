@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.ChromeLock
import com.meni.whitelistfilter.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DnsPreset(val title: String, val host: String)

private val dnsPresets = listOf(
    DnsPreset("Cloudflare למשפחה (חוסם תוכן למבוגרים)", "family.cloudflare-dns.com"),
    DnsPreset("AdGuard למשפחה", "family.adguard-dns.com"),
    DnsPreset("CleanBrowsing למשפחה", "family-filter-dns.cleanbrowsing.org"),
    DnsPreset("Google", "dns.google")
)

private val hostRegex =
    Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$")

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val owner = remember(refresh) { Protection.isOwner(ctx) }
    val chromeInstalled = remember { Protection.isChromeInstalled(ctx) }
    val forcedHost = remember(refresh) { Protection.forcedDnsHost(ctx) }

    var host by rememberSaveable { mutableStateOf(Protection.forcedDnsHost(ctx) ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("→ חזרה") }
                Spacer(Modifier.width(8.dp))
                Text("הגדרות", style = MaterialTheme.typography.headlineSmall)
            }
        }

        if (!owner) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "ההגדרות האלה עובדות רק כשהאפליקציה מוגדרת כ-Device Owner. אפשר להגדיר בלשונית \"סטטוס\".",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // ----- כרום -----
        item { SectionTitle("כרום") }
        if (!chromeInstalled) {
            item {
                Text(
                    "כרום לא מותקן כרגע. ההגדרות יישמרו ויחולו ברגע שהוא יותקן.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        items(ChromeLock.entries.toList(), key = { it.name }) { lock ->
            var on by remember(refresh) { mutableStateOf(Protection.isChromeOn(ctx, lock)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lock.title, style = MaterialTheme.typography.bodyLarge)
                    Text(lock.desc, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = on,
                    enabled = owner,
                    onCheckedChange = {
                        Protection.setChrome(ctx, lock, it)
                        on = Protection.isChromeOn(ctx, lock)
                        refresh++
                    }
                )
            }
        }
        item {
            Text(
                "ההגדרות מתעדכנות בכרום תוך כמה שניות. אם כרום פתוח, כדאי לסגור ולפתוח אותו מחדש.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ----- DNS -----
        item { SectionTitle("כפיית DNS") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (forcedHost != null) "✅ DNS נכפה: $forcedHost" else "אין כפיית DNS",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "הכפייה היא ב-DNS פרטי של אנדרואיד, לכן צריך שם שרת (למשל dns.google) ולא כתובת IP. " +
                            "אם השרת לא נגיש, לא יהיה אינטרנט בכלל.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    dnsPresets.forEach { preset ->
                        TextButton(onClick = { host = preset.host }) { Text(preset.title) }
                    }
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim().lowercase() },
                        singleLine = true,
                        label = { Text("שם שרת DNS פרטי") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = owner && !busy,
                            onClick = {
                                if (!hostRegex.matches(host)) {
                                    message = "שם השרת לא תקין"
                                } else {
                                    busy = true
                                    message = "בודק את השרת..."
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { Protection.setForcedDns(ctx, host) }
                                        message =
                                            if (ok) "ה-DNS נכפה"
                                            else "ההגדרה נכשלה. השרת לא נגיש או שאינו תומך ב-DNS פרטי"
                                        busy = false
                                        refresh++
                                    }
                                }
                            }
                        ) { Text("כפה DNS") }
                        if (forcedHost != null) {
                            OutlinedButton(
                                enabled = owner && !busy,
                                onClick = {
                                    Protection.setForcedDns(ctx, null)
                                    message = "הכפייה בוטלה"
                                    refresh++
                                }
                            ) { Text("בטל כפייה") }
                        }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
