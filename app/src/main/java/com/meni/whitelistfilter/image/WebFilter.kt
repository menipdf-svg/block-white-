package com.meni.whitelistfilter.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Process
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import com.meni.whitelistfilter.browser.AdBlock
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


    companion object {
        private const val TAG = "WebFilter"
        private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
        /** תמונות קטנות מזה הן אייקונים וכפתורים — לא בודקים */
        private const val MIN_SIDE = 48
        /** גודל התמונה שנותנים למודל */
        private const val ANALYZE_SIDE = 640
        /** גודל מקסימלי לתמונה המטושטשת שמחזירים */
        private const val MAX_OUT_SIDE = 1600

        /** תמונה שקופה בגודל פיקסל — במקום אייקון "תמונה שבורה" כשמשהו נכשל */
        private val TRANSPARENT_GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        )

        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "heic", "heif")
        private val SKIP_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie", "content-type")
    }

    fun isAllowedHost(host: String): Boolean = Rules.isAllowed(uid, host)

    /** בדיקת תמונה שכבר נמצאת בידינו (למשל תמונה מוטמעת בדף). true = לחסום */
    fun isBlocked(bytes: ByteArray, label: String): Boolean {
        val cfg = ImageFilterSettings.config.value
        if (!cfg.active) return false
        val id = "inline:" + sha1(bytes)
        DiskImageCache.getVerdict(ctx, cfg, id)?.let { return it }
        val blocked = judge(bytes, cfg, label) != null
        DiskImageCache.putVerdict(ctx, cfg, id, blocked)
        FilterState.imageChecked(blocked)
        return blocked
    }

    private fun sha1(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = url.host ?: return null
        val domain = Rules.normalize(host)
        val cfg = ImageFilterSettings.config.value

        // פרסומות ומעקב — נחסמים לפני הכל
        if (cfg.blockAds && AdBlock.isAd(host)) {
            FilterState.adBlocked()
            return if (request.isForMainFrame) blockedPage(domain) else empty(204, "No Content")
        }

        if (!isAllowedHost(host)) {
            FilterState.block(uid, domain, true)
            return if (request.isForMainFrame) blockedPage(domain) else empty(403, "Blocked")
        }
        FilterState.request(uid, domain, true, true)

        if (!cfg.active) return null
        if (!request.method.equals("GET", true) || !looksLikeImage(request)) return null
        // SVG (לוגואים ואייקונים) — הדפדפן צריך אותם עם הסוג הנכון, ולא בודקים אותם ממילא
        if (url.lastPathSegment?.lowercase()?.endsWith(".svg") == true) return null

        return try {
            fetchAndFilter(request, cfg)
        } catch (e: Exception) {
            Log.w(TAG, "image ${url}: ${e.message}")
            empty(502, "Bad Gateway")
        }
    }

    private fun looksLikeImage(r: WebResourceRequest): Boolean {
        val accept = r.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (accept.startsWith("image/")) return true
        val ext = r.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in IMAGE_EXT
    }

    /**
     * בקשת תמונה:
     *  1. אם התמונה כבר נבדקה (מטמון בדיסק) — מחזירים אותה מיד.
     *  2. אחרת פותחים חיבור לשרת ומחכים רק לתשובה הראשונה שלו (קוד, סוג, כותרות) — כמו דפדפן רגיל.
     *     כך הדפדפן מקבל את הסוג והכותרות האמיתיים, ושום דבר בדף לא נשבר.
     *  3. התוכן עצמו נקרא ונבדק במודלים רק כשהדפדפן קורא ממנו — ברקע, בלי לתקוע את שאר הדף.
     */
    private fun fetchAndFilter(request: WebResourceRequest, cfg: ImageFilterConfig): WebResourceResponse? {
        val urlStr = request.url.toString()
        val origin = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value

        DiskImageCache.get(ctx, cfg, urlStr)?.let { cached ->
            return WebResourceResponse(sniffMime(cached), null, 200, "OK", corsHeaders(origin, null), ByteArrayInputStream(cached))
        }

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        request.requestHeaders.forEach { (k, v) ->
            if (!k.equals("Accept-Encoding", true) && !k.equals("Accept", true)) conn.setRequestProperty(k, v)
        }
        // AVIF אנדרואיד יודע לפענח רק מגרסה 12. בגרסאות ישנות מבקשים פורמט אחר, כדי שנוכל לבדוק
        conn.setRequestProperty(
            "Accept",
            if (Build.VERSION.SDK_INT >= 31) "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            else "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        )
        if (conn.getRequestProperty("User-Agent") == null) conn.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(urlStr)?.let { conn.setRequestProperty("Cookie", it) }

        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            Log.w(TAG, "image $urlStr: ${e.message}")
            return empty(502, "Bad Gateway")
        }

        // הפניה לדומיין אחר — גם הוא צריך להיות ברשימה
        val finalHost = conn.url.host
        if (finalHost != null && !isAllowedHost(finalHost)) {
            conn.disconnect()
            FilterState.block(uid, Rules.normalize(finalHost), true)
            return empty(403, "Blocked")
        }
        conn.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(urlStr, it) }

        val contentType = conn.contentType ?: ""
        val mime = contentType.substringBefore(';').trim().lowercase().ifEmpty { "application/octet-stream" }
        val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"
        val headers = corsHeaders(origin, conn)

        // שגיאה מהשרת, SVG, או בכלל לא תמונה — מעבירים בדיוק כמו שהם, עם הסוג והכותרות המקוריים
        val isRaster = mime.startsWith("image/") && !mime.contains("svg")
        val maybeImage = isRaster || mime == "application/octet-stream" || mime == "binary/octet-stream"
        if (code !in 200..299 || !maybeImage) {
            val body: InputStream = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?: ByteArrayInputStream(ByteArray(0))
            return WebResourceResponse(mime, charsetOf(contentType), code, reason, headers, ClosingStream(body, conn))
        }

        val stream = LazyStream {
            try {
                val bytes = conn.inputStream.use { readLimited(it) }
                if (bytes.isEmpty()) return@LazyStream bytes
                val blocked = judge(bytes, cfg, urlStr)
                FilterState.imageChecked(blocked != null)
                val out = blocked?.second ?: bytes
                DiskImageCache.put(ctx, cfg, urlStr, out)
                out
            } catch (e: Exception) {
                Log.w(TAG, "image $urlStr: ${e.message}")
                TRANSPARENT_GIF
            } finally {
                conn.disconnect()
            }
        }
        return WebResourceResponse(if (isRaster) mime else "image/jpeg", null, code, reason, headers, stream)
    }

    /**
     * כותרות CORS: מעבירים את המקוריות של השרת. אם אין — מאפשרים למקור של הדף,
     * כולל תמונות שנטענות עם הרשאות (crossorigin="use-credentials").
     */
    private fun corsHeaders(origin: String?, conn: HttpURLConnection?): Map<String, String> {
        val map = HashMap<String, String>()
        conn?.headerFields?.forEach { (k, v) ->
            if (k != null && v != null && k.startsWith("Access-Control-", true)) map[k] = v.joinToString(", ")
        }
        if (map.keys.none { it.equals("Access-Control-Allow-Origin", true) }) {
            if (origin != null && origin != "null") {
                map["Access-Control-Allow-Origin"] = origin
                map["Access-Control-Allow-Credentials"] = "true"
            } else {
                map["Access-Control-Allow-Origin"] = "*"
            }
        }
        map["Cache-Control"] = "no-store"
        return map
    }

    /** זיהוי סוג התמונה לפי הבתים הראשונים (לתמונות מהמטמון) */
    private fun sniffMime(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
        b.size >= 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
        b.size >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() -> "image/avif"
        else -> "image/jpeg"
    }

    /** זרם שסוגר גם את החיבור כשהדפדפן מסיים לקרוא */
    private class ClosingStream(private val inner: InputStream, private val conn: HttpURLConnection) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun available(): Int = inner.available()
        override fun close() {
            try {
                inner.close()
            } finally {
                conn.disconnect()
            }
        }
    }

    /** זרם שמחשב את התוכן שלו רק בקריאה הראשונה */
    private class LazyStream(private val producer: () -> ByteArray) : InputStream() {
        private var data: ByteArrayInputStream? = null

        @Synchronized
        private fun data(): ByteArrayInputStream =
            data ?: ByteArrayInputStream(producer()).also { data = it }

        override fun read(): Int = data().read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = data().read(b, off, len)
        override fun available(): Int = data?.available() ?: 0
        override fun close() {
            data?.close()
        }
    }

    /** null = התמונה בסדר. אחרת: (סוג, בתים) של התמונה המטושטשת */
    private fun judge(bytes: ByteArray, cfg: ImageFilterConfig, url: String): Pair<String, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight

        if (w <= 0 || h <= 0) {
            // רוב הבקשות האלה הן פיקסלי מעקב של פרסומות (תשובה ריקה או לא תמונה) — מעבירים בשקט.
            // רק תמונה אמיתית בפורמט שאנדרואיד לא יודע לפענח (AVIF/HEIC) נחשבת "לא נבדקה".
            val isRealImage = bytes.size >= 12 &&
                bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            if (!isRealImage) return null
            FilterState.logImage(url, cfg.blockUnchecked, "פורמט לא נתמך", "${bytes.size} bytes")
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
        )
        if (bmp == null) {
            FilterState.logImage(url, cfg.blockUnchecked, "פענוח נכשל", "${w}x$h")
            return if (cfg.blockUnchecked) placeholder(w, h) else null
        }

        try {
            val found = try {
                ImageJudge.find(ctx, bmp, cfg)
            } catch (e: Exception) {
                Log.w(TAG, "judge: ${e.message}")
                FilterState.logImage(url, cfg.blockUnchecked, "שגיאה בבדיקה", e.message ?: "")
                return if (cfg.blockUnchecked) blur(bmp, w, h) else null
            }
            val detail = "${w}x$h · " + ImageJudge.lastDetail
            FilterState.logImage(url, found.isNotEmpty(), found.joinToString(", ") { reasonTitle(it) }.ifEmpty { "תקין" }, detail)
            return if (found.isEmpty()) null else blur(bmp, w, h)
        } finally {
            bmp.recycle()
        }
    }

    private fun reasonTitle(r: String) = when (r) {
        "PEOPLE" -> "אנשים"
        "WOMEN_NUDENET" -> "אישה (NudeNet)"
        "WOMEN" -> "אישה"
        else -> r
    }

    /**
     * תמונה שנחסמה מוחלפת במשטח אפור אחיד עם סימן "חסום".
     * שום פיקסל מהתמונה המקורית לא נשאר — אי אפשר לנחש מה היה בה.
     * הגודל נשמר כמו במקור, כדי שהעמוד לא יזוז.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun blur(src: Bitmap, origW: Int, origH: Int): Pair<String, ByteArray> {
        val scale = minOf(1f, MAX_OUT_SIDE.toFloat() / max(origW, origH))
        val outW = max(1, (origW * scale).roundToInt())
        val outH = max(1, (origH * scale).roundToInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.rgb(200, 200, 200))

        // סימן "חסום" במרכז: עיגול עם קו אלכסוני
        val size = minOf(outW, outH) * 0.28f
        if (size >= 14f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2f, size * 0.12f)
                color = Color.rgb(150, 150, 150)
            }
            val cx = outW / 2f
            val cy = outH / 2f
            val r = size / 2f
            val c = Canvas(out)
            c.drawCircle(cx, cy, r, paint)
            val d = r * 0.7071f
            c.drawLine(cx - d, cy - d, cx + d, cy + d, paint)
        }
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
