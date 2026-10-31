@file:OptIn(ExperimentalMaterial3Api::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.meni.whitelistfilter.Mode
import com.meni.whitelistfilter.Pin

/** טון סמנטי של מצב: מותר, חסום, זהירות, או ניטרלי. תמיד נלווה בטקסט ובאייקון */
enum class Tone { ALLOWED, BLOCKED, CAUTION, NEUTRAL }

/** צבע מלא לתגיות ולטקסט על רקע השטח */
@Composable
fun Tone.solid(): Color = when (this) {
    Tone.ALLOWED -> semantic.allowed
    Tone.BLOCKED -> semantic.blocked
    Tone.CAUTION -> semantic.caution
    Tone.NEUTRAL -> semantic.neutral
}

@Composable
fun Tone.onSolid(): Color = when (this) {
    Tone.ALLOWED -> semantic.onAllowed
    Tone.BLOCKED -> semantic.onBlocked
    Tone.CAUTION -> semantic.onCaution
    Tone.NEUTRAL -> semantic.onNeutral
}

@Composable
fun Tone.container(): Color = when (this) {
    Tone.ALLOWED -> semantic.allowedContainer
    Tone.BLOCKED -> semantic.blockedContainer
    Tone.CAUTION -> semantic.cautionContainer
    Tone.NEUTRAL -> semantic.neutralContainer
}

@Composable
fun Tone.onContainer(): Color = when (this) {
    Tone.ALLOWED -> semantic.onAllowedContainer
    Tone.BLOCKED -> semantic.onBlockedContainer
    Tone.CAUTION -> semantic.onCautionContainer
    Tone.NEUTRAL -> semantic.onNeutralContainer
}

fun Mode.tone(): Tone = when (this) {
    Mode.BLOCK -> Tone.BLOCKED
    Mode.WHITELIST -> Tone.ALLOWED
    Mode.BLACKLIST -> Tone.NEUTRAL
    Mode.OPEN -> Tone.CAUTION
}

/** אייקון האפליקציה. כשאין אייקון, מוצג מיכל טונלי עם סמל כללי */
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
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, Radius.tile),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

// ================= מבנה עמוד וקבוצות =================

/** כותרת קבוצה: טקסט קטן בצבע משני, כמו כותרות הקבוצות בהגדרות של אפל */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s)
    )
}

/** הסבר מתחת לקבוצה או בתוכה */
@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** עמוד רשימה אחיד: אותם שוליים ואותם רווחים בכל מסך */
@Composable
fun PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.l, top = Spacing.s, end = Spacing.l, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content
    )
}

/**
 * קבוצה בסגנון "רשימה מקובצת": משטח לבן-שקט עם פינות גדולות, בלי צל.
 * כל השורות בתוכה מחולקות בקו הפרדה דק.
 */
@Composable
fun InsetGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = Radius.group,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

/** קבוצה עם ריפוד פנימי: לטקסטים, שדות וכפתורים שאינם שורות */
@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    InsetGroup {
        Column(
            Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            content = content
        )
    }
}

/** קו הפרדה בין שורות בקבוצה, מוזז כך שלא יעבור מתחת לשוליים */
@Composable
fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.l),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** קבוצה שלמה ברשימה עצלה: כותרת אופציונלית, תוכן, והסבר מתחת */
fun LazyListScope.inset(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    item {
        Column {
            if (title != null) SectionTitle(title)
            InsetGroup(content)
            if (footer != null) {
                InfoText(
                    footer,
                    Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s)
                )
            }
        }
    }
}

// ================= שורות =================

/** שורה עם כותרת, הסבר ומתג. כל השורה לחיצה, וזה גם מה שקורא מסך מקריא */
@Composable
fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange
            )
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (desc != null) InfoText(desc)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** שורה עם תיבת סימון (לבחירה מרובה). כל השורה לחיצה */
@Composable
fun CheckRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) InfoText(desc)
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/**
 * בחירה בין אפשרויות, בסגנון "רשימת סימון": הבחירה מסומנת בצ'ק בצד.
 * הדרך הנכונה לבחור מצב אחד מתוך כמה, במקום שורת שבבים
 */
@Composable
fun ChoiceRow(title: String, desc: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else null
            )
            if (desc != null) InfoText(desc)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** אייקון מרובע בצבע הראשי, בסגנון אייקוני ההגדרות של אפל */
