@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    val guard = rememberPinGuard()
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
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.l, top = Spacing.s, end = Spacing.l, bottom = Spacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        if (!owner) item { OwnerNotice() }

        // ----- הפעלה -----
        inset(
            title = "הפעלה",
            footer = "הבדיקה רצה בתוך שירות ה-VPN, אז כדאי להפעיל גם \"VPN תמיד פעיל\" בלשונית סטטוס. " +
                "אפליקציות שכבר מותקנות לא נבדקות."
        ) {
            SwitchRow(
                title = "חסימה לפי קטגוריות",
                desc = "כל אפליקציה חדשה מוקפאת מיד, נבדקת בגוגל פליי, ומשוחררת רק אם הקטגוריה שלה מותרת. " +
                    "בלי אינטרנט היא נשארת מוקפאת.",
                checked = enabled,
                enabled = owner
            ) { want ->
                val apply: () -> Unit = {
                    InstallSettings.setEnabled(ctx, want)
                    if (want) InstallGuard.sweep(ctx)
                    InstallGuard.bump()
                }
                if (want) apply() else guard(apply)
            }
            if (enabled && pendingCount > 0) {
                RowDivider()
                Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                    StatusMessage("ממתינות לבדיקה: $pendingCount", Tone.NEUTRAL)
                }
            }
        }

        inset {
            SwitchRow(
                title = "הסרה אוטומטית",
                desc = if (uninstall) "אפליקציה בקטגוריה חסומה מוסרת מיד"
                else "אפליקציה בקטגוריה חסומה רק מוקפאת, ואפשר לשחרר אותה ידנית",
                checked = uninstall
            ) { want ->
                val apply: () -> Unit = {
                    InstallSettings.setUninstall(ctx, want)
                    InstallGuard.bump()
                }
                if (want) apply() else guard(apply)
            }
        }

        // ----- אפליקציות מוקפאות -----
        if (held.isNotEmpty()) {
            inset(title = "אפליקציות מוקפאות") {
                held.entries.toList().forEachIndexed { i, entry ->
                    if (i > 0) RowDivider()
                    HeldRow(entry.key, entry.value)
                }
            }
        }

        // ----- קטגוריות -----
        inset(title = "קטגוריות לחסימה") {
            PlayCategories.special.forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                CategoryRow(c, blocked, allGames, version)
            }
        }

        item {
            CollapseHeader(
                "קטגוריות אפליקציות",
                PlayCategories.apps.count { it.id in blocked },
                showApps
            ) { showApps = !showApps }
        }
        item {
            AnimatedVisibility(visible = showApps) {
                InsetGroup {
                    PlayCategories.apps.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        CategoryRow(c, blocked, allGames, version)
                    }
                }
            }
        }

        inset {
            SwitchRow(
                title = "כל המשחקים",
                desc = "חוסם כל קטגוריית משחקים",
                checked = allGames
            ) { want ->
                val apply: () -> Unit = {
                    InstallSettings.setBlocked(ctx, PlayCategories.ALL_GAMES, want)
                    InstallGuard.bump()
                }
                if (want) apply() else guard(apply)
            }
        }
        item {
            CollapseHeader(
                "קטגוריות משחקים",
                if (allGames) PlayCategories.games.size else PlayCategories.games.count { it.id in blocked },
                showGames
            ) { showGames = !showGames }
        }
        item {
            AnimatedVisibility(visible = showGames) {
                InsetGroup {
                    PlayCategories.games.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        CategoryRow(c, blocked, allGames, version)
                    }
                }
            }
        }

        // ----- יומן -----
        if (events.isNotEmpty()) {
            inset(title = "פעילות אחרונה") {
                events.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(e.pkg, 32.dp)
                        Spacer(Modifier.width(Spacing.s))
                        Column(Modifier.weight(1f)) {
                            Text(e.label, style = MaterialTheme.typography.bodyMedium)
                            val cat = e.category?.let { " · " + PlayCategories.title(it) }.orEmpty()
                            InfoText(e.state.title + cat + " · " + timeText(e.time))
                        }
                    }
                }
            }
        }
    }
}

/** שורה שנפתחת ונסגרת: החץ מסתובב עם המצב, והמצב נקרא גם למסך מקריא */
@Composable
internal fun ExpandableRow(title: String, subtitle: String, open: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(targetValue = if (open) 180f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (open) "מורחב" else "מכווץ" }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            InfoText(subtitle)
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation)
        )
    }
}

/** כותרת קבוצה שנפתחת ונסגרת, עם מספר הקטגוריות שחסומות בתוכה */
@Composable
private fun CollapseHeader(title: String, blockedCount: Int, open: Boolean, onToggle: () -> Unit) {
    InsetGroup {
        ExpandableRow(
            title = title,
            subtitle = if (blockedCount == 0) "אין חסימות" else "$blockedCount חסומות",
            open = open,
            onToggle = onToggle
        )
    }
}

@Composable
private fun CategoryRow(category: PlayCategory, blocked: Set<String>, allGames: Boolean, version: Int) {
    val ctx = LocalContext.current
    val forcedByGames = allGames && PlayCategories.isGame(category.id)
    val guard = rememberPinGuard()
    val checked = category.id in blocked || forcedByGames
    SwitchRow(
        title = category.title,
        desc = if (forcedByGames) "נחסם לפי \"כל המשחקים\"" else null,
        checked = checked,
        enabled = !forcedByGames
    ) { want ->
        val apply: () -> Unit = {
            InstallSettings.setBlocked(ctx, category.id, want)
            InstallGuard.bump()
        }
        if (want) apply() else guard(apply)
    }
}

@Composable
private fun HeldRow(pkg: String, category: String) {
    val ctx = LocalContext.current
    val guard = rememberPinGuard()
    val label = remember(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(pkg, 32.dp)
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (category.isNotEmpty()) {
                InfoText(PlayCategories.title(category))
            }
        }
        TextButton(onClick = { guard { InstallGuard.allow(ctx, pkg) } }) { Text("שחרר") }
        TextButton(onClick = { InstallGuard.remove(ctx, pkg) }) { Text("הסר") }
    }
}

private fun timeText(time: Long): String =
    SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(time))
