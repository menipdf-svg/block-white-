@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

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
import com.meni.whitelistfilter.Protection
import com.meni.whitelistfilter.install.InstallGuard
import com.meni.whitelistfilter.install.InstallSettings
import com.meni.whitelistfilter.install.PlayCategories
import com.meni.whitelistfilter.install.PlayCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InstallsScreen() {
    val ctx = LocalContext.current
    val version by InstallGuard.version.collectAsState()
    val events by InstallGuard.events.collectAsState()

    val owner = remember(version) { Protection.isOwner(ctx) }
    val enabled = remember(version) { InstallSettings.enabled(ctx) }
    val uninstall = remember(version) { InstallSettings.uninstall(ctx) }
    val blocked = remember(version) { InstallSettings.blocked(ctx) }
    val held = remember(version) { InstallSettings.held(ctx) }
    val pendingCount = remember(version) { InstallSettings.pending(ctx).size }
    val allGames = PlayCategories.ALL_GAMES in blocked
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showGames by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        if (!owner) item { OwnerNotice() }

        // ----- הפעלה -----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("חסימה לפי קטגוריות", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "כל אפליקציה חדשה מוקפאת מיד, נבדקת בגוגל פליי, ומשוחררת רק אם הקטגוריה שלה מותרת. " +
                                    "בלי אינטרנט היא נשארת מוקפאת.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = owner,
                            onCheckedChange = {
                                InstallSettings.setEnabled(ctx, it)
                                if (it) InstallGuard.sweep(ctx)
                                InstallGuard.bump()
                            }
                        )
                    }
                    if (enabled && pendingCount > 0) {
                        Text("ממתינות לבדיקה: $pendingCount", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "הבדיקה רצה בתוך שירות ה-VPN, אז כדאי להפעיל גם \"VPN תמיד פעיל\" בלשונית סטטוס. " +
                            "אפליקציות שכבר מותקנות לא נבדקות.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("הסרה אוטומטית", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (uninstall) "אפליקציה בקטגוריה חסומה מוסרת מיד"
                        else "אפליקציה בקטגוריה חסומה רק מוקפאת, ואפשר לשחרר אותה ידנית",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = uninstall, onCheckedChange = {
                    InstallSettings.setUninstall(ctx, it)
                    InstallGuard.bump()
                })
            }
        }

        // ----- אפליקציות מוקפאות -----
        if (held.isNotEmpty()) {
            item { SectionTitle("אפליקציות מוקפאות") }
            items(held.entries.toList(), key = { "held_" + it.key }) { (pkg, category) ->
                HeldRow(pkg, category)
            }
        }

        // ----- קטגוריות -----
        item { SectionTitle("קטגוריות לחסימה") }
        items(PlayCategories.special, key = { it.id }) { c -> CategoryRow(c, blocked, allGames, version) }

        item {
            CollapseHeader("קטגוריות אפליקציות", PlayCategories.apps.count { it.id in blocked }, showApps) {
                showApps = !showApps
            }
        }
        if (showApps) items(PlayCategories.apps, key = { it.id }) { c -> CategoryRow(c, blocked, allGames, version) }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("כל המשחקים", style = MaterialTheme.typography.bodyLarge)
                    Text("חוסם כל קטגוריית משחקים", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = allGames, onCheckedChange = {
                    InstallSettings.setBlocked(ctx, PlayCategories.ALL_GAMES, it)
                    InstallGuard.bump()
                })
            }
        }
        item {
            CollapseHeader(
                "קטגוריות משחקים",
                if (allGames) PlayCategories.games.size else PlayCategories.games.count { it.id in blocked },
                showGames
            ) { showGames = !showGames }
        }
        if (showGames) items(PlayCategories.games, key = { it.id }) { c -> CategoryRow(c, blocked, allGames, version) }

        // ----- יומן -----
        if (events.isNotEmpty()) {
            item { SectionTitle("פעילות אחרונה") }
            items(events, key = { "ev_" + it.pkg }) { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(e.pkg, 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.label, style = MaterialTheme.typography.bodyMedium)
                        val cat = e.category?.let { " · " + PlayCategories.title(it) }.orEmpty()
                        Text(
                            e.state.title + cat + " · " + timeText(e.time),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** כותרת שנפתחת ונסגרת, עם מספר הקטגוריות שחסומות בתוכה */
@Composable
private fun CollapseHeader(title: String, blockedCount: Int, open: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                InfoText(if (blockedCount == 0) "אין חסימות" else "$blockedCount חסומות")
            }
            Text(if (open) "▲" else "▼")
        }
    }
}

@Composable
private fun CategoryRow(category: PlayCategory, blocked: Set<String>, allGames: Boolean, version: Int) {
    val ctx = LocalContext.current
    val forcedByGames = allGames && PlayCategories.isGame(category.id)
    val checked = category.id in blocked || forcedByGames
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(category.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            enabled = !forcedByGames,
            onCheckedChange = {
                InstallSettings.setBlocked(ctx, category.id, it)
                InstallGuard.bump()
            }
        )
    }
}

@Composable
private fun HeldRow(pkg: String, category: String) {
    val ctx = LocalContext.current
    val label = remember(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg, 32.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (category.isNotEmpty()) {
                Text(PlayCategories.title(category), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { InstallGuard.allow(ctx, pkg) }) { Text("שחרר") }
        TextButton(onClick = { InstallGuard.remove(ctx, pkg) }) { Text("הסר") }
    }
}

private fun timeText(time: Long): String =
    SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(time))
