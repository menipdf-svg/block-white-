package com.meni.whitelistfilter.ui

import androidx.compose.runtime.saveable.listSaver
import com.meni.whitelistfilter.AppRule
import com.meni.whitelistfilter.Mode
import com.meni.whitelistfilter.RulesData

enum class AppSort(val title: String) {
    NAME("א-ב"),
    MODE("לפי מצב")
}

/** כל מצב הסינון של מסך האפליקציות במקום אחד. שורד סיבוב מסך */
data class AppFilters(
    val query: String = "",
    val modes: Set<Mode> = emptySet(),
    val includeSystem: Boolean = false,
    val sort: AppSort = AppSort.NAME
) {
    /** כולל את החיפוש */
    val activeCount: Int
        get() = (if (query.isNotBlank()) 1 else 0) + modes.size +
            (if (includeSystem) 1 else 0) + (if (sort != AppSort.NAME) 1 else 0)

    companion object {
        val Saver = listSaver<AppFilters, Any>(
            save = { listOf(it.query, it.modes.joinToString(",") { m -> m.name }, it.includeSystem, it.sort.name) },
            restore = {
                AppFilters(
                    query = it[0] as String,
                    modes = (it[1] as String).split(",").filter { s -> s.isNotEmpty() }.map { s -> Mode.valueOf(s) }.toSet(),
                    includeSystem = it[2] as Boolean,
                    sort = AppSort.valueOf(it[3] as String)
                )
            }
        )
    }
}

fun modeOf(app: AppItem, rules: RulesData): Mode = (rules.apps[app.pkg] ?: AppRule()).mode

fun applyFilters(apps: List<AppItem>, rules: RulesData, f: AppFilters): List<AppItem> {
    val q = f.query.trim().lowercase()
    val filtered = apps.filter {
        (f.includeSystem || !it.system || rules.apps.containsKey(it.pkg)) &&
            (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q)) &&
            (f.modes.isEmpty() || modeOf(it, rules) in f.modes)
    }
    return when (f.sort) {
        AppSort.NAME -> filtered
        AppSort.MODE -> filtered.sortedBy { modeOf(it, rules).ordinal }
    }
}