@Composable
fun SettingTile(icon: ImageVector) {
    Box(
        Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primary, Radius.tile),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** שורת ניווט בתפריט ההגדרות: אייקון, כותרת, הסבר וחץ קדימה (שמאלה ב-RTL) */
@Composable
fun NavRow(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingTile(icon)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            InfoText(desc)
        }
        Icon(
            Icons.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** שורת דומיין עם כפתור פעולה (הסר / הוסף וכו') */
@Composable
fun DomainRow(domain: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            domain,
            Modifier.weight(1f).padding(start = Spacing.l),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** שדה להוספת דומיין עם כפתור "הוסף". לחיצה על Done בתוך המקלדת מוסיפה גם היא */
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
        Spacer(Modifier.width(Spacing.s))
        FilledTonalButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("הוסף") }
    }
}

// ================= מצבים, הודעות וגוונים =================

/** באנר מצב: רקע בגוון הטון, אייקון, כותרת והסבר. לאזהרות והסברים חשובים */
@Composable
fun StatusBanner(tone: Tone, icon: ImageVector, title: String, body: String? = null) {
    Surface(
        color = tone.container(),
        contentColor = tone.onContainer(),
        shape = Radius.group,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body != null) Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** הודעת תוצאה קצרה (הצלחה, כישלון, מצב בתהליך). הטון נשלח גם באייקון */
@Composable
fun StatusMessage(text: String, tone: Tone = Tone.NEUTRAL) {
    val icon = when (tone) {
        Tone.ALLOWED -> Icons.Filled.Check
        Tone.BLOCKED -> Icons.Filled.Warning
        Tone.CAUTION -> Icons.Filled.Warning
        Tone.NEUTRAL -> Icons.Filled.Info
    }
    val color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else tone.solid()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** באנר של "דורש Device Owner" כשהוא לא מוגדר */
@Composable
fun OwnerNotice() {
    StatusBanner(
        tone = Tone.CAUTION,
        icon = Icons.Filled.Warning,
        title = "דורש Device Owner",
        body = "האפליקציה עדיין לא מוגדרת כ-Device Owner, ולכן המתגים כאן כבויים. ההגדרה נמצאת ב: הגדרות ← הגנה מפני עקיפה."
    )
}

/** תגית מצב: צבע מלא עם טקסט, כך שהמצב נקרא גם בלי צבע */
@Composable
fun ModeBadge(mode: Mode, extra: Int) {
    val tone = mode.tone()
    val text = if (mode == Mode.WHITELIST && extra > 0) "${mode.title} +$extra" else mode.title
    Surface(
        color = tone.solid(),
        contentColor = tone.onSolid(),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs)
        )
    }
}

fun modeDescription(mode: Mode) = when (mode) {
    Mode.BLOCK -> "האפליקציה לא מקבלת אינטרנט בכלל"
    Mode.WHITELIST -> "רק דומיינים מהרשימה הכללית ומהרשימה של האפליקציה הזו"
    Mode.BLACKLIST -> "הכל מותר, חוץ מהרשימה השחורה הכללית ומהחסומים של האפליקציה הזו"
    Mode.OPEN -> "גישה מלאה לכל האינטרנט, בלי סינון"
}

// ================= קוד נעילה =================

/**
 * מחזיר פונקציה שמריצה פעולה רגישה רק אחרי הקשת הקוד.
 * אם לא הוגדר קוד — הפעולה רצה מיד. כך אי אפשר לבטל הגנה רק בגלל שהאפליקציה נשארה פתוחה.
 */
@Composable
fun rememberPinGuard(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    pending?.let { action ->
        PinDialog(
            onSuccess = {
                pending = null
                action()
            },
            onCancel = { pending = null }
        )
    }
    return { action -> if (Pin.isSet(ctx)) pending = action else action() }
}

@Composable
private fun PinDialog(onSuccess: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("הכנס קוד") },
        text = {
            Column {
                InfoText("הפעולה הזו מחלישה את ההגנה, ולכן צריך את הקוד.")
                Spacer(Modifier.height(Spacing.m))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(12)
                        error = false
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error,
                    label = { Text("קוד") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Spacer(Modifier.height(Spacing.s))
                    StatusMessage("קוד שגוי", Tone.BLOCKED)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (Pin.check(ctx, pin)) onSuccess() else error = true }) { Text("אשר") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("ביטול") } }
    )
}
