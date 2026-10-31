@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.l, top = Spacing.m, end = Spacing.l, bottom = Spacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        item { SearchField(query, { query = it }, "חיפוש דומיין", Modifier.fillMaxWidth()) }

        if (searching && white.isEmpty() && black.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "לא נמצאו דומיינים",
                    message = "אין דומיין שמכיל \"$query\" באף אחת מהרשימות.",
                    modifier = Modifier.height(320.dp),
                    actionLabel = "נקה חיפוש",
                    onAction = { query = "" }
                )
            }
        } else {
            // ----- רשימה לבנה כללית -----
            item {
                Column {
                    SectionTitle(
                        if (searching) "רשימה כללית (${white.size} מתוך ${rules.global.size})"
                        else "רשימה כללית (${rules.global.size})"
                    )
                    InfoText(
                        "דומיינים שמותרים לכל האפליקציות שבמצב רשימה לבנה. תת-דומיינים נכללים אוטומטית.",
                        Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s)
                    )
                    GroupCard { DomainInput { Rules.addGlobal(it) } }
                }
            }
            if (white.isEmpty()) item { InfoText("אין דומיינים ברשימה", Modifier.padding(horizontal = Spacing.l)) }
            items(white, key = { it }) { d -> DomainCard(d) { Rules.removeGlobal(d) } }

            // ----- רשימה שחורה כללית -----
            item {
                Column {
                    SectionTitle(
                        if (searching) "רשימה שחורה כללית (${black.size} מתוך ${rules.globalBlack.size})"
                        else "רשימה שחורה כללית (${rules.globalBlack.size})"
                    )
                    InfoText(
                        "דומיינים שנחסמים לכל האפליקציות שבמצב רשימה שחורה. תת-דומיינים נכללים אוטומטית.",
                        Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s)
                    )
                    GroupCard { DomainInput { Rules.addGlobalBlack(it) } }
                }
            }
            if (black.isEmpty()) item { InfoText("אין דומיינים ברשימה", Modifier.padding(horizontal = Spacing.l)) }
            items(black, key = { "black_$it" }) { d -> DomainCard(d) { Rules.removeGlobalBlack(d) } }
        }
    }
}

/** שורת דומיין ברשימה: שם הדומיין וכפתור הסרה מסומן */
@Composable
private fun DomainCard(domain: String, onRemove: () -> Unit) {
    ListCard {
        Box(Modifier.padding(horizontal = Spacing.s)) {
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
                icon = AppIcons.Journal,
                title = "אין חסימות עדיין",
                message = "כשאפליקציה תנסה להגיע לדומיין חסום, הוא יופיע כאן ותוכל לאשר אותו."
            )
        } else {
            SearchField(
                query, { query = it }, "חיפוש לפי אפליקציה או דומיין",
                Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(top = Spacing.m)
            )
            ResultsHeader(
                shown = visible.size,
                total = log.size,
                unit = "חסימות",
                hasFilters = q.isNotEmpty(),
                onReset = { query = "" },
                modifier = Modifier.padding(top = Spacing.s)
            ) {
                TextButton(onClick = { FilterState.clearLog() }) { Text("נקה יומן") }
            }

            if (visible.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "לא נמצאו חסימות",
                    message = "נסה חיפוש אחר.",
                    actionLabel = "נקה חיפוש",
                    onAction = { query = "" }
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.l, top = Spacing.s, end = Spacing.l, bottom = Spacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s)
                ) {
                    items(visible, key = { "${it.pkg}|${it.target}" }) { e ->
                        val approvedGlobal = e.isDomain && e.target in rules.global
                        val approvedApp =
                            e.isDomain && e.pkg != null && e.target in (rules.apps[e.pkg]?.domains ?: emptySet())
                        ListCard {
                            Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(e.pkg, 28.dp)
                                    Spacer(Modifier.width(Spacing.s))
                                    Text(
                                        e.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    InfoText(fmt.format(Date(e.time)))
                                }
                                Text(
                                    e.target,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = Spacing.xs)
                                )
                                when {
                                    approvedGlobal || approvedApp -> StatusMessage("אושר", Tone.ALLOWED)
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
