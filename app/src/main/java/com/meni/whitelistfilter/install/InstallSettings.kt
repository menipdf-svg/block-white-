package com.meni.whitelistfilter.install

import android.content.Context

/** הגדרות וזיכרון של חסימת ההתקנות לפי קטגוריות */
object InstallSettings {

    private const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("install_guard", Context.MODE_PRIVATE)

    // ---------- הגדרות ----------

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    /** בהפעלה נרשם הזמן: רק אפליקציות שהותקנו אחריו נבדקות */
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().apply {
            putBoolean("enabled", on)
            if (on) putLong("enabled_at", System.currentTimeMillis())
        }.apply()
    }

    fun enabledAt(ctx: Context) = prefs(ctx).getLong("enabled_at", Long.MAX_VALUE)

    /** true = להסיר אוטומטית. false = רק להקפיא (אפשר לשחרר ידנית) */
    fun uninstall(ctx: Context) = prefs(ctx).getBoolean("uninstall", true)

    fun setUninstall(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("uninstall", on).apply()
    }

    // ---------- קטגוריות חסומות ----------

    fun blocked(ctx: Context): Set<String> = getSet(ctx, "blocked")

    fun setBlocked(ctx: Context, id: String, on: Boolean) = edit(ctx, "blocked") { if (on) add(id) else remove(id) }

    fun isBlocked(ctx: Context, categoryId: String): Boolean {
        val set = blocked(ctx)
        return categoryId in set || (PlayCategories.isGame(categoryId) && PlayCategories.ALL_GAMES in set)
    }

    // ---------- מצב אפליקציות ----------

    /** אפליקציות שנבדקו ואושרו */
    fun verified(ctx: Context) = getSet(ctx, "verified")
    fun addVerified(ctx: Context, pkg: String) = edit(ctx, "verified") { add(pkg) }

    /** אפליקציות שאישרת ידנית — לא נבדקות שוב */
    fun allowed(ctx: Context) = getSet(ctx, "allowed")
    fun addAllowed(ctx: Context, pkg: String) = edit(ctx, "allowed") { add(pkg) }

    /** אפליקציות שהותקנו וממתינות לבדיקה (מוקפאות בינתיים) */
    fun pending(ctx: Context) = getSet(ctx, "pending")
    fun addPending(ctx: Context, pkg: String) = edit(ctx, "pending") { add(pkg) }
    fun removePending(ctx: Context, pkg: String) = edit(ctx, "pending") { remove(pkg) }

    /** אפליקציות בקטגוריה חסומה שהוקפאו ולא הוסרו (מצב "רק הקפאה") — pkg לקטגוריה */
    fun held(ctx: Context): Map<String, String> =
        getSet(ctx, "held").associate {
            val i = it.indexOf('|')
            if (i < 0) it to "" else it.substring(0, i) to it.substring(i + 1)
        }

    fun addHeld(ctx: Context, pkg: String, category: String) {
        removeHeld(ctx, pkg)
        edit(ctx, "held") { add("$pkg|$category") }
    }

    fun removeHeld(ctx: Context, pkg: String) =
        edit(ctx, "held") { removeAll { it == pkg || it.startsWith("$pkg|") } }

    fun forget(ctx: Context, pkg: String) {
        edit(ctx, "verified") { remove(pkg) }
        removePending(ctx, pkg)
        removeHeld(ctx, pkg)
    }

    // ---------- מטמון קטגוריות ----------

    fun cachedCategory(ctx: Context, pkg: String): String? {
        val p = prefs(ctx)
        val at = p.getLong("cat_t_$pkg", 0)
        if (System.currentTimeMillis() - at > CACHE_TTL_MS) return null
        return p.getString("cat_$pkg", null)
    }

    fun cacheCategory(ctx: Context, pkg: String, category: String) {
        prefs(ctx).edit()
            .putString("cat_$pkg", category)
            .putLong("cat_t_$pkg", System.currentTimeMillis())
            .apply()
    }

    // ---------- עזרים ----------

    private fun getSet(ctx: Context, key: String): Set<String> =
        prefs(ctx).getStringSet(key, emptySet()).orEmpty().toSet()

    @Synchronized
    private fun edit(ctx: Context, key: String, change: MutableSet<String>.() -> Unit) {
        val set = getSet(ctx, key).toMutableSet()
        set.change()
        prefs(ctx).edit().putStringSet(key, set).apply()
    }
}
