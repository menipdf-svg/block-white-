@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import android.widget.Toast
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.debug.LogSync
import com.meni.whitelistfilter.image.ImageCache
import com.meni.whitelistfilter.image.ImageCategory
import com.meni.whitelistfilter.image.ImageFilterSettings
import com.meni.whitelistfilter.image.ImageJudge
import com.meni.whitelistfilter.image.Sensitivity
import com.meni.whitelistfilter.image.WomenLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImagesScreen() {
    val ctx = LocalContext.current
    remember { ImageFilterSettings.init(ctx) }
    val cfg by ImageFilterSettings.config.collectAsState()
    val checked by FilterState.imagesChecked.collectAsState()
    val blurred by FilterState.imagesBlurred.collectAsState()
    val imageLog by FilterState.imageLog.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var askToken by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun sendLog() {
        if (LogSync.token(ctx) == null) {
            askToken = true
            return
        }
        sending = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) { LogSync.send(ctx) }
            sending = false
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (askToken) {
        TokenDialog(
            onSave = { t ->
                LogSync.setToken(ctx, t)
                askToken = false
                sendLog()
            },
            onDismiss = { askToken = false }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.l, top = Spacing.s, end = Spacing.l, bottom = Spacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        item {
            InfoText(
                "בפיירפוקס המסונן כל תמונה נבדקת במודל AI שרץ על המכשיר עצמו, ומה שבחרת מוחלף במשטח אפור לפני שהוא מוצג. שום תמונה לא נשלחת לשרת.",
                Modifier.padding(bottom = Spacing.s)
            )
        }

        // ----- מצב הסינון והפעולות -----
        inset {
            SwitchRow(
                title = if (cfg.enabled) "סינון תמונות פעיל" else "סינון תמונות כבוי",
                desc = if (checked > 0) "נבדקו $checked תמונות, טושטשו $blurred" else null,
                checked = cfg.enabled
            ) { on -> ImageFilterSettings.update { it.copy(enabled = on) } }
            Column(
                Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                ImageJudge.error?.let { StatusMessage(it, Tone.BLOCKED) }
                Button(
                    onClick = {
                        val launch = ctx.packageManager.getLaunchIntentForPackage("org.mozilla.fenix.debug")
                            ?: ctx.packageManager.getLaunchIntentForPackage("org.mozilla.fenix")
                        if (launch != null) ctx.startActivity(launch)
                        else Toast.makeText(ctx, "פיירפוקס המסונן לא מותקן", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("פתח את פיירפוקס המסונן") }
                OutlinedButton(
                    onClick = {
                        ImageCache.clear(ctx)
                        Toast.makeText(ctx, "התמונות נמחקו מהמטמון", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("מחק תמונות מהמטמון") }
            }
        }

        // ----- מה לטשטש: בחירה מרובה -----
        inset(title = "מה לטשטש") {
            ImageCategory.entries.forEachIndexed { i, cat ->
                if (i > 0) RowDivider()
                CheckRow(cat.title, null, checked = cat in cfg.categories) {
                    ImageFilterSettings.toggleCategory(cat)
                }
            }
        }

        // ----- נשים: בחירה אחת -----
        inset(
            title = "נשים",
            footer = "הזיהוי מבוסס על הפנים ועל הלבוש. כשלא רואים פנים (מהגב, מרחוק) הוא פחות מדויק."
        ) {
            WomenLevel.entries.forEachIndexed { i, level ->
                if (i > 0) RowDivider()
                ChoiceRow(level.title, level.desc, selected = cfg.women == level) {
                    ImageFilterSettings.update { it.copy(women = level) }
                }
            }
            if (cfg.women != WomenLevel.OFF && ImageCategory.PEOPLE in cfg.categories) {
                RowDivider()
                InfoText(
                    "\"אנשים\" מסומן למעלה, אז כל התמונות של אנשים מטושטשות בכל מקרה — כולל נשים.",
                    Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
                )
            }
        }

        // ----- רגישות: בחירה אחת -----
        inset(
            title = "רגישות",
            footer = "רגישות גבוהה מטשטשת גם כשהמודל פחות בטוח — יותר חסימות, וגם יותר טעויות."
        ) {
            Sensitivity.entries.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                ChoiceRow(s.title, null, selected = cfg.sensitivity == s) {
                    ImageFilterSettings.update { it.copy(sensitivity = s) }
                }
            }
        }

        inset {
            SwitchRow(
                title = "טשטש תמונות שלא נבדקו",
                desc = "פורמט לא נתמך או שגיאה בבדיקה — ליתר ביטחון התמונה מטושטשת",
                checked = cfg.blockUnchecked
            ) { on -> ImageFilterSettings.update { it.copy(blockUnchecked = on) } }
        }

        // ----- יומן תמונות -----
        item { SectionTitle("יומן תמונות") }
        item {
            InsetGroup {
                ExpandableRow(
                    title = "מה המודל החליט על כל תמונה",
                    subtitle = "${imageLog.size} תמונות אחרונות. אם משהו נחסם או עבר בטעות — לחץ \"שלח ל-Claude\".",
                    open = showLog,
                    onToggle = { showLog = !showLog }
                )
                RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { sendLog() },
                        enabled = !sending,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (sending) "שולח…" else "שלח את היומן ל-Claude")
                    }
                    TextButton(onClick = { askToken = true }) { Text("טוקן") }
                }
            }
        }
        if (showLog) {
            item {
                TextButton(onClick = { FilterState.clearImageLog() }) { Text("נקה יומן") }
            }
            items(imageLog.size) { i ->
                val e = imageLog[i]
                val tone = if (e.blocked) Tone.BLOCKED else Tone.ALLOWED
                Surface(
                    color = if (e.blocked) tone.container() else MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = if (e.blocked) tone.onContainer() else MaterialTheme.colorScheme.onSurface,
                    shape = Radius.row,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(Spacing.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (e.blocked) Icons.Filled.Close else Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                (if (e.blocked) "נחסם: " else "עבר: ") + e.reason,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        if (e.detail.isNotBlank()) {
                            Text(e.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            e.url.removePrefix("https://").removePrefix("http://").take(90),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        item {
            InfoText(
                "חשוב: סינון התמונות עובד רק בפיירפוקס המסונן. את האתרים שמותרים בו קובעים בלשונית האפליקציות (org.mozilla.fenix.debug ← רשימה לבנה). כדי שלא יעקפו אותו, הגדר את כרום ושאר הדפדפנים כ\"חסום\".",
                Modifier.padding(vertical = Spacing.l)
            )
        }
    }
}

/** הגדרת טוקן GitHub לשליחת היומן */
@Composable
private fun TokenDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(LogSync.token(ctx) ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("טוקן GitHub") },
        text = {
            Column {
                InfoText(
                    "כדי לשלוח את היומן צריך טוקן GitHub (Fine-grained) לריפו block-white- בלבד, עם הרשאת Issues: Read and write. הטוקן נשמר רק בטלפון."
                )
                Spacer(Modifier.height(Spacing.s))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    singleLine = true,
                    label = { Text("github_pat_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(token) }, enabled = token.length > 20) { Text("שמור ושלח") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
