@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.meni.whitelistfilter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * רכיבי סינון לשימוש חוזר בכל מסכי הרשימות:
 * SearchField · FilterButton · ActiveFilterChips · ResultsHeader · EmptyState · FilterSheet · FilterGroup · AppCard
 */

/** כרטיס מורם עם פינות מעוגלות וצל עדין. עם onClick הוא לחיץ */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    if (onClick != null) {
        ElevatedCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            elevation = elevation,
            content = content
        )
    } else {
        ElevatedCard(modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation, content = content)
    }
}

/** שדה חיפוש מעוגל עם כפתור ניקוי */
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
        leadingIcon = { Text("🔍") },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Text("✕") }
        },
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    )
}

/** כפתור פתיחת הסינון, עם מונה של הסינונים הפעילים */
@Composable
fun FilterButton(activeCount: Int, onClick: () -> Unit) {
    BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.height(56.dp)) { Text("סינון") }
    }
}

data class ActiveChip(val label: String, val onDismiss: () -> Unit)

/** תגיות של הסינונים הפעילים. לחיצה על תגית מסירה אותה */
@Composable
fun ActiveFilterChips(chips: List<ActiveChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(chips, key = { it.label }) { chip ->
            InputChip(
                selected = true,
                onClick = chip.onDismiss,
                label = { Text(chip.label) },
                trailingIcon = { Text("✕", style = MaterialTheme.typography.labelSmall) }
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
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

/** מצב ריק: אייקון, כותרת, הסבר, ואפשרות לפעולה (למשל איפוס הסינון) */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(icon, style = MaterialTheme.typography.displaySmall) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}
