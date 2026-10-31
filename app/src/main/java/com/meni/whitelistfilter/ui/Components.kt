@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * רכיבי רשימה וסינון לשימוש חוזר בכל מסכי הרשימות:
 * ListCard · SearchField · FilterButton · ActiveFilterChips · ResultsHeader · EmptyState · FilterSheet · FilterGroup
 */

/**
 * שורה ברשימה עצלה: משטח לבן-שקט עם פינות מעוגלות, בלי צל.
 * הצל הוסר כי הוא כפל ויזואלי מיותר מול הרקע הטונלי. עם onClick השורה לחיצה
 */
@Composable
fun ListCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val base = Modifier.fillMaxWidth()
    val modifier = if (onClick != null) base.clip(Radius.row).clickable(onClick = onClick) else base
    Card(
        modifier = modifier,
        shape = Radius.row,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

/** שדה חיפוש מעוגל עם כפתור ניקוי. גבול השדה בניגודיות 3:1 לפחות (WCAG 1.4.11) */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "נקה חיפוש")
                }
            }
        },
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    )
}

/** כפתור פתיחת הסינון, עם מונה של הסינונים הפעילים */
@Composable
fun FilterButton(activeCount: Int, onClick: () -> Unit) {
    BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.height(56.dp)) {
            Icon(AppIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text("סינון")
        }
    }
}

data class ActiveChip(val label: String, val onDismiss: () -> Unit)

/** תגיות של הסינונים הפעילים. לחיצה על תגית מסירה אותה */
@Composable
fun ActiveFilterChips(chips: List<ActiveChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        contentPadding = PaddingValues(horizontal = Spacing.l)
    ) {
        items(chips, key = { it.label }) { chip ->
            InputChip(
                selected = true,
                onClick = chip.onDismiss,
                label = { Text(chip.label) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "הסר ${chip.label}", modifier = Modifier.size(16.dp)) }
            )
        }
    }
}

/** "מציג 24 מתוך 112 אפליקציות" + כפתור איפוס. extra מוסיף כפתורים בצד */
@Composable
fun ResultsHeader(
    shown: Int,
    total: Int,
    unit: String,
    hasFilters: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Spacing.l, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (hasFilters) "מציג $shown מתוך $total $unit" else "$total $unit",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (hasFilters) TextButton(onClick = onReset) { Text("נקה סינון") }
        extra()
    }
}

/** מצב ריק: אייקון בעיגול טונלי, כותרת, הסבר, ואפשרות לפעולה (למשל איפוס הסינון) */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxSize().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.s))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.l))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** חלון סינון שעולה מלמטה. הכפתור התחתון סוגר אותו ומראה כמה תוצאות יש */
@Composable
fun FilterSheet(
    title: String,
    resultCount: Int,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("אפס") }
            }
            content()
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("הצג $resultCount תוצאות")
            }
        }
    }
}

/** קבוצה בתוך חלון הסינון: כותרת קטנה ותוכן */
@Composable
fun FilterGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}
