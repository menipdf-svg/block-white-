@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.AppRule
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Mode
import com.meni.whitelistfilter.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(val pkg: String, val label: String, val system: Boolean)

@Suppress("DEPRECATION")
fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).mapNotNull { pi ->
        val ai = pi.applicationInfo ?: return@mapNotNull null
        if (pi.packageName == ctx.packageName) return@mapNotNull null
        if (pi.requestedPermissions?.contains(Manifest.permission.INTERNET) != true) return@mapNotNull null
        AppItem(
            pi.packageName,
            ai.loadLabel(pm).toString(),
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        )
    }.sortedBy { it.label.lowercase() }
}

@Composable
fun AppsScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val rules by Rules.data.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("חיפוש אפליקציה") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
            Text("הצג אפליקציות מערכת")
        }
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val q = query.trim().lowercase()
            val filtered = list.filter {
                (showSystem || !it.system || rules.apps.containsKey(it.pkg)) &&
                    (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q))
            }
            LazyColumn {
                items(filtered, key = { it.pkg }) { app ->
                    val rule = rules.apps[app.pkg] ?: AppRule()
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(app.pkg) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.pkg)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        }
                        ModeBadge(rule.mode, rule.domains.size)
                    }
                }
            }
        }
    }
}

@Composable
fun AppDetailScreen(pkg: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val label = remember(pkg) { Rules.labelForPackage(pkg) }
    val rules by Rules.data.collectAsState()
    val rule = rules.apps[pkg] ?: AppRule()
    val log by FilterState.log.collectAsState()
    val blocked = log.filter { it.pkg == pkg && it.isDomain && it.target !in rule.domains }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("→ חזרה") } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, style = MaterialTheme.typography.headlineSmall)
                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionTitle("גישה לאינטרנט") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(
                        selected = rule.mode == m,
                        onClick = { Rules.setMode(pkg, m) },
                        label = { Text(m.title) }
                    )
                }
            }
        }
        item { Text(modeDescription(rule.mode), style = MaterialTheme.typography.bodyMedium) }

        if (rule.mode == Mode.WHITELIST) {
            item { SectionTitle("דומיינים רק לאפליקציה הזו") }
            item {
                Text(
                    "בנוסף לרשימה הכללית. אפליקציות אחרות לא יקבלו את הדומיינים האלה.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { DomainInput { Rules.addAppDomain(pkg, it) } }
            items(rule.domains.sorted(), key = { "d_$it" }) { d ->
                DomainRow(d, "הסר") { Rules.removeAppDomain(pkg, d) }
            }
            if (blocked.isNotEmpty()) {
                item { SectionTitle("נחסמו לאפליקציה הזו") }
                items(blocked, key = { "b_${it.target}" }) { e ->
                    DomainRow(e.target, "אשר") { Rules.addAppDomain(pkg, e.target) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
