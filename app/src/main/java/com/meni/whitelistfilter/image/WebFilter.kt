package com.meni.whitelistfilter.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * כל בקשה של הדפדפן עוברת כאן:
 *  1. בדיקת הדומיין מול הרשימה הלבנה (האפליקציה עצמה לא עוברת דרך ה-VPN, אז הבדיקה כאן)
 *  2. תמונות — מורידים בעצמנו, בודקים עם המודל המקומי, ומחזירים לדפדפן את המקור או גרסה מטושטשת
 */
class WebFilter(context: Context, private val userAgent: String) {
    private val ctx = context.applicationContext
    private val uid = Process.myUid()

    /** תוצאות קודמות לפי כתובת התמונה (true = טושטשה) */
    private val verdicts = LruCache<String, Boolean>(500)
    private var verdictsFor: ImageFilterConfig? = null

    companion object {
        private const val TAG = "WebFilter"
        private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
        /** תמונות קטנות מזה הן אייקונים וכפתורים — לא בודקים */
        private const val MIN_SIDE = 48
        /** גודל התמונה שנותנים למודל */
        private const val ANALYZE_SIDE = 640
        /** גודל מקסימלי לתמונה המטושטשת שמחזירים */
        private const val MAX_OUT_SIDE = 1600

        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "heic", "heif")
        private val SKIP_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie", "content-type")
    }

    fun isAllowedHost(host: String): Boolean = Rules.isAllowed(uid, host)

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = url.host ?: return null
        val domain = Rules.normalize(host)

        if (!isAllowedHost(host)) {
            FilterState.block(uid, domain, true)
            return if (request.isForMainFrame) blockedPage(domain) else empty(403, "Blocked")
        }
        FilterState.request(uid, domain, true, true)

        val cfg = ImageFilterSettings.config.value
        if (!cfg.active) return null
        if (!request.method.equals("GET", true) || !looksLikeImage(request)) return null

        return try {
            fetchAndFilter(request, cfg)
        } catch (e: Exception) {
            Log.w(TAG, "image ${url}: ${e.message}")
            empty(502, "Image check failed")
        }
    }

    private fun looksLikeImage(r: WebResourceRequest): Boolean {
        val accept = r.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (accept.startsWith("image/")) return true
        val ext = r.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in IMAGE_EXT
    }

    private fun fetchAndFilter(request: WebResourceRequest, cfg: ImageFilterConfig): WebResourceResponse {
        val urlStr = request.url.toString()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            request.requestHeaders.forEach { (k, v) ->
                if (!k.equals("Accept-Encoding", true) && !k.equals("Accept", true)) conn.setRequestProperty(k, v)
            }
            // AVIF אנדרואיד יודע לפענח רק מגרסה 12. בגרסאות ישנות מבקשים פורמט אחר, כדי שנוכל לבדוק
            conn.setRequestProperty(
                "Accept",
                if (Build.VERSION.SDK_INT >= 31) "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                else "image/webp,image/apng,image/*,*/*;q=0.8"
            )
            if (conn.getRequestProperty("User-Agent") == null) conn.setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(urlStr)?.let { conn.setRequestProperty("Cookie", it) }

            val code = conn.responseCode
            // הפניה לדומיין אחר — גם הוא צריך להיות ברשימה
            val finalHost = conn.url.host
            if (finalHost != null && !isAllowedHost(finalHost)) {
                FilterState.block(uid, Rules.normalize(finalHost), true)
                return empty(403, "Blocked")
            }

            conn.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(urlStr, it) }

            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readLimited(it) } ?: ByteArray(0)
            val contentType = conn.contentType ?: ""
            val mime = contentType.substringBefore(';').trim().lowercase().ifEmpty { "application/octet-stream" }
            val headers = responseHeaders(conn)
            val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"

            // לא תמונה, שגיאה, או SVG (גרפיקה וקטורית — אייקונים) → מעבירים כמו שזה
            if (code !in 200..299 || !mime.startsWith("image/") || mime.contains("svg")) {
                return WebResourceResponse(mime, charsetOf(contentType), code, reason, headers, ByteArrayInputStream(bytes))
            }

            val key = cfg.hashCode().toString() + "|" + urlStr
            synchronized(verdicts) {
                if (verdictsFor != cfg) {
                    verdicts.evictAll()
                    verdictsFor = cfg
                }
            }
            val cached = verdicts.get(key)
            if (cached == false) {
                return WebResourceResponse(mime, null, code, reason, headers, ByteArrayInputStream(bytes))
            }

            val blurred = judge(bytes, cfg)
            verdicts.put(key, blurred != null)
            FilterState.imageChecked(blurred != null)
            return if (blurred == null) {
                WebResourceResponse(mime, null, code, reason, headers, ByteArrayInputStream(bytes))
            } else {
                WebResourceResponse(blurred.first, null, 200, "OK", headers, ByteArrayInputStream(blurred.second))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** null = התמונה בסדר. אחרת: (סוג, בתים) של התמונה המטושטשת */
    private fun judge(bytes: ByteArray, cfg: ImageFilterConfig): Pair<String, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight

        if (w <= 0 || h <= 0) {
            // פורמט שלא הצלחנו לפענח
            return if (cfg.blockUnchecked) placeholder(320, 240) else null
        }
        if (w < MIN_SIDE || h < MIN_SIDE) return null

        var sample = 1
        while (max(w, h) / (sample * 2) >= ANALYZE_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return if (cfg.blockUnchecked) placeholder(w, h) else null

        try {
            val found = try {
                ImageJudge.find(ctx, bmp, cfg)
            } catch (e: Exception) {
                Log.w(TAG, "judge: ${e.message}")
                return if (cfg.blockUnchecked) blur(bmp, w, h) else null
            }
            return if (found.isEmpty()) null else blur(bmp, w, h)
        } finally {
            bmp.recycle()
        }
    }

    /** טשטוש חזק: מקטינים מאוד ומגדילים חזרה לגודל המקורי, כדי שהעמוד לא יזוז */
    private fun blur(src: Bitmap, origW: Int, origH: Int): Pair<String, ByteArray> {
        val scale = minOf(1f, MAX_OUT_SIDE.toFloat() / max(origW, origH))
        val outW = max(1, (origW * scale).roundToInt())
        val outH = max(1, (origH * scale).roundToInt())

        val tiny = Bitmap.createScaledBitmap(src, max(1, src.width / 28), max(1, src.height / 28), true)
        val mid = Bitmap.createScaledBitmap(tiny, max(1, outW / 8), max(1, outH / 8), true)
        val out = Bitmap.createScaledBitmap(mid, outW, outH, true).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(Color.argb(70, 120, 120, 120))
        tiny.recycle()
        mid.recycle()
        return encode(out)
    }

    private fun placeholder(w: Int, h: Int): Pair<String, ByteArray> {
        val out = Bitmap.createBitmap(max(1, minOf(w, MAX_OUT_SIDE)), max(1, minOf(h, MAX_OUT_SIDE)), Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.rgb(190, 190, 190))
        return encode(out)
    }

    private fun encode(bmp: Bitmap): Pair<String, ByteArray> {
        val os = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, os)
        bmp.recycle()
        return "image/jpeg" to os.toByteArray()
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_IMAGE_BYTES) throw IOException("image too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun responseHeaders(conn: HttpURLConnection): Map<String, String> {
        val map = HashMap<String, String>()
        conn.headerFields.forEach { (k, v) ->
            if (k != null && k.lowercase() !in SKIP_HEADERS && v != null) map[k] = v.joinToString(", ")
        }
        return map
    }

    private fun charsetOf(contentType: String): String? =
        contentType.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("charset=", true) }
            ?.substringAfter('=')?.trim('"')

    private fun empty(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun blockedPage(domain: String): WebResourceResponse {
        val html = """
            <!doctype html><html dir="rtl" lang="he"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;
                   justify-content:center;min-height:90vh;margin:0;padding:24px;text-align:center;
                   background:#f6f6f6;color:#222}
              .d{direction:ltr;background:#fff;border-radius:8px;padding:6px 12px;margin:12px;font-family:monospace}
              @media (prefers-color-scheme: dark){body{background:#121212;color:#eee}.d{background:#222}}
            </style></head><body>
            <div style="font-size:56px">⛔</div>
            <h2>האתר הזה לא ברשימה הלבנה</h2>
            <div class="d">${domain.replace("<", "")}</div>
            <p>אפשר לאשר אותו מתוך אפליקציית המסנן, בלשונית "תמונות" ← "דומיינים של הדפדפן".</p>
            </body></html>
        """.trimIndent()
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }
}
