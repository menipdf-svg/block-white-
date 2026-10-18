package com.meni.whitelistfilter.browser

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class PageEntry(val url: String, val title: String, val time: Long)

/** סימניות, היסטוריה והכרטיסיות הפתוחות — נשמרים במכשיר */
object BrowserStore {
    private const val PREFS = "browser"
    private const val MAX_HISTORY = 500

    private lateinit var prefs: SharedPreferences

    private val _bookmarks = MutableStateFlow<List<PageEntry>>(emptyList())
    val bookmarks: StateFlow<List<PageEntry>> = _bookmarks

    private val _history = MutableStateFlow<List<PageEntry>>(emptyList())
    val history: StateFlow<List<PageEntry>> = _history

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _bookmarks.value = load("bookmarks")
        _history.value = load("history")
    }

    // ----- סימניות -----

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) return
        _bookmarks.update { list ->
            if (list.any { it.url == url }) list.filterNot { it.url == url }
            else listOf(PageEntry(url, title.ifBlank { url }, System.currentTimeMillis())) + list
        }
        save("bookmarks", _bookmarks.value)
    }

    fun removeBookmark(url: String) {
        _bookmarks.update { list -> list.filterNot { it.url == url } }
        save("bookmarks", _bookmarks.value)
    }

    // ----- היסטוריה -----

    fun addHistory(url: String, title: String) {
        if (!url.startsWith("http")) return
        _history.update { list ->
            (listOf(PageEntry(url, title.ifBlank { url }, System.currentTimeMillis())) +
                list.filterNot { it.url == url }).take(MAX_HISTORY)
        }
        save("history", _history.value)
    }

    /** הכותרת מגיעה אחרי שהדף התחיל להיטען — מעדכנים את הרשומה האחרונה */
    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        var changed = false
        _history.update { list ->
            list.map { if (it.url == url && it.title != title) { changed = true; it.copy(title = title) } else it }
        }
        if (changed) save("history", _history.value)
    }

    fun removeHistory(url: String) {
        _history.update { list -> list.filterNot { it.url == url } }
        save("history", _history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        save("history", emptyList())
    }

    // ----- כרטיסיות פתוחות (כדי לשחזר אחרי סגירת האפליקציה) -----

    fun saveTabs(urls: List<String>, current: Int) {
        prefs.edit()
            .putString("tabs", JSONArray(urls).toString())
            .putInt("current_tab", current)
            .apply()
    }

    fun loadTabs(): Pair<List<String>, Int> {
        val urls = try {
            val a = JSONArray(prefs.getString("tabs", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        return urls to prefs.getInt("current_tab", 0)
    }

    // ----- שמירה -----

    private fun save(key: String, list: List<PageEntry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun load(key: String): List<PageEntry> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            PageEntry(o.getString("u"), o.optString("t"), o.optLong("d"))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
