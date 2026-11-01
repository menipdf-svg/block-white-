package com.meni.whitelistfilter.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.Protection
import com.meni.whitelistfilter.install.InstallSettings
import com.meni.whitelistfilter.massinstall.MassInstall
import com.meni.whitelistfilter.massinstall.MassInstallService
import com.meni.whitelistfilter.massinstall.MassItem
import com.meni.whitelistfilter.massinstall.MassKind
import com.meni.whitelistfilter.massinstall.MassState
import kotlinx.coroutines.delay

@Composable
fun MassInstallScreen() {
    val ctx = LocalContext.current
    val queue by MassInstall.items.collectAsState()
    val running by MassInstall.running.collectAsState()
    val owner = remember { Protection.isOwner(ctx) }
    val guardOn = remember { InstallSettings.enabled(ctx) }
    val serviceOn by produceState(MassInstallService.instance != null) {
        while (true) {
            value = MassInstallService.instance != null
            delay(1000)
        }
    }

    var kindName by rememberSaveable { mutableStateOf(MassKind.PLAY.name) }
    val kind = MassKind.valueOf(kindName)
    var playText by rememberSaveable { mutableStateOf("") }
    var apkText by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(Tone.NEUTRAL) }
    val text = if (kind == MassKind.PLAY) playText else apkText

    PageList {
        if (kind == MassKind.APK && !owner) item { OwnerNotice() }

        inset(title = "מקור ההתקנה") {
            ChoiceRow(
                title = "גוגל פליי",
                desc = "פותח כל אפליקציה בפליי ושירות נגישות לוחץ \"התקנה\". לא שקט לגמרי, " +
                    "אבל עובד על כל אפליקציה בחנות.",
                selected = kind == MassKind.PLAY,
                onClick = { kindName = MassKind.PLAY.name }
            )
            RowDivider()
            ChoiceRow(
                title = "קבצי APK",
                desc = "מוריד מקישור https ומתקין בשקט לגמרי, בלי אישור ובלי לחיצות (Device Owner). " +
                    "מתאים לאפליקציות שיש להן קובץ APK ישיר.",
                selected = kind == MassKind.APK,
                onClick = { kindName = MassKind.APK.name }
            )
        }

        if (kind == MassKind.PLAY && !serviceOn) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    StatusBanner(
                        tone = Tone.CAUTION,
                        icon = Icons.Filled.Warning,
                        title = "שירות הנגישות כבוי",
                        body = "הגדרות ← נגישות ← התקנה המונית ← הפעל. אם האפשרות אפורה: " +
                            "מידע על האפליקציה ← שלוש נקודות ← \"אפשר הגדרות מוגבלות\"."
                    )
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("פתח הגדרות נגישות") }
                }
            }
        }

        if (guardOn) {
            item {
                StatusBanner(
                    tone = Tone.CAUTION,
                    icon = Icons.Filled.Info,
                    title = "חסימת ההתקנות פעילה",
                    body = "כל אפליקציה חדשה מוקפאת ונבדקת בגוגל פליי. אפליקציה בקטגוריה חסומה " +
                        "תוסר או תוקפא גם אם ההתקנה ההמונית הצליחה."
                )
            }
        }

        inset(
            title = if (kind == MassKind.PLAY) "אפליקציות להתקנה" else "קישורים לקבצי APK",
            footer = if (kind == MassKind.PLAY) {
                "שם חבילה בכל שורה, למשל com.whatsapp. אפשר גם להדביק קישור מגוגל פליי. " +
                    "אפליקציות בתשלום או כאלה שכבר מותקנות מדולגות."
            } else {
                "קישור https ישיר לקובץ APK בכל שורה, קובץ אחד לכל אפליקציה (לא חבילות מפוצלות). " +
                    "את האפליקציה הזו לא מתקינים מעל עצמה."
            }
        ) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (kind == MassKind.PLAY) playText = it else apkText = it },
                    enabled = !running,
                    minLines = 6,
                    maxLines = 12,
                    label = { Text(if (kind == MassKind.PLAY) "אחד בכל שורה" else "קישור בכל שורה") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Button(
                        enabled = !running && text.isNotBlank(),
                        onClick = {
                            val (targets, bad) = MassInstall.parse(kind, text)
                            when {
                                targets.isEmpty() -> {
                                    message = "אין שורות תקינות ברשימה"
                                    messageTone = Tone.BLOCKED
                                }
                                kind == MassKind.PLAY && !serviceOn -> {
                                    message = "קודם מפעילים את שירות הנגישות"
                                    messageTone = Tone.BLOCKED
                                }
                                kind == MassKind.APK && !owner -> {
                                    message = "התקנה שקטה דורשת Device Owner"
                                    messageTone = Tone.BLOCKED
                                }
                                else -> {
                                    MassInstall.start(ctx, kind, targets)
                                    message = if (bad.isEmpty()) null else "${bad.size} שורות לא תקינות דולגו"
                                    messageTone = Tone.CAUTION
                                }
                            }
                        }
                    ) { Text("התחל") }
                    if (running) {
                        OutlinedButton(onClick = { MassInstall.cancel() }) { Text("עצור") }
                    }
                }
                message?.let { StatusMessage(it, messageTone) }
            }
        }

        if (queue.isNotEmpty()) {
            val finished = queue.count {
                it.state == MassState.DONE || it.state == MassState.ALREADY ||
                    it.state == MassState.SKIPPED || it.state == MassState.FAILED
            }
            item { SectionTitle("התקדמות: $finished מתוך ${queue.size}") }
            items(queue, key = { it.id }) { ProgressRow(it) }
        }
    }
}

@Composable
private fun ProgressRow(item: MassItem) {
    val ctx = LocalContext.current
    val label = remember(item.pkg, item.state) {
        val pkg = item.pkg
        try {
            if (pkg == null) {
                item.target
            } else {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }
        } catch (_: Exception) {
            item.pkg ?: item.target
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(item.pkg, 32.dp)
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            InfoText(item.state.title + if (item.note.isNotEmpty()) " · " + item.note else "")
        }
    }
}
