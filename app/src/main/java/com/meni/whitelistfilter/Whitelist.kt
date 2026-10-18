package com.meni.whitelistfilter

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Whitelist {
    private const val PREFS = "whitelist"
    private const val KEY = "domains"

    private lateinit var prefs: SharedPreferences
    private val _domains = MutableStateFlow<Set<String>>(emptySet())
    val domains: StateFlow<Set<String>> = _domains

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _domains.value = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()
    }

    fun add(raw: String) {
        val d = normalize(raw)
        if (d.isEmpty() || !d.contains('.')) return
        save(_domains.value + d)
    }

    fun remove(domain: String) = save(_domains.value - domain)

    // דומיין מותר אם הוא ברשימה או תת-דומיין של משהו ברשימה
    fun isAllowed(domain: String): Boolean {
        val d = normalize(domain)
        return _domains.value.any { d == it || d.endsWith(".$it") }
    }

    private fun save(set: Set<String>) {
        _domains.value = set
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun normalize(raw: String): String = raw.trim().lowercase()
        .removePrefix("https://").removePrefix("http://")
        .substringBefore("/").substringBefore(":")
        .removePrefix("www.").trimEnd('.')
}
