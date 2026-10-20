package com.meni.whitelistfilter.debug

import android.content.Context
import android.os.Build
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.image.ImageFilterSettings
import com.meni.whitelistfilter.image.ImageJudge
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * שליחת יומן התמונות ל-GitHub, כתגובה ב-Issue קבוע בריפו של הפרויקט — משם Claude קורא אותו.
 * צריך טוקן GitHub (fine-grained) עם הרשאת Issues: Read and write לריפו הזה בלבד.
 * הטוקן נשמר רק באחסון הפרטי של האפליקציה.
 */
object LogSync {
    private const val REPO = "menipdf-svg/block-white-"
    private const val ISSUE_TITLE = "יומן מהמכשיר"
    private const val PREFS = "log_sync"
    private const val MAX_BODY = 60_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)?.takeIf { it.isNotBlank() }

    fun setToken(ctx: Context, token: String?) {
        prefs(ctx).edit().putString("token", token?.trim()).remove("issue").apply()
    }

    /** הדוח שנשלח: פרטי המכשיר, ההגדרות ושורות היומן */
    fun buildReport(ctx: Context): String {
        val cfg = ImageFilterSettings.config.value
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val firefox = listOf("org.mozilla.fenix.debug", "org.mozilla.fenix").firstNotNullOfOrNull { pkg ->
            try {
                pkg + " " + ctx.packageManager.getPackageInfo(pkg, 0).versionName
            } catch (_: Exception) {
                null
            }
        } ?: "לא מותקן"

        val sb = StringBuilder()
        sb.append("### יומן מ-").append(fmt.format(Date())).append("\n\n")
        sb.append("- אפליקציה: ").append(version).append("\n")
        sb.append("- פיירפוקס: ").append(firefox).append("\n")
        sb.append("- מכשיר: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("- הגדרות: פעיל=").append(cfg.enabled)
            .append(", קטגוריות=").append(cfg.categories.joinToString { it.name }.ifEmpty { "-" })
            .append(", נשים=").append(cfg.women.name)
            .append(", רגישות=").append(cfg.sensitivity.name)
            .append(", חסום-לא-נבדק=").append(cfg.blockUnchecked).append("\n")
        sb.append("- נבדקו: ").append(FilterState.imagesChecked.value)
            .append(", נחסמו: ").append(FilterState.imagesBlurred.value).append("\n")
        ImageJudge.error?.let { sb.append("- שגיאת מודלים: ").append(it.replace("\n", " | ")).append("\n") }
        sb.append("\n```\n")
        val log = FilterState.imageLog.value
        if (log.isEmpty()) sb.append("(היומן ריק)\n")
        for (e in log) {
            val line = "${fmt.format(Date(e.time))} ${if (e.blocked) "BLOCK" else "PASS "} ${e.reason} | ${e.detail} | ${e.url.take(120)}\n"
            if (sb.length + line.length > MAX_BODY) {
                sb.append("... (קוצר)\n")
                break
            }
            sb.append(line)
        }
        sb.append("```\n")
        return sb.toString()
    }

    /** שולח את הדוח. מחזיר הודעה למשתמש. רץ ברקע — לא מהתהליכון הראשי */
    fun send(ctx: Context): String {
        val token = token(ctx) ?: return "לא הוגדר טוקן"
        val body = buildReport(ctx)
        return try {
            val issue = issueNumber(ctx, token)
            val (code, _) = request("POST", "/repos/$REPO/issues/$issue/comments", token, JSONObject().put("body", body))
            when (code) {
                in 200..299 -> "היומן נשלח (Issue #$issue)"
                401 -> "הטוקן לא תקין או שפג תוקפו"
                403, 404 -> "לטוקן אין הרשאת Issues לריפו הזה"
                else -> "השליחה נכשלה (HTTP $code)"
            }
        } catch (e: Exception) {
            "השליחה נכשלה: ${e.message}"
        }
    }

    /** מספר ה-Issue הקבוע: שמור, או קיים בריפו לפי הכותרת, או נוצר עכשיו */
    private fun issueNumber(ctx: Context, token: String): Int {
        prefs(ctx).getInt("issue", 0).takeIf { it > 0 }?.let { return it }

        val (code, text) = request("GET", "/repos/$REPO/issues?state=open&per_page=50", token, null)
        if (code in 200..299) {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("title") == ISSUE_TITLE && !o.has("pull_request")) {
                    val n = o.getInt("number")
                    prefs(ctx).edit().putInt("issue", n).apply()
                    return n
                }
            }
        } else if (code == 401) {
            throw IllegalStateException("הטוקן לא תקין או שפג תוקפו")
        }

        val (c2, t2) = request(
            "POST", "/repos/$REPO/issues", token,
            JSONObject().put("title", ISSUE_TITLE).put("body", "יומני תמונות שנשלחים מהאפליקציה בלחיצה על הכפתור.")
        )
        if (c2 !in 200..299) throw IllegalStateException("לא הצלחתי ליצור Issue (HTTP $c2)")
        val n = JSONObject(t2).getInt("number")
        prefs(ctx).edit().putInt("issue", n).apply()
        return n
    }

    private fun request(method: String, path: String, token: String, json: JSONObject?): Pair<Int, String> {
        val conn = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
