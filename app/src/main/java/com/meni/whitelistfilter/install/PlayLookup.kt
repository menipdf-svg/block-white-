package com.meni.whitelistfilter.install

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface LookupResult {
    /** הקטגוריה כפי שגוגל מגדירה אותה, למשל SOCIAL או GAME_PUZZLE */
    data class Found(val category: String) : LookupResult

    /** גוגל פליי ענה שהאפליקציה לא קיימת */
    data object NotOnPlay : LookupResult

    /** אין אינטרנט, חסימה זמנית או דף במבנה שלא הצלחנו לקרוא — מנסים שוב מאוחר יותר */
    data object Failed : LookupResult
}

/**
 * שולף את הקטגוריה של אפליקציה מדף ה-Google Play שלה.
 * הבקשה יוצאת מהאפליקציה שלנו ישירות (היא מוחרגת מה-VPN), לא דרך המסנן.
 */
object PlayLookup {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val jsonCategory = Regex("\"applicationCategory\"\\s*:\\s*\"([A-Z_]+)\"")
    private val categoryLink = Regex("/store/apps/category/([A-Z_]+)")

    fun lookup(pkg: String): LookupResult {
        var conn: HttpURLConnection? = null
        return try {
            val id = URLEncoder.encode(pkg, "UTF-8")
            val url = URL("https://play.google.com/store/apps/details?id=$id&hl=en&gl=IL")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            when (conn.responseCode) {
                404 -> LookupResult.NotOnPlay
                200 -> {
                    // הפניה לדף הסכמה או לאתר אחר — זו לא תשובה על האפליקציה
                    if (conn.url.host != "play.google.com") return LookupResult.Failed
                    parse(conn.inputStream.bufferedReader().use { it.readText() })
                }
                else -> LookupResult.Failed
            }
        } catch (_: Exception) {
            LookupResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parse(html: String): LookupResult {
        jsonCategory.find(html)?.let { return LookupResult.Found(it.groupValues[1]) }

        // גיבוי: קישור הקטגוריה שליד תגית genre
        val at = html.indexOf("itemprop=\"genre\"")
        if (at >= 0) {
            val from = maxOf(0, at - 400)
            val to = minOf(html.length, at + 400)
            categoryLink.find(html.substring(from, to))?.let {
                return LookupResult.Found(it.groupValues[1])
            }
        }
        return LookupResult.Failed
    }
}
