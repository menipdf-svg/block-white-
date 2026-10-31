@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.l, top = Spacing.s, end = Spacing.l, bottom = Spacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        item {
            Row(
                Modifier.padding(top = Spacing.m, bottom = Spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(pkg, 56.dp)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.headlineSmall)
                    InfoText(pkg)
                }
            }
        }

        // ----- מצב הגישה: בחירה אחת מתוך ארבע, עם הסבר לכל אחת -----
        inset(title = "גישה לאינטרנט") {
            Mode.entries.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                ChoiceRow(
                    title = m.title,
                    desc = modeDescription(m),
                    selected = rule.mode == m,
                    onClick = { Rules.setMode(pkg, m) }
                )
            }
        }

        // ----- הגנה מהסרה -----
        item { ProtectCard(pkg) }

        // ----- Spotify: בלי תמונות -----
        if (pkg == SPOTIFY_PACKAGE) {
            item {
                val allOn = SPOTIFY_IMAGE_DOMAINS.all { it in rule.blocked }
                Column {
                    SectionTitle("Spotify בלי תמונות")
                    InsetGroup {
                        Column(
                            Modifier.padding(Spacing.l),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s)
                        ) {
                            InfoText("חוסם ל-Spotify את שרתי התמונות (עטיפות, תמונות אמנים, וידאו קנבס). המוזיקה ממשיכה לעבוד.")
                            if (allOn) {
                                StatusMessage("פעיל", Tone.ALLOWED)
                            } else {
                                Button(onClick = { Rules.addAppBlocked(pkg, SPOTIFY_IMAGE_DOMAINS) }) {
                                    Text("חסום תמונות של Spotify")
                                }
                            }
                        }
                    }
                }
            }
        }

        // ----- הדומיינים שהאפליקציה ביקשה -----
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.l, bottom = Spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "דומיינים שהאפליקציה מבקשת (${requests.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (requests.isNotEmpty()) {
                    TextButton(onClick = {
                        FilterState.clearRequests(pkg)
                        requests = emptyList()
                    }) { Text("נקה") }
                }
            }
        }
        item {
            InfoText(
                when {
                    !running -> "הסינון כבוי. הפעל אותו כדי לראות כאן את הבקשות."
                    requests.isEmpty() -> "פתח את האפליקציה והשתמש בה. כל דומיין שהיא מבקשת יופיע כאן מיד."
                    rule.mode != Mode.WHITELIST -> "כדי לאשר דומיינים אחד-אחד, בחר למעלה \"רשימה לבנה\"."
                    else -> "חסום או מותר. \"אשר\" מאשר את הדומיין הזה בלבד, ו\"אשר הכל\" גם את כל תת-הדומיינים של הדומיין הראשי."
                },
                Modifier.padding(horizontal = Spacing.l)
            )
        }
        items(sorted, key = { "r_${it.target}" }) { e ->
            RequestRow(pkg, e, statusOf(e, pkg, rules), rule.mode, fmt.format(Date(e.last)))
        }

        // ----- חסומים תמיד -----
        item {
            Column {
                SectionTitle("חסומים תמיד לאפליקציה הזו (${rule.blocked.size})")
                InfoText(
                    "נחסמים בכל מצב — גם ב\"פתוח\" וגם אם הם ברשימה הכללית.",
                    Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s)
                )
                GroupCard { DomainInput { Rules.addAppBlocked(pkg, listOf(it)) } }
            }
        }
        items(rule.blocked.sorted(), key = { "b_$it" }) { d ->
            ListCard {
                Box(Modifier.padding(horizontal = Spacing.s)) {
                    DomainRow(d, "הסר") { Rules.removeAppBlocked(pkg, d) }
                }
            }
        }

        // ----- דומיינים שאושרו לאפליקציה -----
        if (rule.mode == Mode.WHITELIST) {
            item {
                Column {
                    SectionTitle("מאושרים רק לאפליקציה הזו (${rule.domains.size})")
                    InfoText(
                        "בנוסף לרשימה הכללית. אפליקציות אחרות לא יקבלו את הדומיינים האלה.",
                        Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s)
                    )
                    GroupCard { DomainInput { Rules.addAppDomain(pkg, it) } }
                }
            }
            items(rule.domains.sorted(), key = { "d_$it" }) { d ->
                ListCard {
                    Box(Modifier.padding(horizontal = Spacing.s)) {
                        DomainRow(d, "הסר") { Rules.removeAppDomain(pkg, d) }
                    }
                }
            }
        }
    }
}

/** שורת בקשה: צורה (✓ / ✕) וטקסט ("מותר" / "חסום") מעבירים את המצב גם בלי צבע */
@Composable
private fun RequestRow(pkg: String, e: RequestEntry, status: Status, mode: Mode, time: String) {
    val blocked = status is Status.Blocked
    val tone = if (blocked) Tone.BLOCKED else Tone.ALLOWED
    val stateLabel = if (blocked) "חסום" else "מותר"
    ListCard {
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (blocked) Icons.Filled.Close else Icons.Filled.Check,
                    contentDescription = null,
                    tint = tone.solid(),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.s))
                Column(Modifier.weight(1f)) {
                    Text(e.target, style = MaterialTheme.typography.bodyLarge)
                    val where = when (status) {
                        is Status.Global -> " · מאושר ברשימה הכללית"
                        is Status.App -> if (status.rule != Rules.normalize(e.target)) " · מאושר דרך ${status.rule}" else ""
                        else -> ""
                    }
                    Text(
                        "$stateLabel · ${e.count} בקשות · $time$where",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                InfoText("כתובת IP בלי שם — אי אפשר לאשר")
            }
        }
    }
}

/** הגנה מהסרה / עצירה / מחיקת נתונים לאפליקציה הזו (דורש Device Owner) */
@Composable
private fun ProtectCard(pkg: String) {
    val ctx = LocalContext.current
    val owner = remember { Protection.isOwner(ctx) }
    var on by remember(pkg) { mutableStateOf(Protection.isProtected(ctx, pkg)) }
    val guard = rememberPinGuard()
    Column {
        SectionTitle("הגנה")
        InsetGroup {
            SwitchRow(
                title = "מניעת הסרה ומחיקת נתונים",
                desc = if (owner) "אי אפשר להסיר את האפליקציה, לעצור אותה או למחוק את הנתונים שלה"
                else "דורש Device Owner (הגדרות ← הגנה מפני עקיפה)",
                checked = on,
                enabled = owner
            ) { want ->
                val apply: () -> Unit = {
                    Protection.setProtected(ctx, pkg, want)
                    on = Protection.isProtected(ctx, pkg)
                }
                if (want) apply() else guard(apply)
            }
        }
    }
}
