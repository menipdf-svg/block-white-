@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.image.ImageCache
import com.meni.whitelistfilter.image.ImageCategory
import com.meni.whitelistfilter.image.ImageFilterSettings
import com.meni.whitelistfilter.image.ImageJudge
import com.meni.whitelistfilter.image.Sensitivity
import com.meni.whitelistfilter.image.WomenLevel

@Composable
fun ImagesScreen() {
    val ctx = LocalContext.current
    remember { ImageFilterSettings.init(ctx) }
    val cfg by ImageFilterSettings.config.collectAsState()
    val checked by FilterState.imagesChecked.collectAsState()
    val blurred by FilterState.imagesBlurred.collectAsState()
    val imageLog by FilterState.imageLog.collectAsState()
    var showLog by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "סינון תמונות",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "בפיירפוקס המסונן כל תמונה נבדקת במודל AI שרץ על המכשיר עצמו, ומה שבחרת מוחלף במשטח אפור לפני שהוא מוצג. שום תמונה לא נשלחת לשרת.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (cfg.enabled) "✅ סינון תמונות פעיל" else "⛔ סינון תמונות כבוי",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = cfg.enabled, onCheckedChange = { on -> ImageFilterSettings.update { it.copy(enabled = on) } })
                    }
                    if (checked > 0) {
                        Text("נבדקו $checked תמונות, טושטשו $blurred", style = MaterialTheme.typography.bodySmall)
                    }
                    ImageJudge.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val launch = ctx.packageManager.getLaunchIntentForPackage("org.mozilla.fenix.debug")
                                ?: ctx.packageManager.getLaunchIntentForPackage("org.mozilla.fenix")
                            if (launch != null) ctx.startActivity(launch)
                            else Toast.makeText(ctx, "פיירפוקס המסונן לא מותקן", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🦊 פתח את פיירפוקס המסונן") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            ImageCache.clear(ctx)
                            Toast.makeText(ctx, "התמונות נמחקו מהמטמון", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🗑️ מחק תמונות מהמטמון") }
                }
            }
        }

        item { SectionTitle("מה לטשטש") }
        items(ImageCategory.entries.size) { i ->
            val cat = ImageCategory.entries[i]
            Card(Modifier.fillMaxWidth().clickable { ImageFilterSettings.toggleCategory(cat) }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cat.emoji, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(cat.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Checkbox(checked = cat in cfg.categories, onCheckedChange = { ImageFilterSettings.toggleCategory(cat) })
                }
            }
        }

        item { SectionTitle("👩 נשים") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    WomenLevel.entries.forEach { level ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { ImageFilterSettings.update { it.copy(women = level) } }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = cfg.women == level,
                                onClick = { ImageFilterSettings.update { it.copy(women = level) } }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(level.title, style = MaterialTheme.typography.bodyLarge)
                                Text(level.desc, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (cfg.women != WomenLevel.OFF && ImageCategory.PEOPLE in cfg.categories) {
                        Text(
                            "\"אנשים\" מסומן למעלה, אז כל התמונות של אנשים מטושטשות בכל מקרה — כולל נשים.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        "הזיהוי מבוסס על הפנים ועל הלבוש. כשלא רואים פנים (מהגב, מרחוק) הוא פחות מדויק.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }

        item { SectionTitle("רגישות") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sensitivity.entries.forEach { s ->
                    FilterChip(
                        selected = cfg.sensitivity == s,
                        onClick = { ImageFilterSettings.update { it.copy(sensitivity = s) } },
                        label = { Text(s.title) }
                    )
                }
            }
            Text(
                "רגישות גבוהה מטשטשת גם כשהמודל פחות בטוח — יותר חסימות, וגם יותר טעויות.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("טשטש תמונות שלא נבדקו", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "פורמט לא נתמך או שגיאה בבדיקה — ליתר ביטחון התמונה מטושטשת",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = cfg.blockUnchecked,
                        onCheckedChange = { on -> ImageFilterSettings.update { it.copy(blockUnchecked = on) } }
                    )
                }
            }
        }

        item { SectionTitle("📋 יומן תמונות") }
        item {
            Card(Modifier.fillMaxWidth().clickable { showLog = !showLog }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("מה המודל החליט על כל תמונה", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${imageLog.size} תמונות אחרונות. אם משהו נחסם או עבר בטעות — צלם את המסך הזה ושלח.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(if (showLog) "▲" else "▼")
                }
            }
        }
        if (showLog) {
            item {
                TextButton(onClick = { FilterState.clearImageLog() }) { Text("נקה יומן") }
            }
            items(imageLog.size) { i ->
                val e = imageLog[i]
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (e.blocked) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            (if (e.blocked) "⛔ נחסם: " else "✅ עבר: ") + e.reason,
                            style = MaterialTheme.typography.labelLarge
                        )
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
            Text(
                "חשוב: סינון התמונות עובד רק בפיירפוקס המסונן. את האתרים שמותרים בו קובעים בלשונית האפליקציות (org.mozilla.fenix.debug ← רשימה לבנה). כדי שלא יעקפו אותו, הגדר את כרום ושאר הדפדפנים כ\"חסום\".",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}
