@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.meni.whitelistfilter.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.AppRule
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
    var filters by rememberSaveable(stateSaver = AppFilters.Saver) { mutableStateOf(AppFilters()) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val rules by Rules.data.collectAsState()

    val list = apps
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        val visible = remember(list, rules, filters) { applyFilters(list, rules, filters) }
        val hasFilters = filters.activeCount > 0

        // תגיות הסינון הפעילות (בלי החיפוש, שנראה בשדה עצמו)
        val chips = buildList {
            filters.modes.forEach { m ->
                add(ActiveChip("מצב: ${m.title}") { filters = filters.copy(modes = filters.modes - m) })
            }
            if (filters.includeSystem) {
                add(ActiveChip("כולל אפליקציות מערכת") { filters = filters.copy(includeSystem = false) })
            }
            if (filters.sort != AppSort.NAME) {
                add(ActiveChip("מיון: ${filters.sort.title}") { filters = filters.copy(sort = AppSort.NAME) })
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchField(
                    value = filters.query,
                    onValueChange = { filters = filters.copy(query = it) },
                    placeholder = "חיפוש אפליקציה",
                    modifier = Modifier.weight(1f)
                )
                FilterButton(chips.size) { showSheet = true }
            }
            ActiveFilterChips(chips, Modifier.padding(top = 8.dp))
            ResultsHeader(
                shown = visible.size,
                total = list.size,
                unit = "אפליקציות",
                hasFilters = hasFilters,
                onReset = { filters = AppFilters() }
            )

            if (visible.isEmpty()) {
                EmptyState(
                    icon = "🔍",
                    title = "לא נמצאו אפליקציות",
                    message = "נסה לשנות את החיפוש או את הסינון.",
                    actionLabel = if (hasFilters) "אפס סינון" else null,
                    onAction = { filters = AppFilters() }
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.pkg }) { app ->
                        val rule = rules.apps[app.pkg] ?: AppRule()
                        AppCard(onClick = { onOpen(app.pkg) }) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(app.pkg, 44.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        app.pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                ModeBadge(rule.mode, rule.domains.size)
                            }
                        }
                    }
                }
            }
        }

        if (showSheet) {
            FilterSheet(
                title = "סינון אפליקציות",
                resultCount = visible.size,
                onReset = { filters = AppFilters(query = filters.query) },
                onDismiss = { showSheet = false }
            ) {
                FilterGroup("מצב גישה לאינטרנט") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Mode.entries.forEach { m ->
                            FilterChip(
                                selected = m in filters.modes,
                                onClick = {
                                    filters = filters.copy(
                                        modes = if (m in filters.modes) filters.modes - m else filters.modes + m
                                    )
                                },
                                label = { Text(m.title) }
                            )
                        }
                    }
                }
                FilterGroup("מיון") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSort.entries.forEach { s ->
                            FilterChip(
                                selected = filters.sort == s,
                                onClick = { filters = filters.copy(sort = s) },
                                label = { Text(s.title) }
                            )
                        }
                    }
                }
                SwitchRow(
                    "אפליקציות מערכת",
                    "הצג גם אפליקציות מערכת שיש להן גישה לאינטרנט",
                    filters.includeSystem
                ) { filters = filters.copy(includeSystem = it) }
            }
        }
    }
}
