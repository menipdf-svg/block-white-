@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.meni.whitelistfilter.Mode

@Composable
fun AppIcon(pkg: String?, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        try {
            if (pkg == null) null
            else ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size))
    else Box(Modifier.size(size), contentAlignment = Alignment.Center) { Text("⚙️") }
}

@Composable
fun DomainInput(label: String = "דומיין להוספה, למשל wikipedia.org", onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { submit() }) { Text("הוסף") }
    }
}

@Composable
fun DomainRow(domain: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp)
    )
}

/** רשימה אחידה לדפי הגדרות: אותם שוליים ואותם רווחים בכל מקום */
@Composable
fun PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        content = content
    )
}

/** כרטיס עם ריפוד ורווחים אחידים */
@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** טקסט הסבר קטן */
@Composable
fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** שורה עם כותרת, הסבר ומתג */
@Composable
fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) InfoText(desc)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** שורת ניווט בתפריט ההגדרות */
@Composable
fun NavRow(icon: String, title: String, desc: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                InfoText(desc)
            }
            Text("←", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** הודעה שמופיעה בדפים שדורשים Device Owner כשהוא לא מוגדר */
@Composable
fun OwnerNotice() {
    GroupCard {
        Text("⚠️ דורש Device Owner", style = MaterialTheme.typography.titleSmall)
        InfoText("האפליקציה עדיין לא מוגדרת כ-Device Owner, ולכן המתגים כאן כבויים. ההגדרה נמצאת ב: הגדרות ← הגנה מפני עקיפה.")
    }
}

@Composable
fun ModeBadge(mode: Mode, extra: Int) {
    val color = when (mode) {
        Mode.BLOCK -> Color(0xFFC62828)
        Mode.WHITELIST -> Color(0xFF1E6B52)
        Mode.BLACKLIST -> Color(0xFF37474F)
        Mode.OPEN -> Color(0xFFE08A00)
    }
    val text = if (mode == Mode.WHITELIST && extra > 0) "${mode.title} +$extra" else mode.title
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

fun modeDescription(mode: Mode) = when (mode) {
    Mode.BLOCK -> "האפליקציה לא מקבלת אינטרנט בכלל"
    Mode.WHITELIST -> "רק דומיינים מהרשימה הכללית ומהרשימה של האפליקציה הזו"
    Mode.BLACKLIST -> "הכל מותר, חוץ מהרשימה השחורה הכללית ומהחסומים של האפליקציה הזו"
    Mode.OPEN -> "גישה מלאה לכל האינטרנט, בלי סינון"
}
