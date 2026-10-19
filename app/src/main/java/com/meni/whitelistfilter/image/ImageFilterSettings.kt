package com.meni.whitelistfilter.image

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** סוגי התמונות שאפשר לבחור לחסום */
enum class ImageCategory(val title: String, val emoji: String) {
    PEOPLE("אנשים (כל תמונה עם פנים)", "🧍")
}

/** סינון תמונות של נשים — שלוש רמות */
enum class WomenLevel(val title: String, val desc: String) {
    OFF("כבוי", "לא בודקים מגדר"),
    SWIMWEAR("בגד ים", "מטשטש נשים בבגד ים או לבוש חשוף מאוד"),
    PARTIAL("לבוש חלקי", "מטשטש נשים עם ידיים, רגליים או כתפיים חשופות"),
    ALL("חסימה מלאה", "מטשטש כל תמונה שיש בה אישה")
}

/** כמה בטוח המודל צריך להיות כדי לטשטש. רגישות גבוהה = מטשטש יותר (גם כשהוא פחות בטוח) */
enum class Sensitivity(val title: String, val threshold: Float) {
    LOW("נמוכה", 0.6f),
    MEDIUM("בינונית", 0.4f),
    HIGH("גבוהה", 0.25f)
}

data class ImageFilterConfig(
    val enabled: Boolean = true,
    val categories: Set<ImageCategory> = setOf(ImageCategory.PEOPLE),
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val women: WomenLevel = WomenLevel.OFF,
    /** תמונה שלא הצלחנו לבדוק (פורמט לא נתמך, שגיאה) — לטשטש ליתר ביטחון */
    val blockUnchecked: Boolean = true
) {
    /** האם יש בכלל משהו לבדוק */
    val active: Boolean get() = enabled && (categories.isNotEmpty() || women != WomenLevel.OFF)
}

object ImageFilterSettings {
    private const val PREFS = "image_filter"

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(ImageFilterConfig())
    val config: StateFlow<ImageFilterConfig> = _config

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cats = prefs.getStringSet("categories", null)
            ?.mapNotNull { name -> ImageCategory.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: ImageFilterConfig().categories
        _config.value = ImageFilterConfig(
            enabled = prefs.getBoolean("enabled", true),
            categories = cats,
            sensitivity = Sensitivity.entries.firstOrNull { it.name == prefs.getString("sensitivity", null) }
                ?: Sensitivity.MEDIUM,
            blockUnchecked = prefs.getBoolean("block_unchecked", true),
            women = WomenLevel.entries.firstOrNull { it.name == prefs.getString("women", null) } ?: WomenLevel.OFF
        )
    }

    fun update(block: (ImageFilterConfig) -> ImageFilterConfig) {
        _config.update(block)
        val c = _config.value
        prefs.edit()
            .putBoolean("enabled", c.enabled)
            .putStringSet("categories", c.categories.map { it.name }.toSet())
            .putString("sensitivity", c.sensitivity.name)
            .putBoolean("block_unchecked", c.blockUnchecked)
            .putString("women", c.women.name)
            .apply()
    }

    fun toggleCategory(cat: ImageCategory) = update {
        it.copy(categories = if (cat in it.categories) it.categories - cat else it.categories + cat)
    }
}
