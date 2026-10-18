@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DomainsScreen() {
    val rules by Rules.data.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { SectionTitle("רשימה כללית (${rules.global.size})") }
        item {
            Text(
                "דומיינים שמותרים לכל האפליקציות שבמצב רשימה לבנה. תת-דומיינים נכללים אוטומטית.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { DomainInput { Rules.addGlobal(it) } }
        items(rules.global.sorted(), key = { it }) { d ->
            DomainRow(d, "הסר") { Rules.removeGlobal(d) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun LogScreen() {
    val log by FilterState.log.collectAsState()
    val rules by Rules.data.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("נחסמו לאחרונה", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { FilterState.clearLog() }) { Text("נקה") }
            }
        }
        if (log.isEmpty()) item { Text("אין עדיין חסימות", style = MaterialTheme.typography.bodySmall) }
        items(log, key = { "${it.pkg}|${it.target}" }) { e ->
            val approvedGlobal = e.isDomain && e.target in rules.global
            val approvedApp = e.isDomain && e.pkg != null && e.target in (rules.apps[e.pkg]?.domains ?: emptySet())
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(e.pkg, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(e.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(e.target, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                    when {
                        approvedGlobal || approvedApp -> Text("✅ אושר", style = MaterialTheme.typography.bodySmall)
                        e.isDomain -> Row {
                            TextButton(onClick = { Rules.addGlobal(e.target) }) { Text("אשר לכולם") }
                            if (e.pkg != null) {
                                TextButton(onClick = { Rules.addAppDomain(e.pkg, e.target) }) { Text("אשר רק לאפליקציה") }
                            }
                        }
                        else -> Text("כתובת IP בלי דומיין ידוע", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
