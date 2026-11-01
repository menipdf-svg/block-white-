@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.meni.whitelistfilter.ui

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
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
import com.meni.whitelistfilter.Protection
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
    var suspTick by remember { mutableIntStateOf(0) }
    val owner = remember { Protection.isOwner(ctx) }

    val list = apps
    if (list == null) {
        // מצב טעינה: מיד מוצג משהו, עם הסבר מה קורה
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(Spacing.m))
            InfoText("טוען את רשימת האפליקציות…")
        }
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
                Modifier.padding(horizontal = Spacing.l).padding(top = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                SearchField(
                    value = filters.query,
                    onValueChange = { filters = filters.copy(query = it) },
                    placeholder = "חיפוש אפליקציה",
                    modifier = Modifier.weight(1f)
                )
                FilterButton(chips.size) { showSheet = true }
            }
            ActiveFilterChips(chips, Modifier.padding(top = Spacing.s))
            ResultsHeader(
                shown = visible.size,
                total = list.size,
                unit = "אפליקציות",
                hasFilters = hasFilters,
                onReset = { filters = AppFilters() },
                modifier = Modifier.padding(top = Spacing.s)
            )

            if (visible.isEmpty()) {
                EmptyState(
                    icon = AppIcons.Apps,
                    title = "לא נמצאו אפליקציות",
                    message = "נסה לשנות את החיפוש או את הסינון.",
                    actionLabel = if (hasFilters) "אפס סינון" else null,
                    onAction = { filters = AppFilters() }
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    items(visible, key = { it.pkg }) { app ->
                        val rule = rules.apps[app.pkg] ?: AppRule()
                        val guard = rememberPinGuard()
                        ListCard(onClick = { onOpen(app.pkg) }) {
                            Row(
                                Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(app.pkg, 40.dp)
                                Spacer(Modifier.width(Spacing.m))
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
                                Spacer(Modifier.width(Spacing.s))
                                ModeBadge(rule.mode, rule.domains.size)
                            }
                            // חסימה ישירה של האפליקציה עצמה (הקפאה), בלי קשר לאינטרנט
                            val suspended = remember(suspTick) { Protection.isAppSuspended(ctx, app.pkg) }
                            Row(
                                Modifier.padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val ok = Protection.setAppSuspended(ctx, app.pkg, true)
                                        suspTick++
                                        if (!ok) Toast.makeText(ctx, "לא ניתן לחסום את האפליקציה הזו", Toast.LENGTH_SHORT).show()
                                    },
                                    enabled = owner && !suspended,
                                    modifier = Modifier.weight(1f)
                                ) { Text("חסום אפליקציה") }
                                OutlinedButton(
                                    onClick = {
                                        guard {
                                            Protection.setAppSuspended(ctx, app.pkg, false)
                                            suspTick++
                                        }
                                    },
                                    enabled = owner && suspended,
                                    modifier = Modifier.weight(1f)
                                ) { Text("פתח אפליקציה") }
                            }
                            if (suspended) {
                                InfoText("האפליקציה חסומה ולא ניתן להפעיל אותה", Modifier.padding(horizontal = Spacing.l).padding(bottom = Spacing.s))
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        AppSort.entries.forEach { s ->
                            FilterChip(
                                selected = filters.sort == s,
                                onClick = { filters = filters.copy(sort = s) },
                                label = { Text(s.title) }
                            )
                        }
                    }
                }
                InsetGroup {
                    SwitchRow(
                        "אפליקציות מערכת",
                        "הצג גם אפליקציות מערכת שיש להן גישה לאינטרנט",
                        filters.includeSystem
                    ) { filters = filters.copy(includeSystem = it) }
                }
            }
        }
    }
}
