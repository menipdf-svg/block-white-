@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val white = remember(rules.global, q) { rules.global.sorted().filter { q.isEmpty() || it.contains(q) } }
    val black = remember(rules.globalBlack, q) { rules.globalBlack.sorted().filter { q.isEmpty() || it.contains(q) } }
    val searching = q.isNotEmpty()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SearchField(query, { query = it }, "חיפוש דומיין", Modifier.fillMaxWidth()) }

        if (searching && white.isEmpty() && black.isEmpty()) {
            item {
                EmptyState(
                    icon = "🔍",
                    title = "לא נמצאו דומיינים",
                    message = "אין דומיין שמכיל \"$query\" באף אחת מהרשימות.",
                    modifier = Modifier.height(320.dp),
                    actionLabel = "נקה חיפוש",
                    onAction = { query = "" }
                )
            }
        } else {
            item {
                SectionTitle(
                    if (searching) "רשימה כללית (${white.size} מתוך ${rules.global.size})"
                    else "רשימה כללית (${rules.global.size})"
                )
            }
            item {
                InfoText("דומיינים שמותרים לכל האפליקציות שבמצב רשימה לבנה. תת-דומיינים נכללים אוטומטית.")
            }
            item { DomainInput { Rules.addGlobal(it) } }
            if (white.isEmpty()) item { InfoText("אין דומיינים ברשימה") }
            items(white, key = { it }) { d -> DomainCard(d) { Rules.removeGlobal(d) } }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionTitle(
                    if (searching) "רשימה שחורה כללית (${black.size} מתוך ${rules.globalBlack.size})"
                    else "רשימה שחורה כללית (${rules.globalBlack.size})"
                )
            }
            item {
                InfoText("דומיינים שנחסמים לכל האפליקציות שבמצב רשימה שחורה. תת-דומיינים נכללים אוטומטית.")
            }
            item { DomainInput { Rules.addGlobalBlack(it) } }
            if (black.isEmpty()) item { InfoText("אין דומיינים ברשימה") }
            items(black, key = { "black_$it" }) { d -> DomainCard(d) { Rules.removeGlobalBlack(d) } }
        }
    }
}

@Composable
private fun DomainCard(domain: String, onRemove: () -> Unit) {
    AppCard {
        Box(Modifier.padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)) {
            DomainRow(domain, "הסר", onRemove)
        }
    }
}

@Composable
fun LogScreen() {
    val log by FilterState.log.collectAsState()
    val rules by Rules.data.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val visible = remember(log, q) {
        if (q.isEmpty()) log
        else log.filter { it.label.lowercase().contains(q) || it.target.lowercase().contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        if (log.isEmpty()) {
            EmptyState(
                icon = "🛡️",
                title = "אין חסימות עדיין",
                message = "כשאפליקציה תנסה להגיע לדומיין חסום, הוא יופיע כאן ותוכל לאשר אותו."
            )
        } else {
            SearchField(
                query, { query = it }, "חיפוש לפי אפליקציה או דומיין",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
            )
            ResultsHeader(
                shown = visible.size,
                total = log.size,
                unit = "חסימות",
                hasFilters = q.isNotEmpty(),
                onReset = { query = "" }
            ) {
                TextButton(onClick = { FilterState.clearLog() }) { Text("נקה יומן") }
            }

            if (visible.isEmpty()) {
                EmptyState(
                    icon = "🔍",
                    title = "לא נמצאו חסימות",
                    message = "נסה חיפוש אחר.",
                    actionLabel = "נקה חיפוש",
                    onAction = { query = "" }
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { "${it.pkg}|${it.target}" }) { e ->
                        val approvedGlobal = e.isDomain && e.target in rules.global
                        val approvedApp =
                            e.isDomain && e.pkg != null && e.target in (rules.apps[e.pkg]?.domains ?: emptySet())
                        AppCard {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(e.pkg, 28.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        e.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    InfoText(fmt.format(Date(e.time)))
                                }
                                Text(
                                    e.target,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                when {
                                    approvedGlobal || approvedApp ->
                                        Text("✅ אושר", style = MaterialTheme.typography.bodySmall)
                                    e.isDomain -> Row {
                                        TextButton(onClick = { Rules.addGlobal(e.target) }) { Text("אשר לכולם") }
                                        if (e.pkg != null) {
                                            TextButton(onClick = { Rules.addAppDomain(e.pkg, e.target) }) {
                                                Text("אשר רק לאפליקציה")
                                            }
                                        }
                                    }
                                    else -> InfoText("כתובת IP בלי דומיין ידוע")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
