package com.meni.whitelistfilter.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** אפליקציה אחת בקטלוג החנות */
data class StoreApp(
    val pkg: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val url: String,
    val sha256: String,
    val desc: String
)

/**
 * הקטלוג של החנות: קובץ JSON שמארח אותו אתה (ברירת מחדל: store/catalog.json ב-GitHub).
 * רק חבילות שמופיעות בו מותרות להתקנה כשמצב "רק מהחנות" פעיל.
 */
object StoreCatalog {
    const val DEFAULT_URL = "https://raw.githubusercontent.com/menipdf-svg/block-white-/main/store/catalog.json"

    /** עולה בכל שינוי בקטלוג או בהתקנות, כדי שהמסך ירענן */
    val version = MutableStateFlow(0)

    fun bump() {
        version.update { it + 1 }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    fun url(ctx: Context): String = prefs(ctx).getString("url", DEFAULT_URL) ?: DEFAULT_URL

    fun apps(ctx: Context): List<StoreApp> = parse(prefs(ctx).getString("catalog", "") ?: "") ?: emptyList()

    fun allowedPackages(ctx: Context): Set<String> = apps(ctx).map { it.pkg }.toSet()

    /** null אם הטקסט אינו קטלוג תקין */
    fun parse(text: String): List<StoreApp>? = try {
        val arr = JSONObject(text).getJSONArray("apps")
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.optString("pkg")
                val url = o.optString("url")
                val sha = o.optString("sha256").lowercase()
                if (pkg.isEmpty() || !url.startsWith("https://") || sha.length != 64) continue
                add(
                    StoreApp(
                        pkg = pkg,
                        name = o.optString("name", pkg).ifEmpty { pkg },
                        version = o.optString("version"),
                        versionCode = o.optLong("versionCode", 0),
                        url = url,
                        sha256 = sha,
                        desc = o.optString("desc")
                    )
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    /** מוריד את הקטלוג ושומר אותו. onDone(null) = הצלחה, אחרת הודעת שגיאה */
    fun refresh(ctx: Context, onDone: (String?) -> Unit) {
        val app = ctx.applicationContext
        thread {
            val error: String? = try {
                val conn = URL(url(app)).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) {
                    "השרת החזיר ${conn.responseCode}"
                } else {
                    val text = conn.inputStream.bufferedReader().readText()
                    if (parse(text) == null) {
                        "הקובץ אינו קטלוג תקין"
                    } else {
                        prefs(app).edit().putString("catalog", text).apply()
                        null
                    }
                }
            } catch (e: Exception) {
                e.message ?: "אין חיבור"
            }
            Handler(Looper.getMainLooper()).post {
                bump()
                onDone(error)
            }
        }
    }
}
