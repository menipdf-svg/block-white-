@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.AppRule
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Mode
import com.meni.whitelistfilter.Protection
import com.meni.whitelistfilter.SPOTIFY_IMAGE_DOMAINS
import com.meni.whitelistfilter.SPOTIFY_PACKAGE
import com.meni.whitelistfilter.RequestEntry
import com.meni.whitelistfilter.Rules
import com.meni.whitelistfilter.RulesData
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** מה המצב הנוכחי של יעד מסוים לפי החוקים */
private sealed class Status {
    object Blocked : Status()
    object Open : Status()
    class Global(val rule: String) : Status()
    class App(val rule: String) : Status()
}

private fun statusOf(e: RequestEntry, pkg: String, data: RulesData): Status {
    val rule = data.apps[pkg] ?: AppRule()
    if (e.isDomain && Rules.firstMatch(rule.blocked, e.target) != null) return Status.Blocked
    return when (rule.mode) {
        Mode.BLOCK -> Status.Blocked
        Mode.OPEN -> Status.Open
        Mode.BLACKLIST ->
            if (e.isDomain && Rules.firstMatch(data.globalBlack, e.target) != null) Status.Blocked else Status.Open
        Mode.WHITELIST -> {
            if (!e.isDomain) return Status.Blocked
            Rules.firstMatch(data.global, e.target)?.let { return Status.Global(it) }
            Rules.firstMatch(rule.domains, e.target)?.let { return Status.App(it) }
            Status.Blocked
        }
    }
}

@Composable
fun AppDetailScreen(pkg: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val label = remember(pkg) { Rules.labelForPackage(pkg) }
    val rules by Rules.data.collectAsState()
    val running by FilterState.running.collectAsState()
    val rule = rules.apps[pkg] ?: AppRule()

    // רשימת הבקשות מתעדכנת כל שנייה בזמן שהמסך פתוח
    var requests by remember(pkg) { mutableStateOf(FilterState.requestsFor(pkg)) }
    LaunchedEffect(pkg) {
        while (true) {
            requests = FilterState.requestsFor(pkg)
            delay(1000)
        }
    }
    // חסומים קודם, ואז לפי א-ב — כדי שהרשימה לא תקפוץ כל שנייה
    val sorted = requests.sortedWith(
        compareBy<RequestEntry>({ statusOf(it, pkg, rules) !is Status.Blocked }, { it.target })
    )
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

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

        // ----- מצב הגישה -----
        item { SectionTitle("גישה לאינטרנט") }
        item {
            Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

        // ----- הגנה מהסרה -----
        item { ProtectCard(pkg) }

        // ----- Spotify: בלי תמונות -----
        if (pkg == SPOTIFY_PACKAGE) {
            item {
                val allOn = SPOTIFY_IMAGE_DOMAINS.all { it in rule.blocked }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Spotify בלי תמונות", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "חוסם ל-Spotify את שרתי התמונות (עטיפות, תמונות אמנים, וידאו קנבס). המוזיקה ממשיכה לעבוד.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (allOn) {
                            Text("✅ פעיל", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Button(onClick = { Rules.addAppBlocked(pkg, SPOTIFY_IMAGE_DOMAINS) }) {
                                Text("חסום תמונות של Spotify")
                            }
                        }
                    }
                }
            }
        }

        // ----- הדומיינים שהאפליקציה ביקשה -----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("דומיינים שהאפליקציה מבקשת (${requests.size})") }
                if (requests.isNotEmpty()) {
                    TextButton(onClick = {
                        FilterState.clearRequests(pkg)
                        requests = emptyList()
                    }) { Text("נקה") }
                }
            }
        }
        item {
            Text(
                when {
                    !running -> "הסינון כבוי. הפעל אותו כדי לראות כאן את הבקשות."
                    requests.isEmpty() -> "פתח את האפליקציה והשתמש בה. כל דומיין שהיא מבקשת יופיע כאן מיד."
                    rule.mode != Mode.WHITELIST -> "כדי לאשר דומיינים אחד-אחד, בחר למעלה \"רשימה לבנה\"."
                    else -> "⛔ חסום  ✅ מותר. \"אשר\" מאשר את הדומיין הזה בלבד, ו\"אשר הכל\" גם את כל תת-הדומיינים של הדומיין הראשי."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(sorted, key = { "r_${it.target}" }) { e ->
            RequestRow(pkg, e, statusOf(e, pkg, rules), rule.mode, fmt.format(Date(e.last)))
        }

        // ----- חסומים תמיד -----
        item { SectionTitle("חסומים תמיד לאפליקציה הזו (${rule.blocked.size})") }
        item {
            Text(
                "נחסמים בכל מצב — גם ב\"פתוח\" וגם אם הם ברשימה הכללית.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item { DomainInput { Rules.addAppBlocked(pkg, listOf(it)) } }
        items(rule.blocked.sorted(), key = { "b_$it" }) { d ->
            DomainRow(d, "הסר") { Rules.removeAppBlocked(pkg, d) }
        }

        // ----- דומיינים שאושרו לאפליקציה -----
        if (rule.mode == Mode.WHITELIST) {
            item { SectionTitle("מאושרים רק לאפליקציה הזו (${rule.domains.size})") }
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
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RequestRow(pkg: String, e: RequestEntry, status: Status, mode: Mode, time: String) {
    val blocked = status is Status.Blocked
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (blocked) "⛔" else "✅")
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.target, style = MaterialTheme.typography.bodyLarge)
                    val where = when (status) {
                        is Status.Global -> " · מאושר ברשימה הכללית"
                        is Status.App -> if (status.rule != Rules.normalize(e.target)) " · מאושר דרך ${status.rule}" else ""
                        else -> ""
                    }
                    Text(
                        "${e.count} בקשות · $time$where",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (mode == Mode.WHITELIST && e.isDomain) {
                Row {
                    when (status) {
                        is Status.Blocked -> {
                            val exact = Rules.normalize(e.target)
                            val base = Rules.baseDomain(e.target)
                            TextButton(onClick = { Rules.addAppDomain(pkg, exact) }) { Text("אשר") }
                            if (base != exact) {
                                TextButton(onClick = { Rules.addAppDomain(pkg, base) }) { Text("אשר הכל ($base)") }
                            }
                        }
                        is Status.App -> TextButton(onClick = { Rules.removeAppDomain(pkg, status.rule) }) {
                            Text(if (status.rule == Rules.normalize(e.target)) "בטל אישור" else "בטל אישור (${status.rule})")
                        }
                        else -> {}
                    }
                }
            }
            if (e.isDomain && !blocked) {
                TextButton(onClick = { Rules.addAppBlocked(pkg, listOf(Rules.normalize(e.target))) }) {
                    Text("חסום תמיד")
                }
            }
            if (!e.isDomain && blocked) {
                Text("כתובת IP בלי שם — אי אפשר לאשר", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** הגנה מהסרה / עצירה / מחיקת נתונים לאפליקציה הזו (דורש Device Owner) */
@Composable
private fun ProtectCard(pkg: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val owner = remember { Protection.isOwner(ctx) }
    var on by remember(pkg) { mutableStateOf(Protection.isProtected(ctx, pkg)) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("מניעת הסרה ומחיקת נתונים", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (owner) "אי אפשר להסיר את האפליקציה, לעצור אותה או למחוק את הנתונים שלה"
                    else "דורש הרשאת Device Owner (במסך הסטטוס)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = on,
                enabled = owner,
                onCheckedChange = {
                    Protection.setProtected(ctx, pkg, it)
                    on = Protection.isProtected(ctx, pkg)
                }
            )
        }
    }
}
